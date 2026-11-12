package com.chargenap.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargenap.app.data.AppSettings
import com.chargenap.app.data.SessionHistoryRepository
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.SettingsRepository
import com.chargenap.app.data.weeklyReport
import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionNotice
import com.chargenap.app.session.SessionTimings
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.DemoVehicleControls
import com.chargenap.app.vehicle.ParkState
import com.chargenap.app.vehicle.VehicleCapabilities
import com.chargenap.app.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 세션 엔진·차량 값·저장소를 화면 상태 하나([uiState])로 묶고, 화면 이벤트를 엔진과 저장소로 넘긴다.
 *
 * 세션 로직은 여기 없다. 이벤트 함수는 엔진에 위임만 하고 결과는 엔진 상태로 돌아온다.
 * 엔진과 게이트웨이의 `start()`/`stop()`은 부르지 않는다(`AppContainer` 몫).
 * 끝난 세션을 기록에 저장하는 것도 `AppContainer` 몫이다. 여기서는 [historyRepository]를 읽기만 한다.
 *
 * @param demoControls 데모 모드일 때만 non-null. null이면 데모 조작 함수는 아무 일도 하지 않는다
 * @param fallbackReason 실제 차량 연결 실패로 데모로 대체됐을 때의 사유
 * @param voiceAvailable true면 홈에 음성 안내 힌트를 보여 준다
 * @param zone 리포트의 날짜 경계를 정하는 시간대
 * @param coroutineContext 테스트에서 디스패처(와 Job)를 바꿀 때 쓴다. 기본은 `viewModelScope` 그대로
 */
