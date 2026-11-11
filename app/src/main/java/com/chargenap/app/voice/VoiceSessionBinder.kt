package com.chargenap.app.voice

import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.SessionNotice
import com.chargenap.app.vehicle.VehicleGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 세션 엔진과 음성을 잇는다. 엔진 상태·알림을 말로 안내하고, 인식한 말을 엔진 이벤트로 바꾼다.
 *
 * 구조
 * - 엔진 상태, 알림, 인식 결과, 음성 사용 가능 여부는 모두 이벤트 큐 하나로 들어가고 루프 코루틴 하나가 순서대로
 *   처리한다. 내부 상태는 그 코루틴만 건드린다.
 * - 말하기는 [SpeechQueue]가 한 문장씩 차례로 한다. 기상·중단 안내는 진행 중인 말을 끊고 바로 말한다.
 * - 시간(뒷좌석 무응답 12초, 호흡 안내 간격)은 세션 시계가 아니라 실제 시간(`delay`)이다.
 *
 * 음성을 쓸 수 없으면(`voice.available == false`) 말하지도 듣지도 않고 뒷좌석 무응답 타임아웃도 걸지 않는다.
 * 말하기만 되고 듣기가 안 되면(`voice.canListen == false`) 질문은 말하되 무응답 타임아웃은 걸지 않는다.
 * 백색소음([ambient]) 제어는 음성과 무관하게 동작한다.
 *
 * 엔진과 게이트웨이의 `start()`/`stop()`은 부르지 않는다.
 *
 * @param gateway 지금은 쓰지 않는다(통합 시그니처 유지)
 * @param targetPercent 제안 멘트에 넣을 충전 목표 잔량
 */
