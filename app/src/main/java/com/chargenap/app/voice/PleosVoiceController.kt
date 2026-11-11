package com.chargenap.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener

/**
 * Pleos TextToSpeech·SpeechToText 3.3.1 래퍼. 호출 근거는 docs/sdk-notes.md 10절(TTS), 11절(STT).
 *
 * - 둘 다 `Mode.ON_DEVICE`로 만든다(앱 등록이 필요 없다. sdk-notes 0절 9번). 언어는 한국어.
 * - SDK 콜백은 메인 스레드가 아닐 수 있으므로(0절 8번) 상태는 [lock]으로 보호하고, SDK 호출은 잠금 밖에서 한다.
 * - 모든 SDK 호출은 `Throwable`을 잡는다. 예외가 나면 [available]을 false로 내려 앱이 터치만으로 동작하게 한다.
 * - [available]은 "말할 수 있다"는 뜻이다. STT만 초기화에 실패하면 말하기는 되고 듣기만 아무 일도 하지 않는다.
 *   듣기가 되는지는 [canListen]으로 알린다(초기화 실패, 오류가 이어져 포기하면 false. 다시 인식되면 true).
 *
 * 실제 동작(콜백 순서, 침묵 시 세션 종료 방식)은 에뮬레이터에서 확인해야 한다(sdk-notes 16절 10·11번).
 */
class PleosVoiceController(context: Context, private val scope: CoroutineScope) : VoiceController {

    private val appContext: Context = context.applicationContext

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _canListen = MutableStateFlow(false)
    override val canListen: StateFlow<Boolean> = _canListen.asStateFlow()

    private val _recognized = MutableSharedFlow<String>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val recognized: Flow<String> = _recognized.asSharedFlow()

    private val initMutex = Mutex()
    private val speakMutex = Mutex()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var stt: SpeechToText? = null

    // ---- 아래는 lock으로 보호한다 ----
    private val lock = Any()
    private var wantListening = false
    private var speaking = false
    private var sessionOpen = false
    private var listenErrors = 0
    private var speakErrors = 0
    private var restartJob: Job? = null

    /** 진행 중인 말하기(또는 정지)의 끝을 알리는 신호. true = SDK 콜백으로 끝남, false = 밖에서 끊음. */
    private var utterance: CompletableDeferred<Boolean>? = null

    // ================================================================ 초기화와 해제

