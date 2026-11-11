package com.chargenap.app.vehicle.pleos

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// 시트·공조·도어 값 변환. 전부 순수 함수라 단위 테스트로 검증한다.

/**
 * 등받이 이동 방향.
 *
 * 가정(docs/sdk-notes.md 5.2, AAOS 정의): 위치 값이 작을수록 누운 것이다(min = 완전히 눕힘, max = 가장 세움).
 * 그래서 눕히면 위치 값이 줄고([positionSign] = -1), 세우면 늘어난다.
 * Pleos Vehicle Service가 이 정의를 따르는지는 미검증이므로 [runSeatMove]가 실제 변화 방향을 감시한다.
 */
internal enum class SeatDirection(val positionSign: Int) {
    RECLINE(-1),
    RAISE(+1),
}

/**
 * 등받이를 [start]에서 [target]까지 [direction]으로 움직이는 계획.
 *
 * @param tolerance 이 값 이하의 위치 차이는 센서 흔들림으로 본다
 */
internal data class SeatMovePlan(
    val start: Int,
    val target: Int,
    val direction: SeatDirection,
    val tolerance: Int,
) {
    fun reached(position: Int): Boolean = when (direction) {
        SeatDirection.RECLINE -> position <= target
        SeatDirection.RAISE -> position >= target
    }

    /** 시작 위치에서 목표와 반대쪽으로 [tolerance]를 넘게 움직였는지. */
    fun movedAway(position: Int): Boolean =
        (position.toLong() - start) * direction.positionSign < -tolerance.toLong()
}

internal fun isValidBackrestRange(min: Int, max: Int): Boolean = max > min

/** 범위의 1%. 좁은 범위(100 미만)에서는 0이라 한 칸만 어긋나도 감지한다. */
internal fun backrestTolerance(min: Int, max: Int): Int =
    if (isValidBackrestRange(min, max)) ((max.toLong() - min) / 100).toInt() else 0

/**
 * `reclineLevel`(0..100, 가동 범위의 %) → 목표 위치. `max − (max − min) × level / 100`.
 * 범위가 퇴화(min ≥ max)했으면 null.
 */
internal fun reclineTargetPosition(min: Int, max: Int, reclineLevel: Int): Int? {
    if (!isValidBackrestRange(min, max)) return null
    val level = reclineLevel.coerceIn(0, 100)
    val travel = ((max.toLong() - min) * level / 100.0).roundToLong()
    return (max - travel).toInt()
}

/**
 * 수면 프리셋용 계획. 움직일 필요가 없으면 null:
 * `reclineLevel ≤ 0`, 범위 퇴화, 이미 목표만큼(또는 더) 누워 있음.
 * 수면 프리셋은 세우는 방향으로는 절대 움직이지 않는다.
 */
internal fun planRecline(min: Int, max: Int, current: Int, reclineLevel: Int): SeatMovePlan? {
    if (reclineLevel <= 0) return null
    val target = reclineTargetPosition(min, max, reclineLevel) ?: return null
    val tolerance = backrestTolerance(min, max)
    if (current <= target.toLong() + tolerance) return null
    return SeatMovePlan(start = current, target = target, direction = SeatDirection.RECLINE, tolerance = tolerance)
}

/**
 * 복구용 계획: 스냅샷 위치까지 세운다. 움직일 필요가 없으면 null:
 * 범위 퇴화, 이미 스냅샷만큼(또는 더) 서 있음. 복구는 눕히는 방향으로는 움직이지 않는다.
 */
internal fun planRaise(min: Int, max: Int, current: Int, snapshotPosition: Int): SeatMovePlan? {
    if (!isValidBackrestRange(min, max)) return null
    val target = snapshotPosition.coerceIn(min, max)
    val tolerance = backrestTolerance(min, max)
    if (current >= target.toLong() - tolerance) return null
    return SeatMovePlan(start = current, target = target, direction = SeatDirection.RAISE, tolerance = tolerance)
}

internal const val FAN_LEVEL_MIN = 1
internal const val FAN_LEVEL_MAX = 5

/**
 * 추상 풍량 단계(1..5) → 차량 풍량 값. 차량 범위 `min..max`에 비례 변환한다.
 * 0 이하는 "꺼짐"으로 보고 1부터 쓴다(세션 동안 환기를 유지해야 한다). 쓸 수 있는 값이 없으면 null.
 * 예: 0..8 → 1, 3, 5, 6, 8
 */