class VoiceSessionBinder(
    private val engine: NapSessionEngine,
    @Suppress("unused") private val gateway: VehicleGateway,
    private val voice: VoiceController,
    private val phrases: VoicePhrases,
    private val ambient: AmbientSound,
    private val scope: CoroutineScope,
    private val targetPercent: () -> Int = { DEFAULT_TARGET_PERCENT },
) {
    private val lock = Any()
    private var running: Running? = null

    /** 엔진 상태·알림 구독을 시작한다. 이미 돌고 있으면 아무 일도 하지 않는다. */
    fun start() {
        synchronized(lock) {
            if (running != null) return
            running = Running().also { it.launch() }
        }
    }

    /** 구독을 끝내고 말하기·듣기·소음을 멈춘다. */
    fun stop() {
        val current = synchronized(lock) { running.also { running = null } } ?: return
        current.shutdown()
    }

    private sealed interface Event {
        data class State(val state: NapSessionState) : Event
        data class Notice(val notice: SessionNotice) : Event
        data class Heard(val text: String) : Event
        data class Availability(val available: Boolean) : Event
        data class RearQuestionSpoken(val epoch: Int) : Event
        data class RearTimeout(val epoch: Int) : Event
    }

    /** [start]부터 [stop]까지의 한 번. 아래 필드는 이벤트 루프 코루틴만 읽고 쓴다. */
    private inner class Running {
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val runScope = CoroutineScope(scope.coroutineContext + job)
        private val events = Channel<Event>(Channel.UNLIMITED)
        private val speech = SpeechQueue(voice, runScope)

        private var state: NapSessionState? = null
        private var available = voice.available.value
        private var listening = false

        /** 지금 상태에서 이미 말한 안내의 열쇠. 상태가 바뀌면 지운다. */
        private var announced: Any? = null
        private var ambientOn = false
        private var seatMoved = false
        private var guideJob: Job? = null

        // ---- SafetyCheck 안에서만 쓴다 ----
        private var waitSpoken: String? = null
        private var rearAsked = false
        private var doorAsked = false
        private var rearUnknowns = 0
        private var rearEpoch = 0
        private var rearTimer: Job? = null

        fun launch() {
            runScope.launch { for (event in events) handle(event) }
            runScope.launch { voice.available.collect { events.send(Event.Availability(it)) } }
            runScope.launch { engine.state.collect { events.send(Event.State(it)) } }
            runScope.launch { engine.notices.collect { events.send(Event.Notice(it)) } }
            runScope.launch { voice.recognized.collect { events.send(Event.Heard(it)) } }
        }

        fun shutdown() {
            job.cancel()
            speech.cancelAll()
            voice.stopSpeaking()
            voice.stopListening()
            ambient.stop()
        }

        private fun handle(event: Event) {
            when (event) {
                is Event.State -> onState(event.state)
                is Event.Notice -> onNotice(event.notice)
                is Event.Heard -> onHeard(event.text)
                is Event.Availability -> onAvailability(event.available)
                is Event.RearQuestionSpoken -> if (event.epoch == rearEpoch) startRearTimer(event.epoch)
                is Event.RearTimeout -> if (event.epoch == rearEpoch) rearSeatUnanswered()
            }
        }

        // ============================================================ 상태 → 말하기

        private fun onState(next: NapSessionState) {
            val previous = state
            state = next
            if (previous == null || isNewPhase(previous, next)) leavePhase(previous, next)
            enter(next)
        }

        private fun isNewPhase(previous: NapSessionState, next: NapSessionState): Boolean = when {
            previous::class != next::class -> true
            previous is NapSessionState.Aborted && next is NapSessionState.Aborted -> previous.reason != next.reason
            else -> false
        }

        private fun leavePhase(previous: NapSessionState?, next: NapSessionState) {
            announced = null
            stopGuide()
            resetSafetyCheck()
            when {
                // 기상·중단 안내는 기다리지 않는다.
                next is NapSessionState.Waking || next is NapSessionState.Aborted -> speech.cancelAll()
                // "시트를 눕힐게요"는 끝까지 말하고 호흡 안내로 넘어간다.
                previous is NapSessionState.Preparing && next is NapSessionState.Napping -> Unit
                else -> speech.cancel(SpeechKind.STATE, SpeechKind.QUESTION, SpeechKind.GUIDE)
            }
            if (next !is NapSessionState.Napping) stopAmbient()
            if (next == NapSessionState.Idle) seatMoved = false
        }

        /** 같은 상태로 여러 번 불려도 한 번만 말한다([announce]). 음성이 뒤늦게 켜지면 다시 불린다. */
        private fun enter(current: NapSessionState) {
            when (current) {
                NapSessionState.Idle -> listen(false)

                is NapSessionState.Offer -> {
                    announce(KEY_OFFER) { phrases.offer(current.recommendation, targetPercent()) }
                    listen(true)
                }

                is NapSessionState.SafetyCheck -> {
                    promptSafetyCheck(current)
                    listen(true)
                }

                is NapSessionState.Preparing -> {
                    seatMoved = current.seatConsent
                    announce(KEY_PREPARING) { phrases.preparing(current.seatConsent) }
                    listen(false)
                }

                is NapSessionState.Napping -> {
                    seatMoved = current.cabin.seatApplied
                    // 호흡 안내가 이미 끝났으면(백색소음 재생 중) 음성이 뒤늦게 켜져도 다시 시작하지 않는다.
                    if (available && announced != KEY_NAPPING && !ambientOn) {
                        announced = KEY_NAPPING
                        startGuide()
                    }
                    listen(true)
                }

                is NapSessionState.Waking -> {
                    announce(current.reason) { phrases.waking(current.reason) }
                    listen(false)
                }

                is NapSessionState.Briefing -> {
                    announce(KEY_BRIEFING) { phrases.briefing(current.summary) }
                    listen(true)
                }

                is NapSessionState.Aborted -> {
                    announce(current.reason) { phrases.aborted(current.reason, seatMoved) }
                    listen(false)
                }
            }
        }

        private inline fun announce(key: Any, text: () -> String?) {
            if (!available || announced == key) return
            announced = key
            say(text(), SpeechKind.STATE)
        }

        private fun promptSafetyCheck(current: NapSessionState.SafetyCheck) {
            if (!available) return
            // 도어가 열려 있거나 주차 브레이크가 풀려 있으면 한 번 안내한다. 풀렸다가 다시 생기면 다시 말한다.
            val wait = phrases.safetyWait(current.report)
            if (wait != null && wait != waitSpoken) say(wait, SpeechKind.STATE)
            waitSpoken = wait

            if (current.rearSeatQuestionPending) {
                if (!rearAsked) {
                    rearAsked = true
                    askRearSeat(phrases.rearSeatQuestion())
                }
                return
            }
            if (rearAsked) {
                // 화면에서 답했다. 질문을 더 말하거나 기다리지 않는다.
                rearAsked = false
                cancelRearTimer()
                speech.cancel(SpeechKind.QUESTION)
            }
            if (current.doorConfirmPending && !doorAsked) {
                doorAsked = true
                say(phrases.doorQuestion(), SpeechKind.QUESTION)
            }
        }

        private fun resetSafetyCheck() {
            waitSpoken = null
            rearAsked = false
            doorAsked = false
            rearUnknowns = 0
            cancelRearTimer()
        }

        // ============================================================ 뒷좌석 질문

        /** 질문을 말하고, 다 말한 뒤부터 무응답 시간을 잰다. */
        private fun askRearSeat(text: String) {
            cancelRearTimer()
            val epoch = rearEpoch
            say(text, SpeechKind.QUESTION) { events.trySend(Event.RearQuestionSpoken(epoch)) }
        }

        private fun startRearTimer(epoch: Int) {
            rearTimer?.cancel()
            // 들을 수 없으면 대답을 기다리지 않는다. 화면 버튼으로 답한다.
            if (!voice.canListen.value) return
            rearTimer = runScope.launch {
                delay(REAR_SEAT_ANSWER_TIMEOUT_MILLIS)
                events.send(Event.RearTimeout(epoch))
            }
        }

        /** 진행 중인 타이머와, 아직 도착하지 않은 타이머 이벤트를 무효로 만든다. */
        private fun cancelRearTimer() {
            rearEpoch++
            rearTimer?.cancel()
            rearTimer = null
        }

        /** 답이 없거나 두 번 알아듣지 못했다. 시트는 그대로 둔다. */
        private fun rearSeatUnanswered() {
            cancelRearTimer()
            // 기다리는 동안 듣기가 죽었으면 "무응답"으로 단정하지 않는다.
            if (!available || !voice.canListen.value) return
            val current = engine.state.value as? NapSessionState.SafetyCheck ?: return
            if (!current.rearSeatQuestionPending) return
            speech.cancel(SpeechKind.QUESTION)
            say(phrases.rearSeatNoAnswer(), SpeechKind.REPLY)
            engine.answerRearSeat(null)
        }

        private fun answerRearSeat(current: NapSessionState.SafetyCheck, clear: Boolean) {
            if (!current.rearSeatQuestionPending) return
            cancelRearTimer()
            engine.answerRearSeat(clear)
        }

        private fun rearSeatAnswerUnclear(current: NapSessionState.SafetyCheck) {
            if (!current.rearSeatQuestionPending || !rearAsked) return
            rearUnknowns++
            if (rearUnknowns >= MAX_REAR_SEAT_UNKNOWNS) rearSeatUnanswered() else askRearSeat(phrases.rearSeatRetry())
        }

        // ============================================================ 호흡 안내와 백색소음

        private fun startGuide() {
            guideJob?.cancel()
            guideJob = runScope.launch {
                for (line in phrases.breathingGuide()) {
                    speech.say(line, SpeechKind.GUIDE, null).await()
                    delay(BREATHING_GUIDE_GAP_MILLIS)
                }
            }
        }

        private fun stopGuide() {
            guideJob?.cancel()
            guideJob = null
        }

        private fun stopAmbient() {
            if (!ambientOn) return
            ambientOn = false
            ambient.stop()
        }

        // ============================================================ 알림 → 말하기

        private fun onNotice(notice: SessionNotice) {
            when (notice) {
                is SessionNotice.StartBlocked -> say(phrases.startBlocked(notice.report), SpeechKind.REPLY)
                is SessionNotice.ExtendGranted -> say(phrases.extendGranted(notice.minutes), SpeechKind.REPLY)
                is SessionNotice.ExtendDenied -> say(phrases.extendDenied(notice.reason), SpeechKind.REPLY)
                is SessionNotice.WakeRescheduled -> Unit        // 자는 사람을 깨우지 않는다
                SessionNotice.BreathingGuideFinished -> {
                    // 말하던 문장은 끝까지 말하고, 남은 호흡 안내는 버린다.
                    stopGuide()
                    speech.cancelQueued(SpeechKind.GUIDE)
                    if (engine.state.value is NapSessionState.Napping && !ambientOn) {
                        ambientOn = true
                        ambient.start()
                    }
                }
            }
        }

        // ============================================================ 인식 결과 → 엔진 이벤트

        private fun onHeard(text: String) {
            if (!available) return
            val current = engine.state.value
            val context = parseContext(current) ?: return
            val intent = IntentParser.parse(text, context)
            when (current) {
                is NapSessionState.Offer -> when (intent) {
                    VoiceIntent.Yes -> engine.requestStart()
                    is VoiceIntent.Start -> engine.requestStart(intent.minutes)
                    VoiceIntent.No, VoiceIntent.Wake -> engine.declineOffer()
                    else -> Unit
                }

                is NapSessionState.SafetyCheck -> when (intent) {
                    VoiceIntent.Wake -> engine.cancel()
                    VoiceIntent.RearSeatClear -> answerRearSeat(current, clear = true)
                    VoiceIntent.RearSeatOccupied -> answerRearSeat(current, clear = false)
                    VoiceIntent.Unknown -> rearSeatAnswerUnclear(current)
                    VoiceIntent.Yes -> if (awaitingDoorAnswer(current)) engine.confirmDoorsClosed()
                    VoiceIntent.No -> if (awaitingDoorAnswer(current)) say(phrases.closeDoors(), SpeechKind.REPLY)
                    else -> Unit
                }

                is NapSessionState.Preparing -> if (intent == VoiceIntent.Wake) engine.requestWake()

                is NapSessionState.Napping -> when (intent) {
                    VoiceIntent.Wake -> engine.requestWake()
                    is VoiceIntent.Extend -> engine.requestExtend(intent.minutes)
                    VoiceIntent.Status -> say(phrases.remaining(remainingMinutes(current)), SpeechKind.REPLY)
                    else -> Unit
                }

                is NapSessionState.Briefing -> if (intent == VoiceIntent.Yes) engine.acknowledgeBriefing()

                else -> Unit
            }
        }

        /** 지금 상태에서 어떤 대답을 기다리는가. 듣지 않는 상태면 null. */
        private fun parseContext(current: NapSessionState): ParseContext? = when (current) {
            is NapSessionState.Offer, is NapSessionState.Briefing -> ParseContext.OFFER
            is NapSessionState.SafetyCheck -> when {
                current.rearSeatQuestionPending -> ParseContext.REAR_SEAT_QUESTION
                current.doorConfirmPending -> ParseContext.DOOR_QUESTION
                else -> ParseContext.GENERAL
            }
            is NapSessionState.Preparing, is NapSessionState.Napping -> ParseContext.GENERAL
            else -> null
        }

        private fun awaitingDoorAnswer(current: NapSessionState.SafetyCheck): Boolean =
            !current.rearSeatQuestionPending && current.doorConfirmPending

        /** 기상 예정 시각까지 남은 분(올림). */
        private fun remainingMinutes(current: NapSessionState.Napping): Int {
            val millis = current.wakeAtMillis - engine.clock.nowMillis()
            return ceil(millis / MINUTE_MILLIS).toInt().coerceAtLeast(0)
        }

        // ============================================================ 음성 사용 가능 여부

        private fun onAvailability(now: Boolean) {
            if (now == available) return
            available = now
            if (!now) {
                // 터치만으로 동작한다. 무응답 타임아웃도 걸지 않는다.
                speech.cancelAll()
                stopGuide()
                resetSafetyCheck()
                announced = null
            }
            state?.let { enter(it) }
        }

        // ============================================================ 도우미

        private fun say(text: String?, kind: SpeechKind, onDone: (() -> Unit)? = null) {
            if (!available || text.isNullOrBlank()) return
            speech.say(text, kind, onDone)
        }

        private fun listen(on: Boolean) {
            val want = on && available
            if (want == listening) return
            listening = want
            if (want) voice.startListening() else voice.stopListening()
        }
    }

    private companion object {
        const val DEFAULT_TARGET_PERCENT = 80
        const val REAR_SEAT_ANSWER_TIMEOUT_MILLIS = 12_000L
        const val MAX_REAR_SEAT_UNKNOWNS = 2
        const val BREATHING_GUIDE_GAP_MILLIS = 9_000L
        const val MINUTE_MILLIS = 60_000.0

        const val KEY_OFFER = "offer"
        const val KEY_PREPARING = "preparing"
        const val KEY_NAPPING = "napping"
        const val KEY_BRIEFING = "briefing"
    }
}

