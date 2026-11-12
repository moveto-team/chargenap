package com.chargenap.app.ui

import com.chargenap.app.data.InMemoryPreferencesStore
import com.chargenap.app.data.SessionHistoryRepository
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.SettingsRepository
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.Clock
import com.chargenap.app.session.ExtendDenyReason
import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SessionConfig
import com.chargenap.app.session.WakeReason
import com.chargenap.app.ui.aborted.AbortedPhase
import com.chargenap.app.ui.aborted.abortedPhase
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 가상 시간 + [FakeVehicleGateway](timeScale = 1.0) + 실제 [NapSessionEngine]으로 ViewModel을 검증한다.
 *
 * ViewModel은 엔진과 게이트웨이를 시작하지 않으므로(AppContainer 몫) 여기서는 테스트가 시작한다.
 * 끝난 세션을 기록에 넣는 것도 AppContainer 몫이라 같은 배선을 테스트 안에서 흉내 낸다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NapViewModelTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)
    private val baseMillis = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val scenario = FakeScenario(startPercent = 50, percentPerMinute = 1.0, autoStartChargingAfterMillis = null)

    private class Harness(
        val fake: FakeVehicleGateway,
        val engine: NapSessionEngine,
        val settings: SettingsRepository,
        val history: SessionHistoryRepository,
        val voiceAvailable: MutableStateFlow<Boolean>,
        val vm: NapViewModel,
    ) {
        val ui: NapUiState get() = vm.uiState.value
        inline fun <reified T : NapSessionState> session(): T {
            val current = ui.session
            assertTrue("기대한 상태 ${T::class.simpleName}, 실제 $current", current is T)
            return current as T
        }
    }

    @Before
    fun setUp() {
        // viewModelScope가 Dispatchers.Main을 쓴다. runTest는 Main에 설정된 테스트 디스패처의 스케줄러를 같이 쓴다.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.harness(demo: Boolean = true, fallbackReason: String? = null): Harness {
        val fake = FakeVehicleGateway(scenario, timeScale = 1.0, scope = backgroundScope)
        val engine = NapSessionEngine(
            gateway = fake,
            clock = Clock { baseMillis + testScheduler.currentTime },
            scope = backgroundScope,
            config = MutableStateFlow(SessionConfig()),
            tickMillis = 1_000,
        )
        val settings = SettingsRepository(InMemoryPreferencesStore())
        val history = SessionHistoryRepository(InMemoryPreferencesStore())
        val voiceAvailable = MutableStateFlow(false)
        backgroundScope.launch { engine.completedSessions.collect { history.add(SessionRecord.from(it)) } }
        fake.start()
        engine.start()
        val vm = NapViewModel(
            engine = engine,
            gateway = fake,
            demoControls = if (demo) fake else null,
            settingsRepository = settings,
            historyRepository = history,
            fallbackReason = fallbackReason,
            voiceAvailable = voiceAvailable,
            zone = zone,
            coroutineContext = backgroundScope.coroutineContext,
        )
        runCurrent()
        return Harness(fake, engine, settings, history, voiceAvailable, vm)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    /** 충전 시작 → 제안 → 수락 → 뒷좌석 "없어요" → 수면까지. */
    private fun TestScope.startNap(h: Harness, sdkRemainingMinutes: Int, minutes: Int): NapSessionState.Napping {
        h.fake.setRemainingChargeMinutes(sdkRemainingMinutes)
        h.vm.demoStartCharging()
        runCurrent()
        h.vm.requestStart(minutes)
        runCurrent()
        h.vm.answerRearSeat(true)
        runCurrent()
        advance(1_000)
        return h.session()
    }

    // ---------------------------------------------------------------- 세션 흐름

    @Test
    fun initialState_showsVehicleValuesAndBlockReason() = runTest {
        val h = harness()

        assertEquals(NapSessionState.Idle, h.ui.session)
        assertEquals(ChargingState.NOT_CONNECTED, h.ui.chargingState)
        assertEquals(50, h.ui.batteryPercent)
        assertEquals(true, h.ui.gearInPark)
        assertEquals(false, h.ui.anyDoorOpen)
        assertTrue(h.ui.capabilities.isDemo)
        assertEquals(StartBlockReason.CHARGER_NOT_CONNECTED, h.ui.startBlockReason)
        assertFalse(h.ui.sessionActive)
        assertNull(h.ui.remainingSeconds)
        assertEquals(today.toEpochDay(), h.ui.todayEpochDay)
    }

    @Test
    fun acceptOffer_napsAndBriefs_uiStateFollowsEngine() = runTest {
        val h = harness()

        // 충전 시작 → 제안
        h.fake.setRemainingChargeMinutes(27)
        h.vm.demoStartCharging()
        runCurrent()
        val offer = h.session<NapSessionState.Offer>()
        assertEquals(Recommendation.Nap(minutes = 20, remainingChargeMinutes = 27), offer.recommendation)
        assertEquals(ChargingState.CHARGING, h.ui.chargingState)
        assertEquals(27, h.ui.remainingChargeMinutes)
        assertNull(h.ui.startBlockReason)
        assertFalse(h.ui.sessionActive)

        // 수락 → 안전 확인(뒷좌석 질문)
        h.vm.requestStart(20)
        runCurrent()
        val safety = h.session<NapSessionState.SafetyCheck>()
        assertTrue(safety.rearSeatQuestionPending)
        assertEquals(20, safety.minutes)
        assertTrue(h.ui.sessionActive)

        // "없어요" → 준비 → 수면
        h.vm.answerRearSeat(true)
        runCurrent()
        assertTrue(h.session<NapSessionState.Preparing>().seatConsent)
        advance(1_000)
        val napping = h.session<NapSessionState.Napping>()
        assertEquals(60, h.fake.seatReclineLevel.value)
        val atStart = h.ui.remainingSeconds!!
        assertTrue("남은 초 $atStart", atStart in (20 * 60 - 2)..(20 * 60))
        assertEquals(
            NapViewModel.remainingSeconds(napping.wakeAtMillis, baseMillis + testScheduler.currentTime),
            atStart,
        )

        // 시간이 흐르면 남은 시간이 줄어든다(엔진 시계 기준, 200ms 간격 갱신).
        advance(60_000)
        val afterMinute = h.ui.remainingSeconds!!
        assertTrue("1분 뒤 남은 초 $afterMinute", afterMinute in (atStart - 61)..(atStart - 59))
        advance(400)
        assertTrue(h.ui.remainingSeconds!! < afterMinute + 1)

        // 깨우기 → 기상 → 브리핑
        h.vm.requestWake()
        runCurrent()
        assertEquals(WakeReason.USER, h.session<NapSessionState.Waking>().reason)
        assertNull(h.ui.remainingSeconds)
        advance(22_000)
        val summary = h.session<NapSessionState.Briefing>().summary
        assertEquals(WakeReason.USER, summary.wakeReason)
        assertEquals(1, summary.sleptMinutes)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertTrue(h.ui.sessionActive)

        // 확인 → 홈. 기록이 리포트에 들어온다.
        h.vm.acknowledgeBriefing()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.ui.session)
        assertFalse(h.ui.sessionActive)
        assertEquals(1, h.ui.weeklyReport.sessionCount)
        assertEquals(1, h.ui.weeklyReport.totalMinutes)
        assertEquals(1, h.ui.weeklyReport.perDayMinutes.last())
        assertEquals(listOf(SessionRecord.from(summary)), h.ui.recentRecords)
    }

    @Test
    fun timerEnds_wakesWithoutUserAction() = runTest {
        val h = harness()
        startNap(h, sdkRemainingMinutes = 45, minutes = 10)

        advance(10 * 60_000L)

        assertEquals(WakeReason.TIMER, h.session<NapSessionState.Waking>().reason)
        assertNull(h.ui.remainingSeconds)
    }

    @Test
    fun breathingGuide_fromNapStartUntilEngineFinishesIt() = runTest {
        val h = harness()
        assertFalse(h.ui.breathingGuide)

        startNap(h, sdkRemainingMinutes = 45, minutes = 20)
        assertTrue(h.ui.breathingGuide)
        advance(60_000)
        assertTrue(h.ui.breathingGuide)

        // 수면 시작 2분 뒤 엔진이 BreathingGuideFinished를 보내면 백색소음 구간이다.
        advance(61_000)
        assertFalse(h.ui.breathingGuide)
        advance(60_000)
        assertFalse(h.ui.breathingGuide)

        h.vm.requestWake()
        runCurrent()
        assertFalse(h.ui.breathingGuide)
    }

    @Test
    fun breathingGuide_startsAgainForNextNap() = runTest {
        val h = harness()
        startNap(h, sdkRemainingMinutes = 45, minutes = 10)
        advance(3 * 60_000L)
        assertFalse(h.ui.breathingGuide)
        h.vm.requestWake()
        runCurrent()
        advance(22_000)
        h.vm.acknowledgeBriefing()
        runCurrent()

        // 다음 세션은 다시 호흡 안내부터 시작한다.
        h.vm.requestStart(10)
        runCurrent()
        h.vm.answerRearSeat(true)
        runCurrent()
        advance(1_000)
        h.session<NapSessionState.Napping>()
        assertTrue(h.ui.breathingGuide)
    }

    @Test
    fun declineOffer_returnsToIdle() = runTest {
        val h = harness()
        h.fake.setRemainingChargeMinutes(27)
        h.vm.demoStartCharging()
        runCurrent()
        h.session<NapSessionState.Offer>()

        h.vm.declineOffer()
        runCurrent()
        advance(5_000)

        assertEquals(NapSessionState.Idle, h.ui.session)
        // 거절해도 직접 시작할 수는 있다.
        assertNull(h.ui.startBlockReason)
    }

    @Test
    fun cancelDuringSafetyCheck_returnsToIdle() = runTest {
        val h = harness()
        h.fake.setRemainingChargeMinutes(27)
        h.vm.demoStartCharging()
        runCurrent()
        h.vm.requestStart(20)
        runCurrent()
        h.session<NapSessionState.SafetyCheck>()

        h.vm.cancel()
        runCurrent()

        assertEquals(NapSessionState.Idle, h.ui.session)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun doorOpensDuringNap_showsAbortedUntilAcknowledged() = runTest {
        val h = harness()
        startNap(h, sdkRemainingMinutes = 27, minutes = 20)

        h.vm.demoSetDoorOpen(true)
        runCurrent()
        val waiting = h.session<NapSessionState.Aborted>()
        assertEquals(AbortReason.DOOR_OPENED, waiting.reason)
        assertTrue(waiting.restoring)
        assertEquals(true, h.ui.anyDoorOpen)
        assertEquals(AbortedPhase.WAITING_DOOR, abortedPhase(waiting, h.ui.anyDoorOpen, overrideRequested = false))
        assertEquals(60, h.fake.seatReclineLevel.value)

        // [지금 되돌리기]
        h.vm.confirmDoorsClosed()
        advance(2_000)
        val done = h.session<NapSessionState.Aborted>()
        assertFalse(done.restoring)
        assertEquals(AbortedPhase.DONE, abortedPhase(done, h.ui.anyDoorOpen, overrideRequested = true))
        assertEquals(0, h.fake.seatReclineLevel.value)

        h.vm.acknowledgeAbort()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.ui.session)
    }

    // ---------------------------------------------------------------- 알림

    @Test
    fun startBlocked_showsNoticeThenClears() = runTest {
        val h = harness()

        h.vm.requestStart(20)
        runCurrent()

        assertEquals(NapSessionState.Idle, h.ui.session)
        assertEquals(UiNotice.StartBlocked(StartBlockReason.CHARGER_NOT_CONNECTED), h.ui.notice)

        advance(NapViewModel.NOTICE_MILLIS)
        assertNull(h.ui.notice)
    }

    @Test
    fun startBlocked_gearNotPark_reportsGearFirst() = runTest {
        val h = harness()
        h.vm.demoSetGearInPark(false)
        runCurrent()

        h.vm.requestStart(20)
        runCurrent()

        assertEquals(UiNotice.StartBlocked(StartBlockReason.GEAR_NOT_PARK), h.ui.notice)

        h.vm.dismissNotice()
        runCurrent()
        assertNull(h.ui.notice)
    }

    @Test
    fun extend_grantedAndDenied_showNotices() = runTest {
        val h = harness()
        startNap(h, sdkRemainingMinutes = 45, minutes = 20)
        val before = h.ui.remainingSeconds!!

        h.vm.requestExtend()
        runCurrent()
        assertEquals(UiNotice.ExtendGranted(5), h.ui.notice)
        advance(200)
        assertTrue(h.ui.remainingSeconds!! > before + 4 * 60)

        // 20 + 5 + 5 = 30분까지는 되고, 그다음은 총 30분 상한에 걸린다.
        h.vm.requestExtend()
        runCurrent()
        assertEquals(UiNotice.ExtendGranted(5), h.ui.notice)
        h.vm.requestExtend()
        runCurrent()
        assertEquals(UiNotice.ExtendDenied(ExtendDenyReason.MAX_TOTAL), h.ui.notice)
    }

    // ---------------------------------------------------------------- 데모 조작

    @Test
    fun demoControls_reachGateway() = runTest {
        val h = harness()
        assertTrue(h.ui.demoControlsAvailable)

        h.vm.demoStartCharging()
        runCurrent()
        assertEquals(ChargingState.CHARGING, h.fake.chargingState.value)
        assertEquals(ChargingState.CHARGING, h.ui.chargingState)

        h.vm.demoInterruptCharging()
        runCurrent()
        assertEquals(ChargingState.CONNECTED_IDLE, h.fake.chargingState.value)
        assertEquals(ChargingState.CONNECTED_IDLE, h.ui.chargingState)

        h.vm.demoCompleteCharging()
        runCurrent()
        assertEquals(ChargingState.COMPLETE, h.fake.chargingState.value)
        assertEquals(80, h.ui.batteryPercent)
        assertEquals(StartBlockReason.CHARGE_COMPLETE, h.ui.startBlockReason)

        h.vm.demoSetDoorOpen(true)
        runCurrent()
        assertEquals(true, h.fake.anyDoorOpen.value)
        assertEquals(true, h.ui.anyDoorOpen)
        h.vm.demoSetDoorOpen(false)
        runCurrent()
        assertEquals(false, h.ui.anyDoorOpen)

        h.vm.demoSetGearInPark(false)
        runCurrent()
        assertEquals(false, h.fake.parkState.value.gearInPark)
        assertEquals(false, h.ui.gearInPark)
        assertEquals(StartBlockReason.GEAR_NOT_PARK, h.ui.startBlockReason)
        h.vm.demoSetGearInPark(true)
        runCurrent()
        assertEquals(true, h.ui.gearInPark)
    }

    @Test
    fun withoutDemoControls_demoFunctionsDoNothing() = runTest {
        val h = harness(demo = false)
        assertFalse(h.ui.demoControlsAvailable)

        h.vm.demoStartCharging()
        h.vm.demoSetDoorOpen(true)
        h.vm.demoSetGearInPark(false)
        runCurrent()

        assertEquals(ChargingState.NOT_CONNECTED, h.fake.chargingState.value)
        assertEquals(false, h.fake.anyDoorOpen.value)
        assertEquals(true, h.fake.parkState.value.gearInPark)
    }

    // ---------------------------------------------------------------- 설정과 부가 정보

    @Test
    fun settingsChanges_areSavedNormalizedAndShown() = runTest {
        val h = harness()

        h.vm.setReclineLevel(40)
        h.vm.setTemperatureC(30f)
        h.vm.setFanLevel(4)
        h.vm.setTargetPercent(90)
        h.vm.setSeatControlEnabled(false)
        h.vm.setVoiceEnabled(false)
        h.vm.setDemoMode(true)
        runCurrent()

        val shown = h.ui.settings
        assertEquals(40, shown.reclineLevel)
        assertEquals(28f, shown.temperatureC)
        assertEquals(4, shown.fanLevel)
        assertEquals(90, shown.targetPercent)
        assertFalse(shown.seatControlEnabled)
        assertFalse(shown.voiceEnabled)
        assertTrue(shown.demoMode)
        assertEquals(shown, h.settings.settings.first())
    }

    @Test
    fun demoModeSwitch_ignoredWhileSessionActive() = runTest {
        val h = harness()
        startNap(h, sdkRemainingMinutes = 27, minutes = 20)

        h.vm.setDemoMode(true)
        runCurrent()

        assertFalse(h.ui.settings.demoMode)
    }

    @Test
    fun fallbackReasonAndVoiceAvailability_arePassedThrough() = runTest {
        val h = harness(fallbackReason = "vehicle service unavailable")
        assertEquals("vehicle service unavailable", h.ui.fallbackReason)
        assertFalse(h.ui.voiceAvailable)

        h.voiceAvailable.value = true
        runCurrent()

        assertTrue(h.ui.voiceAvailable)
    }

    @Test
    fun remainingSeconds_roundsUpAndNeverNegative() {
        assertEquals(60, NapViewModel.remainingSeconds(wakeAtMillis = 59_200, nowMillis = 0))
        assertEquals(1, NapViewModel.remainingSeconds(wakeAtMillis = 1, nowMillis = 0))
        assertEquals(0, NapViewModel.remainingSeconds(wakeAtMillis = 0, nowMillis = 0))
        assertEquals(0, NapViewModel.remainingSeconds(wakeAtMillis = 1_000, nowMillis = 5_000))
    }
}
