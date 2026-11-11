package com.chargenap.app.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 음성 인식(STT)과 음성 합성(TTS)을 감싸는 창구. 다른 계층은 이 인터페이스만 본다.
 *
 * 어떤 함수도 예외를 던지지 않는다. 음성을 쓸 수 없으면 [available]이 false이고 모든 함수가 아무 일도 하지 않는다.
 * 그때 앱은 터치만으로 동작한다.
 */
interface VoiceController {
    /** 초기화에 성공해 음성 안내를 쓸 수 있는가. 실행 중 오류가 나면 false로 내려간다. */
    val available: StateFlow<Boolean>

    /**
     * 듣기가 동작하는가. 말하기만 되는 환경(인식기 초기화 실패, 마이크 오류가 이어져 듣기를 포기함)에서는 false다.
     * 이때 호출하는 쪽은 말로 묻더라도 말로 된 대답을 기다리지 않는다. 기본 구현은 [available]과 같다.
     */
    val canListen: StateFlow<Boolean> get() = available

    /** 최종 인식 결과 문장. 중간 결과와 말하는 동안 들린 소리는 내보내지 않는다. */
    val recognized: Flow<String>

    /** 실패해도 예외 없이 false를 돌려준다. */
    suspend fun initialize(): Boolean

    /**
     * [text]를 말하고, 말하기가 끝나면(또는 실패·타임아웃·[stopSpeaking]) 돌아온다.
     * 말하는 동안은 듣지 않는다(자기 목소리 인식 방지). 호출한 코루틴이 취소되면 말하기를 멈춘다.
     */
    suspend fun speak(text: String)

    fun stopSpeaking()

    /** 듣기를 켠다. 인식 세션이 끝나면 내부에서 다시 시작해 [stopListening]까지 계속 듣는다. */
    fun startListening()

    fun stopListening()

    fun release()
}

/** 음성을 쓰지 않는 구현. 항상 `available = false`. */
class NoopVoiceController : VoiceController {
    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    override val recognized: Flow<String> = emptyFlow()

    override suspend fun initialize(): Boolean = false
    override suspend fun speak(text: String) = Unit
    override fun stopSpeaking() = Unit
    override fun startListening() = Unit
    override fun stopListening() = Unit
    override fun release() = Unit
}