/** 말의 종류. 상태가 바뀔 때 무엇을 끊을지 정하는 데 쓴다. */
private enum class SpeechKind {
    /** 상태 안내(제안, 준비, 기상, 브리핑, 중단) */
    STATE,

    /** 답을 기다리는 질문(뒷좌석, 도어) */
    QUESTION,

    /** 호흡 안내 */
    GUIDE,

    /** 사용자의 말이나 엔진 알림에 대한 짧은 대답. 상태가 바뀌어도 끝까지 말한다(기상·중단 제외) */
    REPLY,
}

/**
 * 문장을 한 번에 하나씩 차례로 말한다. 여러 스레드에서 불러도 된다.
 *
 * 취소된 문장은 말하지 않고(말하던 중이면 끊고) `onDone`도 부르지 않는다.
 */
private class SpeechQueue(private val voice: VoiceController, private val scope: CoroutineScope) {

    private class Item(val text: String, val kind: SpeechKind, val onDone: (() -> Unit)?) {
        /** true = 끝까지 말함, false = 취소됨 */
        val finished = CompletableDeferred<Boolean>()
        var cancelled = false       // lock으로 보호
        var job: Job? = null        // lock으로 보호
    }

    private val lock = Any()
    private val pending = ArrayDeque<Item>()
    private var current: Item? = null
    private var worker: Job? = null

