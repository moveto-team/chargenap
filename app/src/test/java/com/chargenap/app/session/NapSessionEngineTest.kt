package com.chargenap.app.session

import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.FakeClimate
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
import com.chargenap.app.vehicle.NapPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가상 시간 + [FakeVehicleGateway](timeScale = 1.0)로 세션 엔진의 흐름을 검증한다.
 *
 * 시간 기준: 충전 시작을 0으로 둔다. 시트는 250ms마다 20씩 움직이므로 리클라인 60은 750ms가 걸린다.
 * 엔진 틱은 1초다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NapSessionEngineTest {

    private val minute = 60_000L
    private val manual = FakeScenario(startPercent = 50, percentPerMinute = 1.0, autoStartChargingAfterMillis = null)
    private val initialClimate = FakeClimate(temperatureC = 21f, fanLevel = 3)

    private class Harness(
        val fake: FakeVehicleGateway,
        val engine: NapSessionEngine,
        val config: MutableStateFlow<SessionConfig>,
        val notices: List<SessionNotice>,
        val completed: List<SessionSummary>,
        val states: List<NapSessionState>,
    ) {
        val state: NapSessionState get() = engine.state.value

        /** 지나간 상태까지 포함해 마지막 `Preparing`. 시트를 움직이지 않으면 준비가 즉시 끝나 `state`로는 볼 수 없다. */
        val lastPreparing: NapSessionState.Preparing get() = states.filterIsInstance<NapSessionState.Preparing>().last()
        inline fun <reified T : NapSessionState> stateAs(): T {
            val current = engine.state.value
            assertTrue("기대한 상태 ${T::class.simpleName}, 실제 $current", current is T)
            return current as T
        }
    }

    private suspend fun TestScope.harness(
        scenario: FakeScenario = manual,
        config: SessionConfig = SessionConfig(),
    ): Harness {
        val fake = FakeVehicleGateway(scenario, timeScale = 1.0, scope = backgroundScope)
        val configFlow = MutableStateFlow(config)
        val engine = NapSessionEngine(
            gateway = fake,
            clock = Clock { testScheduler.currentTime },
            scope = backgroundScope,
            config = configFlow,
            tickMillis = 1_000,
        )
        val notices = mutableListOf<SessionNotice>()
        val completed = mutableListOf<SessionSummary>()
        val states = mutableListOf<NapSessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.collect { states += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.notices.collect { notices += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.completedSessions.collect { completed += it }
        }
        fake.start()
        engine.start()
        runCurrent()
        return Harness(fake, engine, configFlow, notices, completed, states)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    /** 충전을 시작한다. [sdkRemainingMinutes]가 null이 아니면 SDK가 남은 시간을 주는 차량을 흉내 낸다. */
    private fun TestScope.charge(h: Harness, sdkRemainingMinutes: Int? = 27) {
        h.fake.setRemainingChargeMinutes(sdkRemainingMinutes)
        h.fake.startCharging()
        runCurrent()
    }

    /** 시작 요청 → (물으면) 뒷좌석 답변 → 캐빈 적용까지 진행해 `Napping`을 돌려준다. 1초가 흐른다. */
    private fun TestScope.startNap(h: Harness, minutes: Int? = null, rearSeatClear: Boolean? = true): NapSessionState.Napping {
        h.engine.requestStart(minutes)
        runCurrent()
        if ((h.state as? NapSessionState.SafetyCheck)?.rearSeatQuestionPending == true) {
            h.engine.answerRearSeat(rearSeatClear)
            runCurrent()
        }
        advance(1_000)
        return h.stateAs()
    }

    /** `Waking` → `Briefing`까지 (기상 프리셋 20초 + 시트 복귀). */
    private fun TestScope.finishWaking(h: Harness): SessionSummary {
        advance(22_000)
        return h.stateAs<NapSessionState.Briefing>().summary
    }

    // ---------------------------------------------------------------- 정상 흐름

    @Test
    fun happyPath_offerToBriefing_recordsSummary() = runTest {
        val h = harness()
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.engine.state.collect { state ->
                val name = state::class.simpleName!!
                if (seen.lastOrNull() != name) seen += name
            }
        }
        assertEquals(NapSessionState.Idle, h.state)

        charge(h, sdkRemainingMinutes = 27)
        assertEquals(NapSessionState.Offer(Recommendation.Nap(minutes = 20, remainingChargeMinutes = 27)), h.state)
        assertEquals(27, h.engine.estimatedRemainingChargeMinutes.value)

        h.engine.requestStart()
        runCurrent()
        val check = h.stateAs<NapSessionState.SafetyCheck>()
        assertEquals(20, check.minutes)
        assertEquals(SessionMode.NAP, check.mode)
        assertEquals(SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS), check.report)
        assertTrue(check.rearSeatQuestionPending)
        assertFalse(check.doorConfirmPending)
        assertTrue("확인 전에는 차량에 아무 명령도 보내지 않는다", h.fake.commandLog.isEmpty())

        h.engine.answerRearSeat(true)
        runCurrent()
        assertEquals(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true), h.state)

        advance(1_000)
        val nap = h.stateAs<NapSessionState.Napping>()
        assertEquals(750L, nap.startedAtMillis)
        assertEquals(nap.startedAtMillis + 20 * minute, nap.wakeAtMillis)
        assertEquals(20, nap.plannedMinutes)
        assertEquals(SessionMode.NAP, nap.mode)
        assertFalse(nap.timerOnly)
        assertTrue(nap.cabin.seatApplied)
        assertTrue(nap.cabin.climateApplied)
        assertEquals(60, h.fake.seatReclineLevel.value)
        assertEquals(FakeClimate(23f, 2), h.fake.climate.value)

        advance(19 * minute)
        assertTrue(h.state is NapSessionState.Napping)

        advance(1 * minute + 1_000)
        assertEquals(NapSessionState.Waking(WakeReason.TIMER), h.state)
        assertEquals("기상용 서늘한 바람", FakeClimate(21f, 4), h.fake.climate.value)
        assertEquals(60, h.fake.seatReclineLevel.value)

        advance(18_000)
        assertEquals("20초 동안은 시트를 세우지 않는다", 60, h.fake.seatReclineLevel.value)
        assertTrue(h.state is NapSessionState.Waking)

        advance(3_000)
        val summary = h.stateAs<NapSessionState.Briefing>().summary
        assertEquals(750L, summary.startedAtMillis)
        assertEquals(20 * minute + 1_000, summary.endedAtMillis)
        assertEquals(SessionMode.NAP, summary.mode)
        assertEquals(20, summary.plannedMinutes)
        assertEquals(20, summary.sleptMinutes)
        assertEquals(50, summary.batteryStartPercent)
        assertEquals(70, summary.batteryEndPercent)
        assertEquals(315, summary.rangeKm)
        assertEquals(WakeReason.TIMER, summary.wakeReason)
        assertTrue(summary.seatMoved)
        assertTrue(summary.failures.isEmpty())

        assertEquals(listOf("snapshot", "applyNap(60,23.0,2)", "applyWake", "restore"), h.fake.commandLog)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertEquals(initialClimate, h.fake.climate.value)
        assertTrue("확인 전에는 기록하지 않는다", h.completed.isEmpty())

        h.engine.acknowledgeBriefing()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        assertEquals(listOf(summary), h.completed)

        h.engine.acknowledgeBriefing()
        advance(5_000)
        assertEquals("한 번만 기록한다", 1, h.completed.size)
        assertEquals("세션이 끝난 뒤 같은 충전에서는 다시 제안하지 않는다", NapSessionState.Idle, h.state)
        assertEquals(
            listOf("Idle", "Offer", "SafetyCheck", "Preparing", "Napping", "Waking", "Briefing", "Idle"),
            seen,
        )
    }

    @Test
    fun breathingGuideFinished_emittedOnceTwoMinutesAfterNapStart() = runTest {
        val h = harness()
        charge(h)
        startNap(h)

        advance(118_000)
        assertFalse(SessionNotice.BreathingGuideFinished in h.notices)
        advance(2_000)
        assertEquals(1, h.notices.count { it == SessionNotice.BreathingGuideFinished })
        advance(5 * minute)
        assertEquals(1, h.notices.count { it == SessionNotice.BreathingGuideFinished })
    }

    @Test
    fun userWake_wakesWithUserReason() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        advance(3 * minute)

        h.engine.requestWake()
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.USER), h.state)

        val summary = finishWaking(h)
        assertEquals(WakeReason.USER, summary.wakeReason)
        assertEquals(3, summary.sleptMinutes)
        assertEquals(20, summary.plannedMinutes)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    // ---------------------------------------------------------------- 제안

    @Test
    fun declinedOffer_notReofferedUntilNextCharge() = runTest {
        val h = harness()
        charge(h)
        assertTrue(h.state is NapSessionState.Offer)

        h.engine.declineOffer()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        advance(3 * minute)
        assertEquals("같은 충전 동안은 다시 제안하지 않는다", NapSessionState.Idle, h.state)

        h.fake.interruptCharging()
        advance(2_000)
        assertEquals(NapSessionState.Idle, h.state)
        h.fake.startCharging()
        runCurrent()
        assertTrue("충전이 끊겼다 다시 시작되면 제안한다", h.state is NapSessionState.Offer)
    }

    @Test
    fun declinedOffer_userCanStillRequestStart() = runTest {
        val h = harness()
        charge(h)
        h.engine.declineOffer()
        runCurrent()

        h.engine.requestStart(15)
        runCurrent()
        val check = h.stateAs<NapSessionState.SafetyCheck>()
        assertEquals(15, check.minutes)
    }

    @Test
    fun offer_followsSampleBasedEstimate() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = null)
        assertEquals(NapSessionState.Offer(Recommendation.ChooseDuration(listOf(15, 20))), h.state)
        assertNull(h.engine.estimatedRemainingChargeMinutes.value)

        advance(65_000)     // 51%: 첫 변화는 기준점일 뿐
        assertEquals(NapSessionState.Offer(Recommendation.ChooseDuration(listOf(15, 20))), h.state)

        advance(60_000)     // 52%: 분당 1% → 80%까지 28분
        assertEquals(28, h.engine.estimatedRemainingChargeMinutes.value)
        assertEquals(NapSessionState.Offer(Recommendation.Nap(20, 28)), h.state)
    }

    @Test
    fun remaining14_offersBreathing_andNeverMovesSeat() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 14)
        assertEquals(NapSessionState.Offer(Recommendation.Breathing(5, 14)), h.state)

        h.engine.requestStart()
        runCurrent()
        assertTrue("호흡 모드는 뒷좌석을 묻지 않는다", h.states.none { it is NapSessionState.SafetyCheck })
        assertEquals(NapSessionState.Preparing(5, SessionMode.BREATHING, seatConsent = false), h.lastPreparing)
        advance(1_000)
        val nap = h.stateAs<NapSessionState.Napping>()
        assertEquals(SessionMode.BREATHING, nap.mode)
        assertEquals(nap.startedAtMillis + 5 * minute, nap.wakeAtMillis)
        assertEquals(listOf("snapshot", "applyNap(0,23.0,2)"), h.fake.commandLog)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun remainingUnder10_noOffer() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 9)
        assertEquals(NapSessionState.Idle, h.state)

        // 제안 중에 남은 시간이 10분 아래로 떨어지면 제안을 거둔다.
        h.fake.setRemainingChargeMinutes(12)
        runCurrent()
        assertEquals(NapSessionState.Offer(Recommendation.Breathing(5, 12)), h.state)
        h.fake.setRemainingChargeMinutes(8)
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
    }

    @Test
    fun offer_withdrawnWhenChargingStops() = runTest {
        val h = harness()
        charge(h)
        assertTrue(h.state is NapSessionState.Offer)
        h.fake.unplug()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        assertNull(h.engine.estimatedRemainingChargeMinutes.value)
    }

    // ---------------------------------------------------------------- 안전 확인

    @Test
    fun gearUnknown_startBlocked() = runTest {
        val h = harness()
        h.fake.setGearReadable(false)
        charge(h)
        assertEquals("기어 P를 확인하지 못하면 제안도 하지 않는다", NapSessionState.Idle, h.state)
        assertEquals(CheckResult.FAIL, h.engine.startBlockers.value.gear)

        h.engine.requestStart(20)
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        val blocked = h.notices.filterIsInstance<SessionNotice.StartBlocked>().single()
        assertEquals(CheckResult.FAIL, blocked.report.gear)
        assertEquals(CheckResult.PASS, blocked.report.charging)
        assertTrue(h.fake.commandLog.isEmpty())
    }

    @Test
    fun notPluggedIn_startBlocked() = runTest {
        val h = harness()
        h.engine.requestStart(20)
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        val blocked = h.notices.filterIsInstance<SessionNotice.StartBlocked>().single()
        assertEquals(CheckResult.PASS, blocked.report.gear)
        assertEquals(CheckResult.FAIL, blocked.report.charging)
    }

    @Test
    fun gearLeavesPark_duringSafetyCheck_blocksWithoutTouchingCabin() = runTest {
        val h = harness()
        charge(h)
        h.engine.requestStart()
        runCurrent()
        assertTrue(h.state is NapSessionState.SafetyCheck)

        h.fake.setGearInPark(false)
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        assertEquals(1, h.notices.count { it is SessionNotice.StartBlocked })
        assertTrue(h.fake.commandLog.isEmpty())
    }

    @Test
    fun noRearSeatConsent_reclineZero() = runTest {
        for (answer in listOf(false, null)) {
            val h = harness()
            charge(h)
            h.engine.requestStart()
            runCurrent()
            h.engine.answerRearSeat(answer)
            runCurrent()
            assertEquals(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = false), h.lastPreparing)

            advance(1_000)
            val nap = h.stateAs<NapSessionState.Napping>()
            assertEquals(listOf("snapshot", "applyNap(0,23.0,2)"), h.fake.commandLog)
            assertEquals(0, h.fake.seatReclineLevel.value)
            assertFalse(nap.cabin.seatApplied)
            assertTrue(nap.cabin.climateApplied)
            assertTrue(nap.cabin.errors.isEmpty())

            h.engine.requestWake()
            runCurrent()
            assertFalse(finishWaking(h).seatMoved)
        }
    }

    @Test
    fun seatControlDisabled_skipsRearSeatQuestion() = runTest {
        val h = harness(config = SessionConfig(seatControlEnabled = false))
        charge(h)
        h.engine.requestStart()
        runCurrent()
        assertTrue(h.states.none { it is NapSessionState.SafetyCheck })
        assertEquals(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = false), h.lastPreparing)
        advance(1_000)
        assertTrue(h.state is NapSessionState.Napping)
        assertEquals(listOf("snapshot", "applyNap(0,23.0,2)"), h.fake.commandLog)
    }

    @Test
    fun seatUnsupported_skipsRearSeatQuestion() = runTest {
        val h = harness()
        h.fake.updateCapabilities { it.copy(seatRecline = false) }
        charge(h)
        h.engine.requestStart()
        runCurrent()
        assertTrue(h.states.none { it is NapSessionState.SafetyCheck })
        assertEquals(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = false), h.lastPreparing)
        assertTrue(h.state is NapSessionState.Napping)
        assertEquals(listOf("snapshot", "applyNap(0,23.0,2)"), h.fake.commandLog)
    }

    @Test
    fun doorUnreadable_needsUserConfirmBeforePreparing() = runTest {
        val h = harness()
        h.fake.setDoorReadable(false)
        charge(h)
        h.engine.requestStart()
        runCurrent()
        var check = h.stateAs<NapSessionState.SafetyCheck>()
        assertEquals(CheckResult.NEEDS_USER_CONFIRM, check.report.doors)
        assertTrue(check.doorConfirmPending)
        assertTrue(check.rearSeatQuestionPending)

        h.engine.answerRearSeat(true)
        advance(5_000)
        check = h.stateAs()
        assertTrue("도어 확인을 받기 전에는 넘어가지 않는다", check.doorConfirmPending)
        assertFalse(check.rearSeatQuestionPending)
        assertTrue(h.fake.commandLog.isEmpty())

        h.engine.confirmDoorsClosed()
        runCurrent()
        assertEquals(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true), h.state)
    }

    @Test
    fun doorOpen_duringSafetyCheck_waitsUntilClosed() = runTest {
        val h = harness()
        h.fake.setDoorOpen(true)
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.answerRearSeat(true)
        advance(5_000)
        val check = h.stateAs<NapSessionState.SafetyCheck>()
        assertEquals(CheckResult.FAIL, check.report.doors)
        assertTrue(h.fake.commandLog.isEmpty())

        h.fake.setDoorOpen(false)
        runCurrent()
        assertTrue(h.state is NapSessionState.Preparing)
    }

    @Test
    fun parkingBrakeOff_waitsInSafetyCheck_unknownIsSkipped() = runTest {
        val h = harness()
        h.fake.setParkingBrake(false)
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.answerRearSeat(true)
        advance(5_000)
        assertEquals(CheckResult.FAIL, h.stateAs<NapSessionState.SafetyCheck>().report.parkingBrake)
        assertTrue(h.fake.commandLog.isEmpty())

        h.fake.setParkingBrake(null)     // 값을 못 읽는 차종이면 건너뛴다
        runCurrent()
        assertTrue(h.state is NapSessionState.Preparing)
    }

    @Test
    fun cancel_inSafetyCheck_returnsIdleWithoutTouchingCabin() = runTest {
        val h = harness()
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.cancel()
        advance(5_000)
        assertEquals(NapSessionState.Idle, h.state)
        assertTrue(h.fake.commandLog.isEmpty())
    }

    @Test
    fun cancel_duringPreparing_stopsSeatAndRestores_withoutRecord() = runTest {
        val h = harness(config = SessionConfig(preset = NapPreset(100, 23f, 2)))
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.answerRearSeat(true)
        advance(600)
        assertTrue(h.fake.seatReclineLevel.value in 1..99)

        h.engine.cancel()
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.USER), h.state)
        advance(2_000)
        val summary = h.stateAs<NapSessionState.Briefing>().summary
        assertEquals(0, summary.sleptMinutes)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertEquals(initialClimate, h.fake.climate.value)
        assertFalse("잠들지 않았으니 기상 바람은 틀지 않는다", "applyWake" in h.fake.commandLog)

        h.engine.acknowledgeBriefing()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        assertTrue("수면에 들어가지 않은 세션은 기록하지 않는다", h.completed.isEmpty())
    }

    // ---------------------------------------------------------------- 기상 조건

    @Test
    fun chargeComplete_wakesEarly() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        advance(5 * minute)

        h.fake.completeCharging()
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.CHARGE_COMPLETE), h.state)

        val summary = finishWaking(h)
        assertEquals(WakeReason.CHARGE_COMPLETE, summary.wakeReason)
        assertEquals(5, summary.sleptMinutes)
        assertEquals(20, summary.plannedMinutes)
        assertEquals(80, summary.batteryEndPercent)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun chargingInterrupted_wakesAndReports() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        advance(4 * minute)

        h.fake.interruptCharging()
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.CHARGING_INTERRUPTED), h.state)

        val summary = finishWaking(h)
        assertEquals(WakeReason.CHARGING_INTERRUPTED, summary.wakeReason)
        assertEquals(4, summary.sleptMinutes)
        assertEquals(0, h.fake.seatReclineLevel.value)

        h.engine.acknowledgeBriefing()
        runCurrent()
        assertEquals(listOf(summary), h.completed)
    }

    @Test
    fun unplugged_wakesAsInterrupted() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.fake.unplug()
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.CHARGING_INTERRUPTED), h.state)
    }

    @Test
    fun chargeNearlyDone_wakesFiveMinutesBeforeChargeEnd() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 20)
        val nap = startNap(h, minutes = 25)
        assertEquals("충전 완료 예상(20분) 5분 전", 15 * minute, nap.wakeAtMillis)
        assertFalse(nap.timerOnly)

        advance(15 * minute - 2_000)                  // 14분 59초
        assertTrue(h.state is NapSessionState.Napping)
        advance(1_000)
        assertEquals(NapSessionState.Waking(WakeReason.CHARGE_NEARLY_DONE), h.state)
        assertEquals(14, finishWaking(h).sleptMinutes)
    }

    @Test
    fun wakeAt_recomputedWhenEstimateChanges() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 45)
        val nap = startNap(h, minutes = 25)
        assertEquals(nap.startedAtMillis + 25 * minute, nap.wakeAtMillis)

        advance(5 * minute)                       // 지금 5분 1초
        h.fake.setRemainingChargeMinutes(15)      // 충전이 예상보다 빨리 끝난다
        runCurrent()
        val expectedWakeAt = 5 * minute + 1_000 + 10 * minute
        val rescheduled = h.stateAs<NapSessionState.Napping>()
        assertEquals(expectedWakeAt, rescheduled.wakeAtMillis)
        assertFalse(rescheduled.timerOnly)
        assertEquals(listOf(SessionNotice.WakeRescheduled(expectedWakeAt)), h.notices.filterIsInstance<SessionNotice.WakeRescheduled>())

        advance(10 * minute - 1_000)
        assertTrue(h.state is NapSessionState.Napping)
        advance(1_000)
        assertEquals(NapSessionState.Waking(WakeReason.CHARGE_NEARLY_DONE), h.state)
    }

    @Test
    fun wakeAt_neverLaterThanTimer_whenEstimateGrows() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 27)
        val nap = startNap(h)
        h.fake.setRemainingChargeMinutes(90)
        runCurrent()
        assertEquals(nap.wakeAtMillis, h.stateAs<NapSessionState.Napping>().wakeAtMillis)
        assertTrue(h.notices.none { it is SessionNotice.WakeRescheduled })
    }

    @Test
    fun noEstimate_runsOnTimerOnly() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = null)
        val nap = startNap(h, minutes = 15)
        assertTrue(nap.timerOnly)
        assertEquals(nap.startedAtMillis + 15 * minute, nap.wakeAtMillis)

        advance(3 * minute)                       // 표본이 쌓이면 추정이 생긴다(완료 30분 → 한계 25분)
        val later = h.stateAs<NapSessionState.Napping>()
        assertFalse(later.timerOnly)
        assertEquals(nap.wakeAtMillis, later.wakeAtMillis)
    }

    // ---------------------------------------------------------------- 5분 더

    @Test
    fun extend_withinLimits_granted() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 45)
        val nap = startNap(h, minutes = 20)

        h.engine.requestExtend()
        runCurrent()
        val extended = h.stateAs<NapSessionState.Napping>()
        assertEquals(nap.startedAtMillis + 25 * minute, extended.wakeAtMillis)
        assertEquals(25, extended.plannedMinutes)
        assertEquals(listOf<SessionNotice>(SessionNotice.ExtendGranted(5, extended.wakeAtMillis)), h.notices)

        advance(24 * minute)
        assertTrue(h.state is NapSessionState.Napping)
        advance(1 * minute)
        assertEquals(NapSessionState.Waking(WakeReason.TIMER), h.state)
        val summary = finishWaking(h)
        assertEquals(25, summary.plannedMinutes)
        assertEquals(25, summary.sleptMinutes)
    }

    @Test
    fun extend_beyond30min_denied() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 45)
        val nap = startNap(h, minutes = 25)

        h.engine.requestExtend()                  // 30분: 넘지 않으므로 허용
        runCurrent()
        assertEquals(nap.startedAtMillis + 30 * minute, h.stateAs<NapSessionState.Napping>().wakeAtMillis)

        h.engine.requestExtend()                  // 35분: 거절
        runCurrent()
        assertEquals(SessionNotice.ExtendDenied(ExtendDenyReason.MAX_TOTAL), h.notices.last())
        val after = h.stateAs<NapSessionState.Napping>()
        assertEquals(nap.startedAtMillis + 30 * minute, after.wakeAtMillis)
        assertEquals(30, after.plannedMinutes)
    }

    @Test
    fun extend_pastChargeEnd_denied() = runTest {
        val h = harness()
        charge(h, sdkRemainingMinutes = 27)       // 충전 완료 27분, 한계 22분
        val nap = startNap(h)                     // 20분

        h.engine.requestExtend()
        runCurrent()
        assertEquals(listOf<SessionNotice>(SessionNotice.ExtendDenied(ExtendDenyReason.CHARGE_ENDS_SOON)), h.notices)
        val after = h.stateAs<NapSessionState.Napping>()
        assertEquals(nap.wakeAtMillis, after.wakeAtMillis)
        assertEquals(20, after.plannedMinutes)
    }

    @Test
    fun extend_whenNotNapping_denied() = runTest {
        val h = harness()
        h.engine.requestExtend()
        runCurrent()
        assertEquals(listOf<SessionNotice>(SessionNotice.ExtendDenied(ExtendDenyReason.NOT_NAPPING)), h.notices)
    }

    // ---------------------------------------------------------------- 캐빈 일부 실패

    @Test
    fun seatFailure_sessionContinues_failureInBriefing() = runTest {
        val h = harness()
        h.fake.failSeat = true
        charge(h)
        val nap = startNap(h)
        assertTrue(nap.cabin.seatFailed)
        assertFalse(nap.cabin.seatApplied)
        assertTrue(nap.cabin.climateApplied)
        assertEquals(FakeClimate(23f, 2), h.fake.climate.value)

        advance(20 * minute)
        assertEquals(NapSessionState.Waking(WakeReason.TIMER), h.state)
        val summary = finishWaking(h)
        assertEquals(listOf("seat: 가상 시트 오류"), summary.failures)
        assertFalse(summary.seatMoved)
        assertEquals(20, summary.sleptMinutes)
    }

    @Test
    fun climateFailure_listedOnceInBriefing() = runTest {
        val h = harness()
        h.fake.failClimate = true
        charge(h)
        val nap = startNap(h)
        assertTrue(nap.cabin.climateFailed)
        assertTrue(nap.cabin.seatApplied)

        h.engine.requestWake()
        runCurrent()
        val summary = finishWaking(h)
        assertEquals("수면·기상 프리셋이 같은 문구로 실패하면 한 번만 보여 준다", listOf("climate: 가상 공조 오류"), summary.failures)
        assertTrue(summary.seatMoved)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun restoreFailure_afterWake_listedInBriefing() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.fake.failRestore = true
        h.engine.requestWake()
        runCurrent()
        val summary = finishWaking(h)
        assertEquals(listOf("climate: 가상 공조 복구 오류", "seat: 가상 시트 복구 오류"), summary.failures)
    }

    // ---------------------------------------------------------------- 중단

    @Test
    fun doorOpened_duringNap_abortsAndRestoresAfterClose() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        advance(3 * minute)

        h.fake.setDoorOpen(true)
        runCurrent()
        assertEquals(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null), h.state)

        advance(30_000)
        assertEquals("도어가 열려 있는 동안은 시트를 움직이지 않는다", 60, h.fake.seatReclineLevel.value)
        assertFalse("restore" in h.fake.commandLog)
        assertEquals(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null), h.state)

        h.fake.setDoorOpen(false)
        advance(1_000)
        val aborted = h.stateAs<NapSessionState.Aborted>()
        assertEquals(AbortReason.DOOR_OPENED, aborted.reason)
        assertFalse(aborted.restoring)
        assertEquals(CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()), aborted.restore)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertEquals(initialClimate, h.fake.climate.value)
        assertFalse("applyWake" in h.fake.commandLog)

        advance(16_000)
        assertEquals("복구가 끝나고 15초 뒤 Idle", NapSessionState.Idle, h.state)
        assertTrue("중단된 세션은 기록하지 않는다", h.completed.isEmpty())
    }

    @Test
    fun doorOpened_duringPreparing_cancelsSeatMove() = runTest {
        val h = harness(config = SessionConfig(preset = NapPreset(100, 23f, 2)))
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.answerRearSeat(true)
        advance(600)
        assertTrue(h.state is NapSessionState.Preparing)

        h.fake.setDoorOpen(true)
        runCurrent()
        val stoppedAt = h.fake.seatReclineLevel.value
        assertTrue("시트가 중간에서 멈춰야 한다: $stoppedAt", stoppedAt in 1..99)
        assertEquals(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null), h.state)

        advance(5_000)
        assertEquals(stoppedAt, h.fake.seatReclineLevel.value)
        assertEquals(listOf("snapshot", "applyNap(100,23.0,2)"), h.fake.commandLog)

        h.fake.setDoorOpen(false)
        advance(1_000)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertEquals(initialClimate, h.fake.climate.value)
        assertFalse(h.stateAs<NapSessionState.Aborted>().restoring)
    }

    @Test
    fun doorOpened_duringWaking_stopsSeatReturn() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.engine.requestWake()
        runCurrent()
        advance(20_400)                           // 기상 바람 20초 뒤 시트가 돌아오는 중
        assertTrue(h.fake.seatReclineLevel.value in 1..59)

        h.fake.setDoorOpen(true)
        runCurrent()
        val stoppedAt = h.fake.seatReclineLevel.value
        assertEquals(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null), h.state)
        advance(3_000)
        assertEquals(stoppedAt, h.fake.seatReclineLevel.value)

        h.fake.setDoorOpen(false)
        advance(1_000)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun doorOpened_userConfirm_restoresWithoutWaitingForDoor() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.fake.setDoorOpen(true)
        runCurrent()

        h.engine.confirmDoorsClosed()
        advance(2_000)
        val aborted = h.stateAs<NapSessionState.Aborted>()
        assertFalse(aborted.restoring)
        assertEquals(0, h.fake.seatReclineLevel.value)

        h.engine.acknowledgeAbort()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
    }

    @Test
    fun doorReopened_duringAbortRestore_stopsSeatAgain() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.fake.setDoorOpen(true)
        runCurrent()
        h.fake.setDoorOpen(false)
        advance(300)                              // 복구 시작, 시트 60 → 40
        h.fake.setDoorOpen(true)
        runCurrent()
        val stoppedAt = h.fake.seatReclineLevel.value
        assertTrue(stoppedAt in 1..59)
        advance(5_000)
        assertEquals(stoppedAt, h.fake.seatReclineLevel.value)
        assertTrue(h.stateAs<NapSessionState.Aborted>().restoring)

        h.fake.setDoorOpen(false)
        advance(1_000)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertFalse(h.stateAs<NapSessionState.Aborted>().restoring)
    }

    @Test
    fun gearLeftPark_abortsAndRestoresImmediately() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        advance(3 * minute)

        h.fake.setGearInPark(false)
        runCurrent()
        assertEquals(NapSessionState.Aborted(AbortReason.GEAR_LEFT_PARK, restoring = true, restore = null), h.state)
        assertEquals("즉시 복구를 시작한다", "restore", h.fake.commandLog.last())

        advance(1_000)
        val aborted = h.stateAs<NapSessionState.Aborted>()
        assertEquals(AbortReason.GEAR_LEFT_PARK, aborted.reason)
        assertFalse(aborted.restoring)
        assertTrue(aborted.restore!!.errors.isEmpty())
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertEquals(initialClimate, h.fake.climate.value)
        assertEquals(listOf("snapshot", "applyNap(60,23.0,2)", "restore"), h.fake.commandLog)

        h.engine.acknowledgeAbort()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        assertTrue(h.completed.isEmpty())
    }

    @Test
    fun gearLeftPark_duringPreparing_cancelsApplyAndRestores() = runTest {
        val h = harness(config = SessionConfig(preset = NapPreset(100, 23f, 2)))
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.answerRearSeat(true)
        advance(600)

        h.fake.setGearInPark(false)
        advance(1_000)
        val aborted = h.stateAs<NapSessionState.Aborted>()
        assertEquals(AbortReason.GEAR_LEFT_PARK, aborted.reason)
        assertFalse(aborted.restoring)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun gearLeftPark_whileWaitingForDoor_restoresImmediately() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.fake.setDoorOpen(true)
        runCurrent()
        assertEquals(60, h.fake.seatReclineLevel.value)

        h.fake.setGearInPark(false)               // 도어가 열린 채로 출발하려 한다 → 시트부터 세운다
        advance(1_000)
        val aborted = h.stateAs<NapSessionState.Aborted>()
        assertEquals(AbortReason.GEAR_LEFT_PARK, aborted.reason)
        assertFalse(aborted.restoring)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun napping_gearBecomesUnknown_keepsSession() = runTest {
        val h = harness()
        charge(h)
        val nap = startNap(h)

        h.fake.setGearReadable(false)
        h.fake.setDoorReadable(false)
        advance(2 * minute)
        val still = h.stateAs<NapSessionState.Napping>()
        assertEquals(nap.wakeAtMillis, still.wakeAtMillis)
        assertEquals(60, h.fake.seatReclineLevel.value)
        assertEquals(listOf("snapshot", "applyNap(60,23.0,2)"), h.fake.commandLog)

        h.fake.setGearReadable(true)              // 다시 읽히고 P 그대로면 계속
        h.fake.setDoorReadable(true)
        advance(1 * minute)
        assertTrue(h.state is NapSessionState.Napping)
    }

    @Test
    fun abort_restoreFails_reportsFailureAndReturnsIdle() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.fake.failRestore = true

        h.fake.setGearInPark(false)
        advance(1_000)
        val aborted = h.stateAs<NapSessionState.Aborted>()
        assertFalse(aborted.restoring)
        assertTrue(aborted.restore!!.seatFailed)
        assertTrue(aborted.restore!!.climateFailed)
        assertEquals(listOf("climate: 가상 공조 복구 오류", "seat: 가상 시트 복구 오류"), aborted.restore!!.errors)

        advance(16_000)
        assertEquals(NapSessionState.Idle, h.state)
        assertTrue(h.completed.isEmpty())

        // 엔진은 멈추지 않는다: 다음 충전에서 다시 제안한다.
        h.fake.failRestore = false
        h.fake.setGearInPark(true)
        h.fake.interruptCharging()
        runCurrent()
        h.fake.startCharging()
        runCurrent()
        assertTrue(h.state is NapSessionState.Offer)
    }

    @Test
    fun briefing_gearLeavesPark_acknowledgesAutomatically() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        h.engine.requestWake()
        runCurrent()
        val summary = finishWaking(h)

        h.fake.setGearInPark(false)               // 확인을 누르지 않고 출발
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)
        assertEquals(listOf(summary), h.completed)
    }

    // ---------------------------------------------------------------- 종료

    @Test
    fun shutdown_duringNap_restoresCabin() = runTest {
        val h = harness()
        charge(h)
        startNap(h)
        assertEquals(60, h.fake.seatReclineLevel.value)

        h.engine.shutdown()
        assertEquals(NapSessionState.Idle, h.state)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertEquals(initialClimate, h.fake.climate.value)
        assertEquals("restore", h.fake.commandLog.last())
        assertTrue(h.completed.isEmpty())

        // 구독이 끝났으므로 차량 변화에 더는 반응하지 않는다.
        h.fake.interruptCharging()
        runCurrent()
        h.fake.startCharging()
        advance(5_000)
        assertEquals(NapSessionState.Idle, h.state)
    }

    @Test
    fun shutdown_duringPreparing_stopsSeatAndRestores() = runTest {
        val h = harness(config = SessionConfig(preset = NapPreset(100, 23f, 2)))
        charge(h)
        h.engine.requestStart()
        runCurrent()
        h.engine.answerRearSeat(true)
        advance(600)

        h.engine.shutdown()
        assertEquals(NapSessionState.Idle, h.state)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun shutdown_whenIdle_justStops_andCanRestart() = runTest {
        val h = harness()
        h.engine.shutdown()
        assertEquals(NapSessionState.Idle, h.state)
        assertTrue(h.fake.commandLog.isEmpty())

        h.engine.start()
        runCurrent()
        charge(h)
        assertTrue(h.state is NapSessionState.Offer)
    }
}
