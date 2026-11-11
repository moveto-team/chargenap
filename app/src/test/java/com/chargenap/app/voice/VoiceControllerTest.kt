package com.chargenap.app.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음성을 쓸 수 없는 환경에서 컨트롤러가 조용히 꺼지는지 본다.
 *
 * [PleosVoiceController]가 SDK를 실제로 부르는 경로(초기화 성공 이후)는 에뮬레이터에서만 확인할 수 있다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControllerTest {

    /** 음성 서비스 패키지를 찾을 수 없는 기기를 흉내 낸다. */
    private class NoSpeechServiceContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageManager(): PackageManager? = null
    }

    @Test
    fun noop_isNeverAvailable_andDoesNothing() = runTest {
        val voice = NoopVoiceController()
        val heard = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { voice.recognized.toList(heard) }

        assertFalse(voice.initialize())
        assertFalse(voice.available.value)
        assertFalse(voice.canListen.value)
        voice.speak("일어날 시간이에요.")
        voice.startListening()
        voice.stopListening()
        voice.stopSpeaking()
        voice.release()
        runCurrent()
        assertFalse(voice.available.value)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun pleos_withoutSpeechService_initializeReturnsFalseQuietly() = runTest {
        val voice = PleosVoiceController(NoSpeechServiceContext(), backgroundScope)
        val heard = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { voice.recognized.collect { heard += it } }
        assertFalse("초기화 전에는 쓸 수 없다", voice.available.value)

        assertFalse(voice.initialize())
        assertFalse(voice.available.value)
        assertFalse(voice.canListen.value)

        // 초기화에 실패한 뒤에는 어떤 호출도 예외 없이 아무 일도 하지 않는다.
        voice.speak("일어날 시간이에요.")
        voice.stopSpeaking()
        voice.startListening()
        voice.stopListening()
        runCurrent()
        assertTrue(heard.isEmpty())

        voice.release()
        voice.release()
        assertFalse("해제한 뒤 다시 초기화해도 조용히 실패한다", voice.initialize())
        assertFalse(voice.available.value)
    }
}