class NapViewModel(
    private val engine: NapSessionEngine,
    private val gateway: VehicleGateway,
    private val demoControls: DemoVehicleControls?,
    private val settingsRepository: SettingsRepository,
    historyRepository: SessionHistoryRepository,
    private val fallbackReason: String? = null,
    voiceAvailable: StateFlow<Boolean> = MutableStateFlow(false),
    private val zone: ZoneId = ZoneId.systemDefault(),
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : ViewModel(), NapActions {

    private val scope = CoroutineScope(viewModelScope.coroutineContext + coroutineContext)

    /** 수면 중 200ms마다 값이 바뀌어 남은 시간을 다시 계산하게 한다. */
    private val tick = MutableStateFlow(0L)
    private val notice = MutableStateFlow<UiNotice?>(null)
    private var noticeJob: Job? = null

    /** 호흡 안내가 끝났다는 알림(`BreathingGuideFinished`)을 받은 수면 세션의 시작 시각. */
    private val breathingFinishedFor = MutableStateFlow<Long?>(null)

    private val vehicleValues: Flow<VehicleValues> = combine(
        gateway.chargingState,
        gateway.batteryPercent,
        gateway.parkState,
        gateway.anyDoorOpen,
        gateway.capabilities,
        ::VehicleValues,
    )

    private val engineValues: Flow<EngineValues> = combine(
        engine.state,
        engine.estimatedRemainingChargeMinutes,
        engine.startBlockers,
        ::EngineValues,
    )

    // 저장소는 파일을 읽은 뒤에야 첫 값을 준다. 그동안 차량 값 표시가 막히지 않게 기본값으로 시작한다.
    private val storedValues: Flow<StoredValues> = combine(
        settingsRepository.settings.onStart { emit(AppSettings()) },
        historyRepository.records.onStart { emit(emptyList()) },
    ) { settings, records ->
        StoredValues(settings, records, records.sortedByDescending { it.startedAtMillis }.take(RECENT_LIMIT))
    }

    private val transientValues: Flow<TransientValues> =
        combine(notice, voiceAvailable, breathingFinishedFor, ::TransientValues)

    val uiState: StateFlow<NapUiState> = combine(
        vehicleValues,
        engineValues,
        storedValues,
        transientValues,
        tick,
    ) { vehicle, session, stored, transient, _ ->
        build(vehicle, session, stored, transient)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        build(
            VehicleValues(
                gateway.chargingState.value,
                gateway.batteryPercent.value,
                gateway.parkState.value,
                gateway.anyDoorOpen.value,
                gateway.capabilities.value,
            ),
            EngineValues(engine.state.value, engine.estimatedRemainingChargeMinutes.value, engine.startBlockers.value),
            StoredValues(AppSettings(), emptyList(), emptyList()),
            TransientValues(null, voiceAvailable.value, null),
        ),
    )

    init {
        scope.launch {
            engine.state
                .map { it is NapSessionState.Napping }
                .distinctUntilChanged()
                .collectLatest { napping ->
                    while (napping) {
                        tick.value += 1
                        delay(TICK_MILLIS)
                    }
                }
        }
        scope.launch {
            engine.notices.collect { sessionNotice ->
                if (sessionNotice == SessionNotice.BreathingGuideFinished) {
                    (engine.state.value as? NapSessionState.Napping)?.let { breathingFinishedFor.value = it.startedAtMillis }
                }
                toUiNotice(sessionNotice)?.let(::show)
            }
        }
    }

    private fun build(
        vehicle: VehicleValues,
        session: EngineValues,
        stored: StoredValues,
        transient: TransientValues,
    ): NapUiState {
        // 데모 모드는 시계가 빨리 흐르므로 반드시 엔진의 시계로 계산한다.
        val now = engine.clock.nowMillis()
        val napping = session.state as? NapSessionState.Napping
        return NapUiState(
            session = session.state,
            chargingState = vehicle.charging,
            batteryPercent = vehicle.batteryPercent,
            remainingChargeMinutes = session.remainingChargeMinutes,
            startBlockers = session.startBlockers,
            gearInPark = vehicle.park.gearInPark,
            anyDoorOpen = vehicle.anyDoorOpen,
            capabilities = vehicle.capabilities,
            settings = stored.settings,
            weeklyReport = weeklyReport(stored.records, now, zone),
            recentRecords = stored.recent,
            todayEpochDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay(),
            remainingSeconds = napping?.let { remainingSeconds(it.wakeAtMillis, now) },
            notice = transient.notice,
            demoControlsAvailable = demoControls != null,
            fallbackReason = fallbackReason,
            voiceAvailable = transient.voiceAvailable,
            breathingGuide = napping != null && breathingGuideActive(napping, transient.breathingFinishedFor, now),
        )
    }

    /**
     * 수면 시작부터 엔진의 `BreathingGuideFinished` 알림까지가 호흡 안내 구간이다. 알림을 놓친 경우(수면 중에
     * ViewModel이 새로 만들어진 경우)에도 끝없이 이어지지 않도록 엔진 기본 구간 길이가 지나면 끝난 것으로 본다.
     */
    private fun breathingGuideActive(napping: NapSessionState.Napping, finishedFor: Long?, now: Long): Boolean =
        finishedFor != napping.startedAtMillis && now - napping.startedAtMillis < BREATHING_GUIDE_FALLBACK_MILLIS

    private fun toUiNotice(sessionNotice: SessionNotice): UiNotice? = when (sessionNotice) {
        is SessionNotice.StartBlocked -> UiNotice.StartBlocked(
            startBlockReason(
                sessionNotice.report,
                gateway.chargingState.value,
                gateway.parkState.value.gearInPark,
            ) ?: StartBlockReason.CHARGER_NOT_CONNECTED,
        )
        is SessionNotice.ExtendGranted -> UiNotice.ExtendGranted(sessionNotice.minutes)
        is SessionNotice.ExtendDenied -> UiNotice.ExtendDenied(sessionNotice.reason)
        // 수면 중에 오는 알림이라 화면에는 띄우지 않는다. 음성 계층이 쓴다.
        is SessionNotice.WakeRescheduled, SessionNotice.BreathingGuideFinished -> null
    }

    private fun show(next: UiNotice) {
        noticeJob?.cancel()
        notice.value = next
        noticeJob = scope.launch {
            delay(NOTICE_MILLIS)
            notice.value = null
        }
    }

    // ---------------------------------------------------------------- 세션 이벤트 (엔진 위임)

    override fun requestStart(minutes: Int?) {
        engine.requestStart(minutes)
    }

    override fun declineOffer() {
        engine.declineOffer()
    }

    override fun cancel() {
        engine.cancel()
    }

    /** [clear]가 true("없어요")일 때만 시트를 움직인다. */
    override fun answerRearSeat(clear: Boolean) {
        engine.answerRearSeat(clear)
    }

    override fun confirmDoorsClosed() {
        engine.confirmDoorsClosed()
    }

    override fun requestExtend() {
        engine.requestExtend(EXTEND_MINUTES)
    }

    override fun requestWake() {
        engine.requestWake()
    }

    override fun acknowledgeBriefing() {
        engine.acknowledgeBriefing()
    }

    override fun acknowledgeAbort() {
        engine.acknowledgeAbort()
    }

    override fun dismissNotice() {
        noticeJob?.cancel()
        notice.value = null
    }

    // ---------------------------------------------------------------- 설정

    override fun setReclineLevel(level: Int) = updateSettings { it.copy(reclineLevel = level) }

    override fun setTemperatureC(temperatureC: Float) = updateSettings { it.copy(temperatureC = temperatureC) }

    override fun setFanLevel(level: Int) = updateSettings { it.copy(fanLevel = level) }

    override fun setTargetPercent(percent: Int) = updateSettings { it.copy(targetPercent = percent) }

    override fun setSeatControlEnabled(enabled: Boolean) = updateSettings { it.copy(seatControlEnabled = enabled) }

    override fun setVoiceEnabled(enabled: Boolean) = updateSettings { it.copy(voiceEnabled = enabled) }

    /** 세션이 진행 중이면 바꾸지 않는다. 차량 그래프를 바꾸는 일이라 세션 도중에는 위험하다. */
    override fun setDemoMode(enabled: Boolean) {
        if (uiState.value.sessionActive) return
        updateSettings { it.copy(demoMode = enabled) }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepository.update(transform) }
    }

    // ---------------------------------------------------------------- 데모 조작

    override fun demoStartCharging() {
        demoControls?.startCharging()
    }

    override fun demoInterruptCharging() {
        demoControls?.interruptCharging()
    }

    override fun demoCompleteCharging() {
        demoControls?.completeCharging()
    }

    override fun demoSetDoorOpen(open: Boolean) {
        demoControls?.setDoorOpen(open)
    }

    override fun demoSetGearInPark(inPark: Boolean) {
        demoControls?.setGearInPark(inPark)
    }

    private data class VehicleValues(
        val charging: ChargingState,
        val batteryPercent: Int?,
        val park: ParkState,
        val anyDoorOpen: Boolean?,
        val capabilities: VehicleCapabilities,
    )

    private data class EngineValues(
        val state: NapSessionState,
        val remainingChargeMinutes: Int?,
        val startBlockers: SafetyReport,
    )

    private data class StoredValues(
        val settings: AppSettings,
        val records: List<SessionRecord>,
        val recent: List<SessionRecord>,
    )

    private data class TransientValues(
        val notice: UiNotice?,
        val voiceAvailable: Boolean,
        val breathingFinishedFor: Long?,
    )

    companion object {
        const val TICK_MILLIS = 200L

        /** 호흡 안내 알림을 받지 못했을 때 쓰는 구간 길이. 엔진 기본값과 같다. */
        private val BREATHING_GUIDE_FALLBACK_MILLIS = SessionTimings().breathingGuideMillis
        const val NOTICE_MILLIS = 4_000L
        const val EXTEND_MINUTES = 5
        const val RECENT_LIMIT = 30

        /** 기상까지 남은 초. 올림한다(59.2초 남았으면 60). 지났으면 0. */
        fun remainingSeconds(wakeAtMillis: Long, nowMillis: Long): Int {
            val remaining = wakeAtMillis - nowMillis
            return if (remaining <= 0) 0 else ((remaining + 999) / 1000).toInt()
        }
    }
}
