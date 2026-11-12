package com.chargenap.app.vehicle.pleos

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 등받이 모터에 보내는 명령. SDK `MoveControl`과의 대응은 SDK 쪽 포트 구현이 한 곳에서 정한다. */
internal enum class SeatCommand { RECLINE, RAISE, STOP }

/**
 * 등받이 한 좌석의 조작 창구. SDK에는 절대 위치 지정이 없어서(docs/sdk-notes.md 5.2)
 * 방향 명령을 보내고 위치를 지켜보다가 직접 멈춰야 한다.
 */
internal interface BackrestPort {
    /** 구독·조회로 갱신되는 최신 위치. 아직 모르면 null. */
    val position: StateFlow<Int?>

    /** @return 요청이 받아들여졌으면 null, 아니면 오류 사유 */
    suspend fun command(command: SeatCommand): String?

    suspend fun readPosition(): Int?
}

internal data class SeatMoveLimits(
    val tickMs: Long = 50,
    /** 이 시간 동안 위치가 변하지 않으면 멈춘다. */
    val stallTimeoutMs: Long = 2_000,
    /** 이동 한 번의 전체 제한 시간. */
    val totalTimeoutMs: Long = 20_000,
    /** 정지 명령 뒤 이 간격으로 위치를 다시 읽어 멈췄는지 본다. 움직이고 있으면 정지 명령을 다시 보낸다. */
    val stopCheckIntervalMs: Long = 500,
    /** 정지를 확인하려고 정지 명령을 다시 보내는 최대 시간. */
    val stopConfirmTimeoutMs: Long = 10_000,
) {
    companion object {
        /** 눕히기. 이상하면 빨리 멈춘다. */
        val RECLINE = SeatMoveLimits()

        /**
         * 세우기(복구). 운전 자세로 돌아가는 것이 더 중요하므로 느리거나 잠깐 머뭇거리는 시트도 더 기다린다.
         * 방향 불일치·정지 확인 같은 다른 안전장치는 눕히기와 같다.
         */
        val RESTORE = SeatMoveLimits(stallTimeoutMs = 4_000, totalTimeoutMs = 45_000)
    }
}

/**
 * @param moved 위치가 실제로 변했는지
 * @param errors 접두어 없는 사유 목록(게이트웨이가 "seat: "를 붙인다)
 */
internal data class SeatMoveResult(val moved: Boolean, val errors: List<String>) {
    companion object {
        val NOT_MOVED = SeatMoveResult(moved = false, errors = emptyList())
        fun failed(reason: String) = SeatMoveResult(moved = false, errors = listOf(reason))
    }
}

internal object SeatMoveErrors {
    const val WRONG_DIRECTION = "방향 불일치"
    const val NO_RESPONSE = "응답 없음"
    const val TIMED_OUT = "시간 초과"
    const val STOP_FAILED = "정지 명령 실패"
    const val STOP_UNCONFIRMED = "정지 확인 실패"
    const val MOVE_REJECTED = "이동 명령 실패"
}

/**
 * [plan]대로 등받이를 움직이고 목표에 닿으면 멈춘다.
 *
 * 안전장치
 * 1. 위치가 목표와 반대 방향으로 변하면 즉시 정지("방향 불일치") — 방향 가정이 틀린 차량 대비
 * 2. [SeatMoveLimits.stallTimeoutMs] 동안 위치 변화가 없으면 정지("응답 없음")
 * 3. [SeatMoveLimits.totalTimeoutMs]가 지나면 정지("시간 초과")
 * 4. [abortReason]이 사유를 돌려주면 정지(도어 열림 등)
 * 5. 코루틴이 취소되면 정지를 확인한 뒤 취소를 전파
 *
 * 이동 명령을 한 번이라도 보냈으면 어떤 경로로 끝나든(도착, 오류, 취소) `finally`에서 [stopUntilSettled]로
 * 정지 명령을 보내고 위치가 멈췄는지 확인한다. 끝내 확인하지 못하면 "정지 확인 실패"를 결과 오류에 넣는다.
 */