    /** 줄 끝에 세운다. 돌려주는 값은 말하기가 끝나면(true) 또는 취소되면(false) 완료된다. */
    fun say(text: String, kind: SpeechKind, onDone: (() -> Unit)?): CompletableDeferred<Boolean> {
        val item = Item(text, kind, onDone)
        val starter = synchronized(lock) {
            pending.addLast(item)
            if (worker == null) scope.launch(start = CoroutineStart.LAZY) { drain() }.also { worker = it } else null
        }
        starter?.start()
        return item.finished
    }

    /** [kinds]에 해당하는 문장을 모두 취소한다. 말하던 중이면 끊는다. */
    fun cancel(vararg kinds: SpeechKind) = cancelWhere(includeCurrent = true) { it.kind in kinds }

    /** 아직 말하기 시작하지 않은 [kind] 문장만 취소한다. */
    fun cancelQueued(kind: SpeechKind) = cancelWhere(includeCurrent = false) { it.kind == kind }

    fun cancelAll() = cancelWhere(includeCurrent = true) { true }

    private fun cancelWhere(includeCurrent: Boolean, match: (Item) -> Boolean) {
        val dropped = mutableListOf<Item>()
        var interrupted: Job? = null
        var interrupt = false
        synchronized(lock) {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (match(item)) {
                    iterator.remove()
                    dropped += item
                }
            }
            val speaking = current
            if (includeCurrent && speaking != null && !speaking.cancelled && match(speaking)) {
                speaking.cancelled = true
                interrupted = speaking.job
                interrupt = true
            }
        }
        dropped.forEach { it.finished.complete(false) }
        if (interrupt) {
            interrupted?.cancel()
            voice.stopSpeaking()
        }
    }

    private suspend fun drain() {
        while (true) {
            val item = synchronized(lock) {
                val next = pending.removeFirstOrNull()
                current = next
                if (next == null) worker = null
                next
            } ?: return

            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    voice.speak(item.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // VoiceController는 예외를 던지지 않기로 했지만, 던지더라도 줄이 멈추지 않게 한다.
                }
            }
            val start = synchronized(lock) {
                item.job = job
                !item.cancelled
            }
            if (start) {
                job.start()
                job.join()
            } else {
                job.cancel()
            }
            val completed = synchronized(lock) {
                current = null
                !item.cancelled
            }
            item.finished.complete(completed)
            if (completed) item.onDone?.invoke()
        }
    }
}