    override suspend fun initialize(): Boolean = initMutex.withLock {
        if (tts != null) return@withLock _available.value
        val ok = try {
            if (!speechServiceInstalled()) {
                Log.i(TAG, "음성 서비스($SERVICE_PACKAGE)가 없어 음성을 끕니다")
                false
            } else {
                // 서비스 바인딩과 콜백 등록은 Looper가 있는 메인 스레드에서 한다.
                withContext(Dispatchers.Main.immediate) {
                    val ttsReady = initializeTts()
                    if (ttsReady) initializeStt()
                    ttsReady
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "음성 초기화 실패", t)
            false
        }
        _available.value = ok && tts != null
        _available.value
    }

    /** sdk-notes 1.3: TTS·STT 3.3.1은 `ai.pleos.playground.caas` 패키지의 서비스에 연결한다. */
    private fun speechServiceInstalled(): Boolean {
        val pm = appContext.packageManager ?: return false
        val byPackage = try {
            pm.getPackageInfo(SERVICE_PACKAGE, 0)
            true
        } catch (t: Throwable) {
            false
        }
        if (byPackage) return true
        return try {
            pm.resolveService(Intent(SPEECH_START_ACTION).setPackage(SERVICE_PACKAGE), 0) != null
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun initializeTts(): Boolean {
        val engine = TextToSpeech(appContext, TtsMode.ON_DEVICE)
        try {
            val result = CompletableDeferred<Boolean>()
            engine.initialize(object : TtsInitializeListener {
                override fun onSuccess() {
                    result.complete(true)
                }

                override fun onFailure(errorType: TtsErrorType) {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                    result.complete(false)
                }
            })
            // 서비스가 응답하지 않으면 콜백이 오지 않을 수 있다.
            val ready = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { result.await() }
            if (ready != true) {
                if (ready == null) Log.w(TAG, "TTS 초기화 응답 없음")
                quietly("tts.release") { engine.release() }
                return false
            }
            engine.addEventListener(ttsListener)
            engine.setLocale(Locale.KOREA)
            tts = engine
            return true
        } catch (e: CancellationException) {
            quietly("tts.release") { engine.release() }
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "TTS 초기화 중 예외", t)
            quietly("tts.release") { engine.release() }
            return false
        }
    }

    /** STT 실패는 말하기를 막지 않는다. 듣기만 꺼진다. */
    private suspend fun initializeStt() {
        var engine: SpeechToText? = null
        try {
            val created = SpeechToText(appContext, SttMode.ON_DEVICE)
            engine = created
            val result = CompletableDeferred<Boolean>()
            created.initialize(object : SttInitializeListener {
                override fun onSuccess() {
                    result.complete(true)
                }

                override fun onFailure(errorType: SttErrorType) {
                    Log.w(TAG, "STT 초기화 실패: $errorType")
                    result.complete(false)
                }
            })
            val ready = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { result.await() }
            if (ready != true) {
                if (ready == null) Log.w(TAG, "STT 초기화 응답 없음")
                quietly("stt.release") { created.release() }
                return
            }
            created.addListener(sttListener)
            created.setLocale(Locale.KOREA)
            stt = created
            _canListen.value = true
        } catch (e: CancellationException) {
            engine?.let { quietly("stt.release") { it.release() } }
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "STT 초기화 중 예외", t)
            engine?.let { quietly("stt.release") { it.release() } }
        }
    }

    override fun release() {
        val pending = synchronized(lock) {
            wantListening = false
            sessionOpen = false
            restartJob?.cancel()
            restartJob = null
            utterance
        }
        _available.value = false
        _canListen.value = false
        val speech = tts
        val recognizer = stt
        tts = null
        stt = null
        pending?.complete(false)
        // sdk-notes 10·11절: stop → 리스너 제거 → release 순서.
        recognizer?.let {
            quietly("stt.stop") { it.stop() }
            quietly("stt.removeListener") { it.removeListener(sttListener) }
            quietly("stt.release") { it.release() }
        }
        speech?.let {
            quietly("tts.stop") { it.stop() }
            quietly("tts.removeEventListener") { it.removeEventListener(ttsListener) }
            quietly("tts.release") { it.release() }
        }
    }

    // ================================================================ 말하기

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        speakMutex.withLock {
            val engine = tts ?: return
            if (!_available.value) return
            val done = CompletableDeferred<Boolean>()
            beginSpeaking(done)
            try {
                val finished = if (guarded("tts.speak") { engine.speak(text) }) {
                    withTimeoutOrNull(speakTimeoutMillis(text)) { done.await() } ?: false
                } else {
                    true
                }
                // 타임아웃이거나 밖에서 끊었으면 실제 재생도 멈춘다.
                if (!finished) stopAndSettle(engine)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stopAndSettle(engine) }
                throw e
            } finally {
                endSpeaking()
            }
        }
    }

    override fun stopSpeaking() {
        synchronized(lock) { utterance }?.complete(false)
    }

    /** 듣기를 잠시 멈추고(마이크 해제) 말하기 상태로 들어간다. */
    private fun beginSpeaking(done: CompletableDeferred<Boolean>) {
        val wasListening = synchronized(lock) {
            utterance = done
            speaking = true
            restartJob?.cancel()
            restartJob = null
            wantListening
        }
        if (wasListening) quietly("stt.stop") { stt?.stop() }
    }

    private fun endSpeaking() {
        synchronized(lock) {
            utterance = null
            speaking = false
        }
        scheduleListen(RESTART_DELAY_MILLIS)
    }

    /**
     * 재생을 멈추고 `onStop` 콜백을 잠깐 기다린다. 기다리지 않으면 늦게 온 콜백이 다음 문장을 끝난 것으로 만든다.
     */
    private suspend fun stopAndSettle(engine: TextToSpeech) {
        val settled = CompletableDeferred<Boolean>()
        synchronized(lock) { utterance = settled }
        quietly("tts.stop") { engine.stop() }
        withTimeoutOrNull(STOP_SETTLE_MILLIS) { settled.await() }
    }

    private fun finishUtterance() {
        synchronized(lock) { utterance }?.complete(true)
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            synchronized(lock) { speakErrors = 0 }
            finishUtterance()
        }

        override fun onStop() = finishUtterance()

        override fun onError(errorType: TtsErrorType) {
            Log.w(TAG, "TTS 오류: $errorType")
            val giveUp = synchronized(lock) { ++speakErrors >= MAX_SPEAK_ERRORS }
            if (giveUp) disable("TTS 오류가 $MAX_SPEAK_ERRORS 번 이어짐")
            finishUtterance()
        }
    }

    // ================================================================ 듣기

    override fun startListening() {
        synchronized(lock) {
            if (stt == null || !_available.value || wantListening) return
            wantListening = true
            listenErrors = 0
        }
        scheduleListen(0)
    }

    override fun stopListening() {
        synchronized(lock) {
            if (!wantListening) return
            wantListening = false
            restartJob?.cancel()
            restartJob = null
        }
        quietly("stt.stop") { stt?.stop() }
    }

    /** [delayMillis] 뒤 인식을 요청한다. 듣기가 꺼져 있거나 말하는 중이면 아무 일도 하지 않는다. */
    private fun scheduleListen(delayMillis: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMillis)
            val engine = synchronized(lock) {
                if (wantListening && !speaking) {
                    sessionOpen = true
                    stt
                } else {
                    null
                }
            } ?: return@launch
            guarded("stt.request") { engine.request() }
        }
        val accepted = synchronized(lock) {
            if (!wantListening || speaking) {
                false
            } else {
                restartJob?.cancel()
                restartJob = job
                true
            }
        }
        if (accepted) job.start() else job.cancel()
    }

    /** 인식 세션 하나가 끝났다. 같은 세션의 두 번째 종료 신호(최종 결과 뒤의 `onEndedRecognition` 등)는 무시한다. */
    private fun onSessionEnded(restartDelayMillis: Long) {
        val first = synchronized(lock) {
            val open = sessionOpen
            sessionOpen = false
            open
        }
        if (first) scheduleListen(restartDelayMillis)
    }

    private fun onListenError(errorType: SttErrorType) {
        // TIMEOUT은 아무 말도 없어서 끝난 것으로 보고 고장으로 세지 않는다(수면 중에는 대부분 조용하다).
        // 이 해석은 문서에 없는 가정이다(sdk-notes 11절 8번, 16절 11번). 가정이 틀려 곧바로 TIMEOUT이
        // 되풀이되더라도 빠르게 돌지 않도록 오류 때와 같은 간격으로 다시 듣는다.
        if (errorType == SttErrorType.TIMEOUT) {
            onSessionEnded(ERROR_RESTART_DELAY_MILLIS)
            return
        }
        Log.w(TAG, "STT 오류: $errorType")
        val giveUp = synchronized(lock) {
            if (++listenErrors >= MAX_LISTEN_ERRORS) {
                wantListening = false
                sessionOpen = false
                restartJob?.cancel()
                restartJob = null
                true
            } else {
                false
            }
        }
        if (giveUp) {
            // 말하기는 계속 쓸 수 있으므로 available은 그대로 둔다.
            _canListen.value = false
            Log.w(TAG, "STT 오류가 $MAX_LISTEN_ERRORS 번 이어져 듣기를 멈춥니다")
        } else {
            onSessionEnded(ERROR_RESTART_DELAY_MILLIS)
        }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onUpdated(stt: String, completed: Boolean) {
            val text = stt.trim()
            val deliver = synchronized(lock) {
                if (text.isNotEmpty()) {
                    // 마이크와 인식기가 동작한다는 증거
                    listenErrors = 0
                    _canListen.value = true
                }
                completed && text.isNotEmpty() && wantListening && !speaking
            }
            if (!completed) return
            if (deliver) _recognized.tryEmit(text)
            onSessionEnded(RESTART_DELAY_MILLIS)
        }

        override fun onEndedRecognition() = onSessionEnded(RESTART_DELAY_MILLIS)

        override fun onError(errorType: SttErrorType) = onListenError(errorType)
    }

    // ================================================================ 도우미

    /** SDK 호출을 감싼다. 예외가 나면 음성을 끄고 false를 돌려준다. */
    private inline fun guarded(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        Log.w(TAG, "$what 실패", t)
        disable("$what 예외")
        false
    }

    /** 정리 단계의 SDK 호출. 실패해도 상태를 바꾸지 않는다. */
    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$what 실패", t)
        }
    }

    /** 음성을 쓸 수 없게 됐다. 이후 말하기·듣기는 아무 일도 하지 않고 앱은 터치로만 동작한다. */
    private fun disable(reason: String) {
        Log.w(TAG, "음성을 끕니다: $reason")
        val pending = synchronized(lock) {
            wantListening = false
            sessionOpen = false
            restartJob?.cancel()
            restartJob = null
            utterance
        }
        _available.value = false
        _canListen.value = false
        pending?.complete(true)
    }

    private fun speakTimeoutMillis(text: String): Long =
        (SPEAK_BASE_TIMEOUT_MILLIS + text.length * SPEAK_TIMEOUT_PER_CHAR_MILLIS).coerceAtMost(SPEAK_MAX_TIMEOUT_MILLIS)

    private companion object {
        const val TAG = "ChargeNapVoice"

        /** sdk-notes 1.3 */
        const val SERVICE_PACKAGE = "ai.pleos.playground.caas"

        /** sdk-notes 1.2: TTS·STT AAR 매니페스트의 `<queries>` 액션 */
        const val SPEECH_START_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"

        const val INIT_TIMEOUT_MILLIS = 5_000L
        const val SPEAK_BASE_TIMEOUT_MILLIS = 3_000L
        const val SPEAK_TIMEOUT_PER_CHAR_MILLIS = 250L
        const val SPEAK_MAX_TIMEOUT_MILLIS = 15_000L
        const val STOP_SETTLE_MILLIS = 400L
        const val RESTART_DELAY_MILLIS = 300L
        const val ERROR_RESTART_DELAY_MILLIS = 1_500L
        const val MAX_LISTEN_ERRORS = 5
        const val MAX_SPEAK_ERRORS = 3
    }
}