internal suspend fun runSeatMove(
    port: BackrestPort,
    plan: SeatMovePlan,
    limits: SeatMoveLimits = SeatMoveLimits(),
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    abortReason: () -> String? = { null },
): SeatMoveResult {
    val errors = mutableListOf<String>()
    var last = plan.start
    var stop: StopOutcome? = null
    var commandAttempted = false
    try {
        commandAttempted = true
        val rejected = port.command(
            if (plan.direction == SeatDirection.RECLINE) SeatCommand.RECLINE else SeatCommand.RAISE,
        )
        if (rejected != null) {
            errors += "${SeatMoveErrors.MOVE_REJECTED}: $rejected"
        } else {
            val startedAt = nowMs()
            var lastChangeAt = startedAt
            while (true) {
                val abort = abortReason()
                if (abort != null) {
                    errors += abort
                    break
                }
                val now = nowMs()
                val position = port.position.value
                if (position != null && position != last) {
                    last = position
                    lastChangeAt = now
                    if (plan.movedAway(position)) {
                        errors += SeatMoveErrors.WRONG_DIRECTION
                        break
                    }
                }
                if (plan.reached(last)) break
                if (now - lastChangeAt >= limits.stallTimeoutMs) {
                    errors += SeatMoveErrors.NO_RESPONSE
                    break
                }
                if (now - startedAt >= limits.totalTimeoutMs) {
                    errors += SeatMoveErrors.TIMED_OUT
                    break
                }
                delay(limits.tickMs)
            }
        }
    } finally {
        if (commandAttempted) {
            // 취소된 코루틴에서도 정지는 끝까지 확인한다
            stop = withContext(NonCancellable) { stopUntilSettled(port, limits, nowMs, plan.tolerance) }
        }
    }
    stop?.let { outcome ->
        if (!outcome.confirmed) {
            errors += SeatMoveErrors.STOP_UNCONFIRMED
            outcome.lastRejection?.let { errors += "${SeatMoveErrors.STOP_FAILED}: $it" }
        }
    }
    // 정지 뒤 관성으로 조금 더 움직일 수 있어 정지 확인에서 마지막으로 읽은 위치를 쓴다
    val settled = stop?.position ?: port.readPosition() ?: port.position.value ?: last
    return SeatMoveResult(moved = last != plan.start || settled != plan.start, errors = errors)
}

/**
 * @param confirmed 정지 명령이 받아들여졌고 그 뒤 위치가 멈춘 것을 확인했는지
 * @param position 마지막으로 읽은 위치
 * @param lastRejection 마지막 정지 명령이 거절됐으면 그 사유
 */
private class StopOutcome(val confirmed: Boolean, val position: Int?, val lastRejection: String?)

/**
 * 정지 명령을 보내고 [SeatMoveLimits.stopCheckIntervalMs]마다 위치를 다시 읽는다.
 * 정지 명령이 받아들여졌고 그 사이 위치 변화가 [tolerance] 이하면 정지를 확인한 것이다.
 * 아니면(위치가 계속 바뀌거나 명령이 거절됨) 정지 명령을 다시 보낸다. [SeatMoveLimits.stopConfirmTimeoutMs]가
 * 지나도 확인하지 못하면 그만둔다.
 *
 * 호출자는 `NonCancellable` 안에서 부른다(취소된 이동도 정지를 확인해야 한다).
 */
private suspend fun stopUntilSettled(
    port: BackrestPort,
    limits: SeatMoveLimits,
    nowMs: () -> Long,
    tolerance: Int,
): StopOutcome {
    val deadline = nowMs() + limits.stopConfirmTimeoutMs
    var rejection = port.command(SeatCommand.STOP)
    var previous = currentPosition(port)
    while (true) {
        delay(limits.stopCheckIntervalMs)
        val position = currentPosition(port)
        val still = position != null && previous != null && abs(position.toLong() - previous) <= tolerance
        if (rejection == null && still) return StopOutcome(confirmed = true, position = position, lastRejection = null)
        if (nowMs() >= deadline) return StopOutcome(confirmed = false, position = position ?: previous, lastRejection = rejection)
        previous = position ?: previous
        rejection = port.command(SeatCommand.STOP)
    }
}

/** 직접 조회한 위치. 조회가 실패하면 구독으로 받은 최신 값. */
private suspend fun currentPosition(port: BackrestPort): Int? = port.readPosition() ?: port.position.value
