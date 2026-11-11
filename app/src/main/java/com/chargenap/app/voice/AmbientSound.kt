package com.chargenap.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/** 수면 중 배경 소음. 음성(STT·TTS)을 쓸 수 없어도 동작한다. */
interface AmbientSound {
    fun start()
    fun stop()
}

/**
 * `AudioTrack`으로 만들어 내는 백색소음 계열 배경음. 음원 파일이 없다.
 *
 * [start]하면 2초에 걸쳐 커지고, [stop]하면 1.5초에 걸쳐 작아진 뒤 트랙을 해제한다. 여러 번 불러도 안전하다.
 * 오디오 장치를 열지 못하면 조용히 아무 소리도 내지 않는다.
 *
 * @param volume 0.0..1.0. 미디어 음량 안에서의 상대 크기
 */
class AmbientSoundPlayer(volume: Float = DEFAULT_VOLUME) : AmbientSound {

    private val volume = volume.coerceIn(0f, 1f)
    private val lock = Any()
    private var worker: Worker? = null      // lock으로 보호

    override fun start() {
        synchronized(lock) {
            val current = worker
            if (current != null) {
                current.stopping = false    // 작아지는 중이었으면 다시 키운다
                return
            }
            try {
                worker = Worker().also {
                    it.isDaemon = true
                    it.start()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "배경음 스레드를 시작하지 못했습니다", t)
                worker = null
            }
        }
    }

    override fun stop() {
        synchronized(lock) { worker?.stopping = true }
    }

    private inner class Worker : Thread("ChargeNapAmbient") {
        var stopping = false                // lock으로 보호

        override fun run() {
            var track: AudioTrack? = null
            try {
                track = createTrack() ?: return
                track.play()
                val generator = SoftNoiseGenerator()
                val samples = FloatArray(CHUNK_SAMPLES)
                val pcm = ShortArray(CHUNK_SAMPLES)
                var gain = 0f
                while (true) {
                    val fadingOut = synchronized(lock) { stopping }
                    val next = if (fadingOut) maxOf(0f, gain - FADE_OUT_STEP) else minOf(1f, gain + FADE_IN_STEP)
                    generator.fill(samples)
                    writePcm16(samples, pcm, gain * volume, next * volume)
                    if (track.write(pcm, 0, pcm.size) <= 0) break
                    gain = next
                    if (gain <= 0f && finishIfStopping()) break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "배경음 재생 실패", t)
            } finally {
                synchronized(lock) { if (worker === this) worker = null }
                try {
                    track?.stop()
                } catch (t: Throwable) {
                    // 이미 멈췄거나 초기화되지 않은 트랙
                }
                try {
                    track?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "배경음 해제 실패", t)
                }
            }
        }

        /** 다 작아졌을 때, 그사이 다시 [start]가 불리지 않았으면 끝낸다. */
        private fun finishIfStopping(): Boolean = synchronized(lock) {
            if (stopping) {
                if (worker === this) worker = null
                true
            } else {
                false
            }
        }

        private fun createTrack(): AudioTrack? {
            val minBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) return null
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, CHUNK_SAMPLES * BYTES_PER_SAMPLE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            return track
        }
    }

    private companion object {
        const val TAG = "ChargeNapAmbient"
        const val DEFAULT_VOLUME = 0.3f
        const val SAMPLE_RATE = 22_050
        const val BYTES_PER_SAMPLE = 2

        /** 0.1초 분량. 페이드는 이 단위로 계산한다. */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val FADE_IN_STEP = 1f / 20      // 2초
        const val FADE_OUT_STEP = 1f / 15     // 1.5초
    }
}
