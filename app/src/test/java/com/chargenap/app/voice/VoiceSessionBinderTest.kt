package com.chargenap.app.voice

import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.Clock
import com.chargenap.app.session.ExtendDenyReason
import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionConfig
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionNotice
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가상 시간 + 가짜 음성·소음·문구 + 실제 [NapSessionEngine] + [FakeVehicleGateway](timeScale = 1.0).
 *
 * 가짜 음성은 한 문장을 말하는 데 1초가 걸리고, 듣기가 켜져 있고 말하는 중이 아닐 때만 인식 결과를 내보낸다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionBinderTest {

    private val minute = 60_000L
    private val manual = FakeScenario(startPercent = 50, percentPerMinute = 1.0, autoStartChargingAfterMillis = null)

    // ================================================================ 가짜

    private class FakeVoice(
        private val now: () -> Long,
        available: Boolean,
        canListen: Boolean = true,
        private val speakMillis: Long = SPEAK_MILLIS,
    ) : VoiceController {
        val availableFlow = MutableStateFlow(available)
        val canListenFlow = MutableStateFlow(canListen)
        override val available: StateFlow<Boolean> = availableFlow
        override val canListen: StateFlow<Boolean> = canListenFlow

        private val results = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val recognized: Flow<String> = results

        /** 말하기 시작한 문장과 그 시각 */
        val spoken = mutableListOf<String>()
        val spokenAt = mutableListOf<Long>()

        /** 끝까지 말한 문장 */
        val finished = mutableListOf<String>()

        /** 말하다가 끊긴 문장 */
        val interrupted = mutableListOf<String>()

        var listening = false
        var speaking = false
        var startListeningCalls = 0
        private var gate: CompletableDeferred<Unit>? = null

        override suspend fun initialize(): Boolean = availableFlow.value

        override suspend fun speak(text: String) {
            check(!speaking) { "두 문장을 동시에 말하려 했다: $text" }
            spoken += text
            spokenAt += now()
            speaking = true
            val stop = CompletableDeferred<Unit>()
            gate = stop
            var completed = false
            try {
                completed = withTimeoutOrNull(speakMillis) { stop.await() } == null
            } finally {
                speaking = false
                gate = null
                if (completed) finished += text else interrupted += text
            }
        }

        override fun stopSpeaking() {
            gate?.complete(Unit)
        }

        override fun startListening() {
            startListeningCalls++
            listening = true
        }

        override fun stopListening() {
            listening = false
        }

        override fun release() = Unit

        /** 사용자가 말했다. 실제 컨트롤러처럼 듣는 중이고 말하는 중이 아닐 때만 전달한다. */
        fun hear(text: String): Boolean {
            if (!listening || speaking) return false
            return results.tryEmit(text)
        }
    }

    private class FakeAmbient : AmbientSound {
        val calls = mutableListOf<String>()
        val playing: Boolean get() = calls.lastOrNull() == "start"
        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    /** 문장 대신 알아보기 쉬운 꼬리표를 돌려준다. 실제 문장은 [ResourceVoicePhrasesTest]에서 본다. */
    private class FakePhrases(private val guideLines: Int = 6) : VoicePhrases {
        override fun offer(recommendation: Recommendation, targetPercent: Int): String? = when (recommendation) {
            is Recommendation.Nap ->
                "offer:nap:$targetPercent:${recommendation.remainingChargeMinutes}:${recommendation.minutes}"
            is Recommendation.Breathing -> "offer:breathing:${recommendation.minutes}"
            is Recommendation.ChooseDuration -> "offer:choose:${recommendation.options.joinToString("/")}"
            Recommendation.None -> null
        }

        override fun rearSeatQuestion() = "rear:question"
        override fun rearSeatRetry() = "rear:retry"
        override fun rearSeatNoAnswer() = "rear:noAnswer"
        override fun doorQuestion() = "door:question"
        override fun closeDoors() = "door:close"
        override fun safetyWait(report: SafetyReport): String? = when {
            report.doors == CheckResult.FAIL -> "wait:door"
            report.parkingBrake == CheckResult.FAIL -> "wait:brake"
            else -> null
        }

        override fun startBlocked(report: SafetyReport) =
            if (report.gear == CheckResult.FAIL) "blocked:gear" else "blocked:charging"

        override fun preparing(seatMoves: Boolean) = "preparing:seat=$seatMoves"
        override fun breathingGuide(): List<String> = (1..guideLines).map { "guide:$it" }
        override fun remaining(minutes: Int) = "remaining:$minutes"
        override fun extendGranted(minutes: Int) = "extend:granted:$minutes"
        override fun extendDenied(reason: ExtendDenyReason) = "extend:denied:$reason"
        override fun waking(reason: WakeReason) = "waking:$reason"
        override fun briefing(summary: SessionSummary) = "briefing:${summary.sleptMinutes}"
        override fun aborted(reason: AbortReason, seatMoved: Boolean) = "aborted:$reason:seat=$seatMoved"
    }

    private class Harness(
        val fake: FakeVehicleGateway,
        val engine: NapSessionEngine,
        val voice: FakeVoice,
        val ambient: FakeAmbient,
        val binder: VoiceSessionBinder,
        val notices: List<SessionNotice>,
    ) {
        val state: NapSessionState get() = engine.state.value
        val spoken: List<String> get() = voice.spoken
        fun count(text: String): Int = voice.spoken.count { it == text }
        fun guideCount(): Int = voice.spoken.count { it.startsWith("guide:") }

        inline fun <reified T : NapSessionState> stateAs(): T {
            val current = engine.state.value
            assertTrue("기대한 상태 ${T::class.simpleName}, 실제 $current", current is T)
            return current as T
        }
    }

    private suspend fun TestScope.harness(
        scenario: FakeScenario = manual,
        config: SessionConfig = SessionConfig(),
        available: Boolean = true,
        canListen: Boolean = true,
        guideLines: Int = 6,
        targetPercent: Int = 80,
    ): Harness {
        val fake = FakeVehicleGateway(scenario, timeScale = 1.0, scope = backgroundScope)
        val engine = NapSessionEngine(
            gateway = fake,
            clock = Clock { testScheduler.currentTime },
            scope = backgroundScope,
            config = MutableStateFlow(config),
            tickMillis = 1_000,
        )
        val voice = FakeVoice({ testScheduler.currentTime }, available, canListen)
        val ambient = FakeAmbient()
        val binder = VoiceSessionBinder(
            engine, fake, voice, FakePhrases(guideLines), ambient, backgroundScope, targetPercent = { targetPercent },
        )
        val notices = mutableListOf<SessionNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.notices.collect { notices += it } }
        fake.start()
        engine.start()
        binder.start()
        runCurrent()
        return Harness(fake, engine, voice, ambient, binder, notices)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.charge(h: Harness, sdkRemainingMinutes: Int? = 27) {
        h.fake.setRemainingChargeMinutes(sdkRemainingMinutes)
        h.fake.startCharging()
        runCurrent()
    }

    /** 사용자가 말한다. 가짜 음성이 듣고 있지 않으면 실패한다. */
    private fun TestScope.say(h: Harness, text: String) {
        assertTrue("\"$text\"를 들을 수 없는 상태다(listening=${h.voice.listening}, speaking=${h.voice.speaking})", h.voice.hear(text))
        runCurrent()
    }

    /** 충전 시작 → 제안 멘트가 끝날 때까지. */
    private fun TestScope.toOffer(h: Harness, sdkRemainingMinutes: Int? = 27) {
        charge(h, sdkRemainingMinutes)
        assertTrue(h.state is NapSessionState.Offer)
        advance(SPEAK_MILLIS)
    }

    /** 제안 → "20분만 재워줘" → 뒷좌석 질문을 다 말할 때까지. */
    private fun TestScope.toRearSeatQuestion(h: Harness, sdkRemainingMinutes: Int? = 27) {
        toOffer(h, sdkRemainingMinutes)
        say(h, "20분만 재워줘")
        assertTrue(h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)
        advance(SPEAK_MILLIS)
    }

    /** 수면 상태까지. [settleMillis]만큼 더 흘려보낸다(기본: 호흡 안내가 끝나고 백색소음이 켜진 뒤). */
    private fun TestScope.toNapping(
        h: Harness,
        sdkRemainingMinutes: Int? = 27,
        settleMillis: Long = 3 * minute,
    ): NapSessionState.Napping {
        toRearSeatQuestion(h, sdkRemainingMinutes)
        say(h, "없어요")
        advance(1_000)
        h.stateAs<NapSessionState.Napping>()
        advance(settleMillis)
        return h.stateAs()
    }

    // ================================================================ 제안

    @Test
    fun offer_spokenOnceOnEntry() = runTest {
        val h = harness()
        assertTrue(h.spoken.isEmpty())
        assertFalse("Idle에서는 듣지 않는다", h.voice.listening)

        charge(h, sdkRemainingMinutes = 27)
        assertEquals(listOf("offer:nap:80:27:20"), h.spoken)
        assertTrue(h.voice.listening)

        // 추천이 갱신돼도 다시 말하지 않는다.
        advance(SPEAK_MILLIS)
        h.fake.setRemainingChargeMinutes(22)
        runCurrent()
        assertEquals(NapSessionState.Offer(Recommendation.Nap(15, 22)), h.state)
        advance(30_000)
        assertEquals(listOf("offer:nap:80:27:20"), h.spoken)
    }

    @Test
    fun offer_spokenAgain_afterReturningToIdle() = runTest {
        val h = harness()
        toOffer(h)
        h.fake.interruptCharging()
        advance(2_000)
        assertEquals(NapSessionState.Idle, h.state)
        assertFalse(h.voice.listening)

        h.fake.startCharging()
        runCurrent()
        assertTrue(h.state is NapSessionState.Offer)
        assertEquals(2, h.spoken.count { it.startsWith("offer:") })
        assertTrue(h.voice.listening)
    }

    @Test
    fun offer_usesTargetPercent_andOtherRecommendations() = runTest {
        val h = harness(targetPercent = 90)
        charge(h, sdkRemainingMinutes = null)
        assertEquals(listOf("offer:choose:15/20"), h.spoken)

        val breathing = harness()
        charge(breathing, sdkRemainingMinutes = 14)
        assertEquals(listOf("offer:breathing:5"), breathing.spoken)

        val nap = harness(targetPercent = 90)
        charge(nap, sdkRemainingMinutes = 27)
        assertEquals(listOf("offer:nap:90:27:20"), nap.spoken)
    }

    @Test
    fun offer_startWithMinutes_startsSession() = runTest {
        val h = harness()
        toOffer(h)

        say(h, "20분만 재워줘")
        val check = h.stateAs<NapSessionState.SafetyCheck>()
        assertEquals(20, check.minutes)
        assertTrue(check.rearSeatQuestionPending)
        assertEquals("rear:question", h.spoken.last())
    }

    @Test
    fun offer_yes_startsRecommended_bareNumberStartsThatLength() = runTest {
        val yes = harness()
        toOffer(yes)
        say(yes, "응")
        assertEquals(20, yes.stateAs<NapSessionState.SafetyCheck>().minutes)

        val number = harness()
        toOffer(number)
        say(number, "15분만")
        assertEquals(15, number.stateAs<NapSessionState.SafetyCheck>().minutes)
    }

    @Test
    fun offer_no_declines() = runTest {
        val h = harness()
        toOffer(h)
        say(h, "됐어")
        assertEquals(NapSessionState.Idle, h.state)
        advance(3 * minute)
        assertEquals("거절하면 같은 충전 동안 다시 제안하지 않는다", 1, h.spoken.size)
        assertFalse(h.voice.listening)
    }

    @Test
    fun offer_unknownWords_doNothing() = runTest {
        val h = harness()
        toOffer(h)
        say(h, "오늘 날씨 어때")
        assertTrue(h.state is NapSessionState.Offer)
        assertEquals(1, h.spoken.size)
    }

    @Test
    fun startBlocked_spokenWithReason() = runTest {
        val h = harness()
        h.fake.setGearInPark(false)
        h.fake.startCharging()
        runCurrent()
        assertEquals(NapSessionState.Idle, h.state)

        h.engine.requestStart()
        runCurrent()
        assertTrue(h.notices.any { it is SessionNotice.StartBlocked })
        assertEquals(listOf("blocked:gear"), h.spoken)
    }

    // ================================================================ 뒷좌석 질문

    @Test
    fun rearSeat_clear_reclinesSeat() = runTest {
        val h = harness()
        toRearSeatQuestion(h)

        say(h, "없어요")
        assertEquals(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true), h.state)
        assertEquals("preparing:seat=true", h.spoken.last())
        assertFalse("준비 중에는 듣지 않는다", h.voice.listening)

        advance(1_000)
        assertTrue(h.state is NapSessionState.Napping)
        assertTrue("applyNap(60,23.0,2)" in h.fake.commandLog)
        assertEquals(60, h.fake.seatReclineLevel.value)
    }

    @Test
    fun rearSeat_occupied_keepsSeat() = runTest {
        val h = harness()
        toRearSeatQuestion(h)

        say(h, "있어요")
        advance(1_000)
        assertTrue(h.state is NapSessionState.Napping)
        assertTrue("applyNap(0,23.0,2)" in h.fake.commandLog)
        assertEquals(0, h.fake.seatReclineLevel.value)
        assertTrue("preparing:seat=false" in h.spoken)
    }

    @Test
    fun rearSeat_noAnswerFor12Seconds_keepsSeat() = runTest {
        val h = harness()
        toRearSeatQuestion(h)       // 질문을 다 말한 시점

        advance(11_999)
        assertTrue("12초가 되기 전에는 기다린다", h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)
        assertTrue(h.fake.commandLog.isEmpty())

        advance(1)
        assertFalse(h.state is NapSessionState.SafetyCheck)
        assertTrue("rear:noAnswer" in h.spoken)
        advance(1_000)
        assertTrue(h.state is NapSessionState.Napping)
        assertTrue("applyNap(0,23.0,2)" in h.fake.commandLog)
        assertEquals(0, h.fake.seatReclineLevel.value)
    }

    @Test
    fun rearSeat_unknownTwice_keepsSeat() = runTest {
        val h = harness()
        toRearSeatQuestion(h)

        say(h, "잘 모르겠어")
        assertTrue("한 번은 다시 묻는다", h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)
        assertEquals("rear:retry", h.spoken.last())
        advance(SPEAK_MILLIS)

        say(h, "없는 것 같기도 하고 있는 것 같기도")
        assertFalse(h.state is NapSessionState.SafetyCheck)
        advance(1_000)
        assertTrue("applyNap(0,23.0,2)" in h.fake.commandLog)
        assertEquals(1, h.count("rear:retry"))
        assertEquals(1, h.count("rear:noAnswer"))
    }

    @Test
    fun rearSeat_retryRestartsTheTimeout() = runTest {
        val h = harness()
        toRearSeatQuestion(h)
        advance(10_000)

        say(h, "뭐라고")
        advance(SPEAK_MILLIS)       // 다시 묻는 말이 끝남
        advance(11_000)
        assertTrue(h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)

        say(h, "없어요")
        advance(1_000)
        assertTrue("applyNap(60,23.0,2)" in h.fake.commandLog)
        advance(30_000)
        assertFalse("답을 들은 뒤에는 무응답 안내를 하지 않는다", "rear:noAnswer" in h.spoken)
    }

    @Test
    fun rearSeat_answeredOnScreen_cancelsTimeout() = runTest {
        val h = harness()
        toRearSeatQuestion(h)
        advance(5_000)

        h.engine.answerRearSeat(true)       // 화면 버튼
        runCurrent()
        advance(1_000)
        assertTrue("applyNap(60,23.0,2)" in h.fake.commandLog)
        advance(30_000)
        assertFalse("rear:noAnswer" in h.spoken)
        assertTrue(h.state is NapSessionState.Napping)
    }

    @Test
    fun rearSeat_questionAskedOnce_whileStateRepeats() = runTest {
        val h = harness()
        toRearSeatQuestion(h)
        advance(8_000)      // 엔진 틱이 여러 번 지나간다
        assertEquals(1, h.count("rear:question"))
    }

    @Test
    fun rearSeat_cannotListen_asksButNeverTimesOut() = runTest {
        val h = harness(canListen = false)
        charge(h)
        h.engine.requestStart()
        runCurrent()
        assertEquals("rear:question", h.spoken.last())

        advance(minute)
        assertTrue("들을 수 없으면 화면 답을 기다린다", h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)
        assertFalse("rear:noAnswer" in h.spoken)
    }

    @Test
    fun safetyCheck_stop_cancelsWithoutTouchingCabin() = runTest {
        val h = harness()
        toRearSeatQuestion(h)

        say(h, "그만")
        assertEquals(NapSessionState.Idle, h.state)
        assertTrue(h.fake.commandLog.isEmpty())
        advance(30_000)
        assertFalse("rear:noAnswer" in h.spoken)
    }

    // ================================================================ 도어 확인과 대기

    @Test
    fun doorQuestion_yesConfirms_noAsksToClose() = runTest {
        val h = harness()
        h.fake.setDoorReadable(false)
        toRearSeatQuestion(h)
        assertFalse("뒷좌석 답을 듣기 전에는 도어를 묻지 않는다", "door:question" in h.spoken)

        say(h, "없어요")
        val check = h.stateAs<NapSessionState.SafetyCheck>()
        assertTrue(check.doorConfirmPending)
        assertEquals("door:question", h.spoken.last())
        advance(SPEAK_MILLIS)

        say(h, "아니")
        assertTrue(h.state is NapSessionState.SafetyCheck)
        assertEquals("door:close", h.spoken.last())
        advance(SPEAK_MILLIS)

        say(h, "네 닫혀 있어요")
        assertTrue(h.state is NapSessionState.Preparing)
        assertEquals(1, h.count("door:question"))
    }

    @Test
    fun doorOpen_waitGuidanceSpokenOnce() = runTest {
        val h = harness(config = SessionConfig(seatControlEnabled = false))
        h.fake.setDoorOpen(true)
        charge(h)
        h.engine.requestStart()
        runCurrent()
        assertEquals(CheckResult.FAIL, h.stateAs<NapSessionState.SafetyCheck>().report.doors)
        assertEquals("wait:door", h.spoken.last())

        advance(10_000)
        assertEquals(1, h.count("wait:door"))

        h.fake.setDoorOpen(false)
        runCurrent()
        assertFalse("문을 닫으면 진행한다", h.state is NapSessionState.SafetyCheck)
        advance(1_000)
        assertTrue(h.state is NapSessionState.Napping)
    }

    // ================================================================ 수면

    @Test
    fun napping_breathingGuide_thenAmbientOnNotice() = runTest {
        val h = harness(guideLines = 40)
        toNapping(h, settleMillis = 0)
        val napStart = testScheduler.currentTime
        assertTrue(h.voice.listening)

        advance(110_000)
        assertTrue("호흡 안내 중에는 소음을 켜지 않는다", h.ambient.calls.isEmpty())
        assertFalse(SessionNotice.BreathingGuideFinished in h.notices)
        val guideTimes = h.voice.spoken.indices.filter { h.voice.spoken[it].startsWith("guide:") }.map { h.voice.spokenAt[it] }
        assertTrue("호흡 안내를 여러 문장 말한다: ${guideTimes.size}", guideTimes.size >= 10)
        assertEquals(listOf("guide:1", "guide:2", "guide:3"), h.spoken.filter { it.startsWith("guide:") }.take(3))
        for ((previous, next) in guideTimes.zipWithNext()) {
            val gap = next - (previous + SPEAK_MILLIS)
            assertTrue("문장 사이 간격은 8~10초: $gap", gap in 8_000..10_000)
        }

        advance(12_000)
        assertTrue(SessionNotice.BreathingGuideFinished in h.notices)
        assertEquals(listOf("start"), h.ambient.calls)
        assertTrue(testScheduler.currentTime - napStart >= 2 * minute)

        val spokenAtNotice = h.guideCount()
        advance(2 * minute)
        assertEquals("알림 뒤에는 호흡 안내를 더 말하지 않는다", spokenAtNotice, h.guideCount())
        assertEquals(listOf("start"), h.ambient.calls)
    }

    @Test
    fun napping_shortGuide_endsByItself() = runTest {
        val h = harness(guideLines = 3)
        toNapping(h)
        assertEquals(3, h.guideCount())
        assertEquals(listOf("start"), h.ambient.calls)
    }

    @Test
    fun napping_wake_goesToWaking_andStopsAmbient() = runTest {
        val h = harness()
        toNapping(h)
        assertTrue(h.ambient.playing)

        say(h, "깨워줘")
        assertEquals(NapSessionState.Waking(WakeReason.USER), h.state)
        assertEquals(listOf("start", "stop"), h.ambient.calls)
        assertEquals("waking:USER", h.spoken.last())
        assertFalse("기상 중에는 듣지 않는다", h.voice.listening)
    }

    @Test
    fun napping_extend_granted() = runTest {
        val h = harness()
        val nap = toNapping(h, sdkRemainingMinutes = 45)

        say(h, "5분 더")
        val extended = h.stateAs<NapSessionState.Napping>()
        assertEquals(nap.wakeAtMillis + 5 * minute, extended.wakeAtMillis)
        assertTrue(SessionNotice.ExtendGranted(5, extended.wakeAtMillis) in h.notices)
        assertEquals("extend:granted:5", h.spoken.last())
        assertTrue("연장해도 소음은 그대로", h.ambient.playing)
    }

    @Test
    fun napping_extend_denied_spokenWithReason() = runTest {
        val h = harness()
        toNapping(h, sdkRemainingMinutes = 45)

        repeat(2) {
            say(h, "5분 더")
            assertEquals("extend:granted:5", h.spoken.last())
            advance(SPEAK_MILLIS)
        }
        say(h, "5분 더")      // 20 + 5 + 5 + 5 > 30
        assertTrue(SessionNotice.ExtendDenied(ExtendDenyReason.MAX_TOTAL) in h.notices)
        assertEquals("extend:denied:MAX_TOTAL", h.spoken.last())
    }

    @Test
    fun napping_status_speaksRemainingMinutesRoundedUp() = runTest {
        val h = harness()
        val nap = toNapping(h)
        val remainingMillis = nap.wakeAtMillis - testScheduler.currentTime
        assertTrue(remainingMillis % minute != 0L)
        val expected = (remainingMillis / minute + 1).toInt()

        say(h, "몇 분 남았어?")
        assertEquals("remaining:$expected", h.spoken.last())
        assertTrue("물어봐도 깨우지 않는다", h.state is NapSessionState.Napping)
    }

    @Test
    fun napping_contextFreeAnswers_ignored() = runTest {
        val h = harness()
        toNapping(h)
        val before = h.spoken.size

        for (text in listOf("응", "아니", "없어요", "오늘 날씨 어때")) say(h, text)
        assertTrue(h.state is NapSessionState.Napping)
        assertEquals(before, h.spoken.size)
    }

    @Test
    fun napping_wakeRescheduled_isSilent() = runTest {
        val h = harness()
        toNapping(h, sdkRemainingMinutes = 45)
        val before = h.spoken.size

        h.fake.setRemainingChargeMinutes(15)
        advance(2_000)
        assertTrue(h.notices.any { it is SessionNotice.WakeRescheduled })
        assertEquals("자는 사람에게 말하지 않는다", before, h.spoken.size)
    }

    // ================================================================ 기상, 브리핑

    @Test
    fun waking_timer_thenBriefing_acknowledgedByVoice() = runTest {
        val h = harness()
        toNapping(h)

        advance(17 * minute + 1_000)      // 수면 시작 20분 뒤
        assertEquals(NapSessionState.Waking(WakeReason.TIMER), h.state)
        assertTrue("waking:TIMER" in h.spoken)
        assertEquals(listOf("start", "stop"), h.ambient.calls)

        advance(22_000)
        val summary = h.stateAs<NapSessionState.Briefing>().summary
        assertEquals("briefing:${summary.sleptMinutes}", h.spoken.last())
        advance(SPEAK_MILLIS)

        say(h, "확인")
        assertEquals(NapSessionState.Idle, h.state)
        assertFalse(h.voice.listening)
    }

    @Test
    fun waking_interruptsWhatIsBeingSpoken() = runTest {
        val h = harness(guideLines = 40)
        toNapping(h, settleMillis = 0)
        // 호흡 안내 문장을 말하는 도중이 될 때까지 조금씩 흘려보낸다.
        while (!(h.voice.speaking && h.spoken.last().startsWith("guide:"))) advance(250)
        val line = h.spoken.last()

        h.engine.requestWake()      // 화면 터치
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.USER), h.state)
        assertTrue("말하던 호흡 안내를 끊는다", line in h.voice.interrupted)
        assertEquals("waking:USER", h.spoken.last())
        advance(minute)
        assertEquals("기상 뒤에는 호흡 안내를 잇지 않는다", line, h.spoken.last { it.startsWith("guide:") })
    }

    // ================================================================ 중단

    @Test
    fun aborted_doorOpened_announcedAtOnce_andStopsAmbient() = runTest {
        val h = harness()
        toNapping(h)
        say(h, "몇 분 남았어?")       // 대답을 말하는 중에 문이 열린다
        assertTrue(h.voice.speaking)

        h.fake.setDoorOpen(true)
        runCurrent()
        assertEquals(AbortReason.DOOR_OPENED, h.stateAs<NapSessionState.Aborted>().reason)
        assertEquals("aborted:DOOR_OPENED:seat=true", h.spoken.last())
        assertEquals(1, h.voice.interrupted.size)
        assertEquals(listOf("start", "stop"), h.ambient.calls)
        assertFalse(h.voice.listening)

        // 도어 닫힘 대기 → 복구 → 복구 완료로 상태가 여러 번 바뀌어도 한 번만 말한다.
        advance(10_000)
        h.fake.setDoorOpen(false)
        advance(5_000)
        assertFalse(h.stateAs<NapSessionState.Aborted>().restoring)
        assertEquals(1, h.spoken.count { it.startsWith("aborted:") })
    }

    @Test
    fun aborted_gearLeftPark_withoutSeatMove() = runTest {
        val h = harness()
        toRearSeatQuestion(h)
        say(h, "있어요")
        advance(5_000)
        assertTrue(h.state is NapSessionState.Napping)

        h.fake.setGearInPark(false)
        runCurrent()
        assertEquals(AbortReason.GEAR_LEFT_PARK, h.stateAs<NapSessionState.Aborted>().reason)
        assertEquals("aborted:GEAR_LEFT_PARK:seat=false", h.spoken.last())
    }

    // ================================================================ 음성을 쓸 수 없을 때

    @Test
    fun unavailable_neverSpeaksOrListens_butAmbientStillControlled() = runTest {
        val h = harness(available = false)
        charge(h)
        assertTrue(h.state is NapSessionState.Offer)

        h.engine.requestStart()
        runCurrent()
        assertTrue(h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)
        advance(minute)
        assertTrue("무응답 타임아웃을 걸지 않는다", h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)

        h.engine.answerRearSeat(true)
        runCurrent()
        advance(1_000)
        assertTrue(h.state is NapSessionState.Napping)
        assertTrue("applyNap(60,23.0,2)" in h.fake.commandLog)

        advance(119_000)
        assertTrue(h.ambient.calls.isEmpty())
        advance(2_000)
        assertEquals(listOf("start"), h.ambient.calls)

        h.engine.requestWake()
        runCurrent()
        assertEquals(NapSessionState.Waking(WakeReason.USER), h.state)
        assertEquals(listOf("start", "stop"), h.ambient.calls)

        advance(22_000)
        assertTrue(h.state is NapSessionState.Briefing)
        assertTrue("한마디도 하지 않는다", h.spoken.isEmpty())
        assertEquals(0, h.voice.startListeningCalls)
        assertFalse(h.voice.listening)
    }

    @Test
    fun availability_turnsOnLater_announcesCurrentState() = runTest {
        val h = harness(available = false)
        charge(h)
        advance(5_000)
        assertTrue(h.spoken.isEmpty())

        h.voice.availableFlow.value = true
        runCurrent()
        assertEquals(listOf("offer:nap:80:27:20"), h.spoken)
        assertTrue(h.voice.listening)
    }

    @Test
    fun availability_lostDuringQuestion_dropsTimeoutAndListening() = runTest {
        val h = harness()
        toRearSeatQuestion(h)
        advance(3_000)

        h.voice.availableFlow.value = false
        runCurrent()
        assertFalse(h.voice.listening)
        val spoken = h.spoken.size
        advance(minute)
        assertTrue(h.stateAs<NapSessionState.SafetyCheck>().rearSeatQuestionPending)
        assertEquals(spoken, h.spoken.size)
    }

    // ================================================================ 수명 주기

    @Test
    fun stop_silencesEverything() = runTest {
        val h = harness()
        toNapping(h)
        assertTrue(h.ambient.playing)
        assertTrue(h.voice.listening)

        h.binder.stop()
        runCurrent()
        assertFalse(h.ambient.playing)
        assertFalse(h.voice.listening)
        val spoken = h.spoken.size

        h.engine.requestWake()
        advance(30_000)
        assertTrue(h.state is NapSessionState.Briefing)
        assertEquals("멈춘 뒤에는 말하지 않는다", spoken, h.spoken.size)
    }

    @Test
    fun start_twice_doesNotDoubleSpeak_andRestartWorks() = runTest {
        val h = harness()
        h.binder.start()
        charge(h)
        assertEquals(1, h.spoken.size)

        h.binder.stop()
        runCurrent()
        h.binder.start()
        runCurrent()
        assertEquals("다시 시작하면 지금 상태를 안내한다", 2, h.spoken.count { it.startsWith("offer:") })
    }

    @Test
    fun binder_neverStartsOrStopsEngineOrGateway() = runTest {
        val h = harness()
        toOffer(h)
        h.binder.stop()
        runCurrent()

        // 바인더를 멈춘 뒤에도 엔진과 게이트웨이는 계속 돈다.
        h.engine.requestStart(20)
        runCurrent()
        assertTrue(h.state is NapSessionState.SafetyCheck)
        val percent = h.fake.batteryPercent.value!!
        advance(2 * minute)
        assertTrue(h.fake.batteryPercent.value!! > percent)
    }

    private companion object {
        const val SPEAK_MILLIS = 1_000L
    }
}
