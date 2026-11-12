package com.chargenap.app.session

import com.chargenap.app.vehicle.CabinApplyResult

enum class SessionMode { NAP, BREATHING }

enum class WakeReason { TIMER, CHARGE_NEARLY_DONE, CHARGE_COMPLETE, CHARGING_INTERRUPTED, USER }

enum class AbortReason { GEAR_LEFT_PARK, DOOR_OPENED }

enum class CheckResult { PASS, FAIL, SKIPPED, NEEDS_USER_CONFIRM }

enum class ExtendDenyReason { CHARGE_ENDS_SOON, MAX_TOTAL, NOT_NAPPING }

/**
 * 시작 조건 점검 결과. [SafetyGuard.evaluateStart]가 만든다.
 *
 * - [gear] 또는 [charging]이 FAIL이면 세션을 시작할 수 없다([blocksStart]).
 * - [parkingBrake] 또는 [doors]가 FAIL이면 풀릴 때까지 `SafetyCheck`에서 기다린다([mustWait]).
 * - [doors]가 NEEDS_USER_CONFIRM이면 도어 값을 읽지 못하는 것이므로 사용자 확인이 필요하다.
 */
data class SafetyReport(
    val gear: CheckResult,
    val parkingBrake: CheckResult,
    val charging: CheckResult,
    val doors: CheckResult,
) {
    /** 기어 P 미확인 또는 충전기 미연결. 시작 요청을 받아도 `Idle`로 돌아간다. */
    val blocksStart: Boolean get() = gear == CheckResult.FAIL || charging == CheckResult.FAIL

    /** 주차 브레이크 미체결 또는 도어 열림. 사용자가 조치하면 진행한다. */
    val mustWait: Boolean get() = parkingBrake == CheckResult.FAIL || doors == CheckResult.FAIL
}

sealed interface Recommendation {
    /** 낮잠 제안. [minutes]는 10, 15, 20, 25 중 하나. */
    data class Nap(val minutes: Int, val remainingChargeMinutes: Int) : Recommendation

    /** 남은 충전 시간이 짧아 낮잠 대신 호흡 모드를 제안. [minutes]는 항상 5. */
    data class Breathing(val minutes: Int, val remainingChargeMinutes: Int) : Recommendation

    /** 남은 충전 시간을 추정할 수 없다. 사용자가 [options](15, 20) 중에서 고르고 타이머로만 동작한다. */
    data class ChooseDuration(val options: List<Int>) : Recommendation

    /** 남은 충전 시간이 10분 미만이라 제안하지 않는다. */
    data object None : Recommendation
}

/**
 * 끝난 세션의 요약. 브리핑 화면과 세션 기록에 쓴다.
 *
 * @param startedAtMillis 수면 시작 시각(세션 시계)
 * @param endedAtMillis 수면이 끝난(기상이 시작된) 시각
 * @param plannedMinutes 타이머 길이. "5분 더"가 허용되면 그만큼 늘어난 값
 * @param sleptMinutes 실제 수면 시간. 분 미만은 버린다
 * @param failures 수면·기상 프리셋 적용과 복구에서 나온 [CabinApplyResult.errors]. "seat: …", "climate: …" 형식
 */
data class SessionSummary(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val mode: SessionMode,
    val plannedMinutes: Int,
    val sleptMinutes: Int,
    val batteryStartPercent: Int?,
    val batteryEndPercent: Int?,
    val rangeKm: Int?,
    val wakeReason: WakeReason,
    val seatMoved: Boolean,
    val failures: List<String>,
)

sealed interface NapSessionState {
    data object Idle : NapSessionState

    data class Offer(val recommendation: Recommendation) : NapSessionState

    /**
     * 확인을 받은 뒤 도어가 열리면 뒷좌석 답과 "문 닫았어요" 확인은 무효가 되고, 도어가 닫히면 다시 묻는다.
     *
     * @param rearSeatQuestionPending true면 "뒷좌석에 사람이나 짐이 있나요?"의 답을 기다리는 중.
     *   도어 열림·주차 브레이크 대기([SafetyReport.mustWait]) 중에는 false이고 대기가 풀리면 true가 된다
     * @param doorConfirmPending true면 도어 값을 읽지 못해 사용자의 "문 닫았어요" 확인을 기다리는 중
     */
    data class SafetyCheck(
        val minutes: Int,
        val mode: SessionMode,
        val report: SafetyReport,
        val rearSeatQuestionPending: Boolean,
        val doorConfirmPending: Boolean,
    ) : NapSessionState

    /** @param seatConsent true면 이번 세션에서 시트를 눕힌다 */
    data class Preparing(val minutes: Int, val mode: SessionMode, val seatConsent: Boolean) : NapSessionState

    /**
     * @param wakeAtMillis 기상 예정 시각(세션 시계). 추정치가 바뀌거나 연장하면 바뀐다
     * @param timerOnly true면 충전 완료 시각을 추정하지 못해 타이머로만 깨운다
     * @param cabin 수면 프리셋 적용 결과. 일부 실패했어도 세션은 진행한다
     */
    data class Napping(
        val startedAtMillis: Long,
        val wakeAtMillis: Long,
        val plannedMinutes: Int,
        val mode: SessionMode,
        val timerOnly: Boolean,
        val cabin: CabinApplyResult,
    ) : NapSessionState

    data class Waking(val reason: WakeReason) : NapSessionState

    data class Briefing(val summary: SessionSummary) : NapSessionState

    /**
     * @param restoring true면 아직 복구가 끝나지 않았다. [AbortReason.DOOR_OPENED]에서는 도어가 닫히기를
     *   (또는 사용자 확인을) 기다리는 동안에도 true다
     * @param restore 복구 결과. 복구가 끝나기 전에는 null. 실패 항목은 [CabinApplyResult.errors]에 있다
     */
    data class Aborted(val reason: AbortReason, val restoring: Boolean, val restore: CabinApplyResult?) : NapSessionState
}

/** 상태로 남기지 않는 일회성 알림. 음성·화면 계층이 안내에 쓴다. */
sealed interface SessionNotice {
    data class StartBlocked(val report: SafetyReport) : SessionNotice
    data class ExtendGranted(val minutes: Int, val wakeAtMillis: Long) : SessionNotice
    data class ExtendDenied(val reason: ExtendDenyReason) : SessionNotice
    data class WakeRescheduled(val wakeAtMillis: Long) : SessionNotice

    /** 수면 시작 2분 뒤. 호흡 안내를 끝내고 백색소음으로 바꾸라는 신호. */
    data object BreathingGuideFinished : SessionNotice
}