internal fun vehicleFanSpeed(level: Int, min: Int, max: Int): Int? {
    val lowest = if (min <= 0) 1 else min
    if (max < lowest) return null
    val step = level.coerceIn(FAN_LEVEL_MIN, FAN_LEVEL_MAX) - FAN_LEVEL_MIN
    val span = (max.toLong() - lowest).toDouble()
    return lowest + (span * step / (FAN_LEVEL_MAX - FAN_LEVEL_MIN)).roundToInt()
}

/** 차량 풍량 값 → 가장 가까운 추상 단계(1..5). 변환할 수 없는 범위면 null. */
internal fun fanLevelFromVehicle(speed: Int, min: Int, max: Int): Int? {
    var best: Int? = null
    var bestDistance = Long.MAX_VALUE
    for (level in FAN_LEVEL_MIN..FAN_LEVEL_MAX) {
        val mapped = vehicleFanSpeed(level, min, max) ?: return null
        val distance = abs(mapped.toLong() - speed)
        if (distance < bestDistance) {
            best = level
            bestDistance = distance
        }
    }
    return best
}

/**
 * 목표 온도(℃)를 차량 범위로 맞춘다. [step]이 있으면 `min`에서 시작하는 눈금에 맞춘다.
 * 범위가 잘못됐거나(min > max) 값이 숫자가 아니면 null.
 */
internal fun clampTemperature(value: Float, min: Float, max: Float, step: Float? = null): Float? {
    if (value.isNaN() || min.isNaN() || max.isNaN() || min > max) return null
    val clamped = value.coerceIn(min, max)
    if (step == null || step.isNaN() || step <= 0f) return clamped
    val snapped = min + ((clamped - min) / step).roundToInt() * step
    return snapped.coerceIn(min, max)
}

/** 설정한 온도와 다시 읽은 온도가 같은 값인지. 눈금의 절반(눈금을 모르면 0.5℃)까지 허용한다. */
internal fun sameTemperature(a: Float, b: Float, step: Float?): Boolean {
    val unit = if (step == null || step.isNaN() || step <= 0f) 1f else step
    return abs(a - b) <= unit / 2f + 0.01f
}

internal const val WAKE_TEMPERATURE_DELTA_C = -2f
internal const val WAKE_FAN_LEVEL_DELTA = 2

/** 기상 프리셋 온도: 수면 프리셋 대비 −2℃. 차량 범위 클램프는 [clampTemperature]가 한다. */
internal fun wakeTemperature(napTemperatureC: Float): Float = napTemperatureC + WAKE_TEMPERATURE_DELTA_C

/** 기상 프리셋 풍량: 수면 프리셋 대비 +2단계, 1..5로 제한. */
internal fun wakeFanLevel(napFanLevel: Int): Int =
    (napFanLevel.coerceIn(FAN_LEVEL_MIN, FAN_LEVEL_MAX) + WAKE_FAN_LEVEL_DELTA).coerceIn(FAN_LEVEL_MIN, FAN_LEVEL_MAX)

/**
 * 도어 위치 값 → 열림 여부. 닫힘 위치(capability의 min, 보통 0)보다 크면 열림.
 * 값을 읽지 못했으면 null.
 */
internal fun isDoorOpen(position: Int?, closedPosition: Int): Boolean? = position?.let { it > closedPosition }

/**
 * 도어별 열림 여부 → 하나라도 열렸는지.
 * 감시하는 도어가 없으면 null. 하나라도 열렸으면 true. 열린 도어는 없지만 읽지 못한 도어가 있으면 null.
 */
internal fun anyDoorOpen(doors: Collection<Boolean?>): Boolean? = when {
    doors.isEmpty() -> null
    doors.any { it == true } -> true
    doors.any { it == null } -> null
    else -> false
}

/** 주행 가능 거리(미터) → km 정수(버림). 읽지 못했거나 음수면 null. */
internal fun rangeKmFromMeters(meters: Float?): Int? {
    if (meters == null || meters.isNaN() || meters < 0f) return null
    return (meters / 1000f).toInt()
}
