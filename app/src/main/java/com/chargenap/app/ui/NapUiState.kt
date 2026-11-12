package com.chargenap.app.ui

import com.chargenap.app.data.AppSettings
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.WeeklyReport
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.ExtendDenyReason
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.VehicleCapabilities

/** 시작할 수 없는 이유. 홈 화면의 한 줄 안내와 `StartBlocked` 알림에 쓴다. */
enum class StartBlockReason {
    GEAR_NOT_PARK,
    GEAR_UNKNOWN,
    CHARGER_NOT_CONNECTED,
    CHARGE_COMPLETE,
    CHARGE_UNKNOWN,
}

/** 화면 하단에 잠깐 보여 주는 안내. 엔진의 `SessionNotice` 중 화면에 보일 것만 옮긴 것이다. */
sealed interface UiNotice {
    data class StartBlocked(val reason: StartBlockReason) : UiNotice
    data class ExtendGranted(val minutes: Int) : UiNotice
    data class ExtendDenied(val reason: ExtendDenyReason) : UiNotice
}

/**
 * 화면이 그리는 데 필요한 값 전부.
 *
 * @param session 세션 엔진의 상태 그대로
 * @param remainingChargeMinutes 목표 잔량까지 남은 충전 시간(엔진 추정). 모르면 null
 * @param startBlockers 지금 시작을 요청하면 받게 될 점검 결과
 * @param gearInPark 기어 P 여부. 읽지 못하면 null
 * @param anyDoorOpen 도어 열림 여부. 읽지 못하면 null
 * @param recentRecords 최근 세션(새 것이 먼저)
 * @param todayEpochDay 세션 시계 기준 오늘(`LocalDate.toEpochDay`). 리포트의 요일 표시에 쓴다
 * @param remainingSeconds 수면 중일 때 기상까지 남은 초. 그 밖에는 null
 * @param demoControlsAvailable true면 DEMO 조작 패널을 보여 준다
 * @param fallbackReason 실제 차량 연결에 실패해 데모로 대체됐을 때의 사유. 아니면 null
 * @param voiceAvailable true면 홈에 음성 안내 힌트를 보여 준다
 * @param breathingGuide 수면 중 호흡 안내 구간이면 true. 수면이 시작되면 true가 되고, 엔진의
 *   `SessionNotice.BreathingGuideFinished`를 받으면 false가 된다(그 뒤로는 백색소음 구간). 수면 화면 표시에만 쓴다
 */
data class NapUiState(
    val session: NapSessionState = NapSessionState.Idle,
    val chargingState: ChargingState = ChargingState.UNKNOWN,
    val batteryPercent: Int? = null,
    val remainingChargeMinutes: Int? = null,
    val startBlockers: SafetyReport = SafetyReport(
        gear = CheckResult.FAIL,
        parkingBrake = CheckResult.SKIPPED,
        charging = CheckResult.FAIL,
        doors = CheckResult.NEEDS_USER_CONFIRM,
    ),
    val gearInPark: Boolean? = null,
    val anyDoorOpen: Boolean? = null,
    val capabilities: VehicleCapabilities = VehicleCapabilities.NONE,
    val settings: AppSettings = AppSettings(),
    val weeklyReport: WeeklyReport = WeeklyReport.EMPTY,
    val recentRecords: List<SessionRecord> = emptyList(),
    val todayEpochDay: Long = 0,
    val remainingSeconds: Int? = null,
    val notice: UiNotice? = null,
    val demoControlsAvailable: Boolean = false,
    val fallbackReason: String? = null,
    val voiceAvailable: Boolean = false,
    val breathingGuide: Boolean = false,
) {
    /** 안전 확인부터 중단 안내까지. 이 동안에는 설정·리포트로 이동할 수 없다. */
    val sessionActive: Boolean
        get() = session != NapSessionState.Idle && session !is NapSessionState.Offer

    /** 시작할 수 없으면 그 이유, 시작할 수 있으면 null. */
    val startBlockReason: StartBlockReason?
        get() = startBlockReason(startBlockers, chargingState, gearInPark)
}

/**
 * 점검 결과를 사용자에게 보여 줄 이유 하나로 줄인다. 기어가 P가 아닌 것(주행 가능 상태)을 가장 먼저 알린다.
 */
fun startBlockReason(report: SafetyReport, chargingState: ChargingState, gearInPark: Boolean?): StartBlockReason? {
    val gearFailed = report.gear == CheckResult.FAIL
    val chargingFailed = report.charging == CheckResult.FAIL
    return when {
        gearFailed && gearInPark == false -> StartBlockReason.GEAR_NOT_PARK
        chargingFailed -> when (chargingState) {
            ChargingState.COMPLETE -> StartBlockReason.CHARGE_COMPLETE
            ChargingState.UNKNOWN -> StartBlockReason.CHARGE_UNKNOWN
            else -> StartBlockReason.CHARGER_NOT_CONNECTED
        }
        gearFailed -> StartBlockReason.GEAR_UNKNOWN
        else -> null
    }
}

/** 화면이 부르는 이벤트. [NapViewModel]이 구현한다. 미리보기와 스냅샷 테스트는 [NoopNapActions]를 쓴다. */
interface NapActions {
    // 세션
    fun requestStart(minutes: Int?)
    fun declineOffer()
    fun cancel()
    fun answerRearSeat(clear: Boolean)
    fun confirmDoorsClosed()
    fun requestExtend()
    fun requestWake()
    fun acknowledgeBriefing()
    fun acknowledgeAbort()
    fun dismissNotice()

    // 설정
    fun setReclineLevel(level: Int)
    fun setTemperatureC(temperatureC: Float)
    fun setFanLevel(level: Int)
    fun setTargetPercent(percent: Int)
    fun setSeatControlEnabled(enabled: Boolean)
    fun setVoiceEnabled(enabled: Boolean)
    fun setDemoMode(enabled: Boolean)

    // 데모 조작
    fun demoStartCharging()
    fun demoInterruptCharging()
    fun demoCompleteCharging()
    fun demoSetDoorOpen(open: Boolean)
    fun demoSetGearInPark(inPark: Boolean)
}

object NoopNapActions : NapActions {
    override fun requestStart(minutes: Int?) = Unit
    override fun declineOffer() = Unit
    override fun cancel() = Unit
    override fun answerRearSeat(clear: Boolean) = Unit
    override fun confirmDoorsClosed() = Unit
    override fun requestExtend() = Unit
    override fun requestWake() = Unit
    override fun acknowledgeBriefing() = Unit
    override fun acknowledgeAbort() = Unit
    override fun dismissNotice() = Unit
    override fun setReclineLevel(level: Int) = Unit
    override fun setTemperatureC(temperatureC: Float) = Unit
    override fun setFanLevel(level: Int) = Unit
    override fun setTargetPercent(percent: Int) = Unit
    override fun setSeatControlEnabled(enabled: Boolean) = Unit
    override fun setVoiceEnabled(enabled: Boolean) = Unit
    override fun setDemoMode(enabled: Boolean) = Unit
    override fun demoStartCharging() = Unit
    override fun demoInterruptCharging() = Unit
    override fun demoCompleteCharging() = Unit
    override fun demoSetDoorOpen(open: Boolean) = Unit
    override fun demoSetGearInPark(inPark: Boolean) = Unit
}
