package com.chargenap.app.voice

import kotlin.random.Random

/**
 * 부드러운 배경 소음 표본을 만든다. 순수 Kotlin(안드로이드 의존 없음)이라 단위 테스트할 수 있다.
 *
 * 백색소음을 새는 적분기(1차 저역 통과)에 넣어 브라운 계열로 만들고, 공기 소리가 나도록 백색소음을 조금 섞는다.
 * 적분기가 새기 때문에 값이 한쪽으로 쏠리지(DC) 않는다.
 */
class SoftNoiseGenerator(seed: Long = System.nanoTime()) {
    private val random = Random(seed)
    private var brown = 0f

    /** [out]을 -1.0..1.0 범위의 표본으로 채운다. 이어서 부르면 소리가 끊기지 않고 이어진다. */
    fun fill(out: FloatArray) {
        for (i in out.indices) {
            val white = random.nextFloat() * 2f - 1f
            brown = (brown + LEAK * white) / (1f + LEAK)
            out[i] = (brown * BROWN_GAIN + white * WHITE_MIX).coerceIn(-1f, 1f)
        }
    }

    private companion object {
        const val LEAK = 0.02f
        const val BROWN_GAIN = 3.5f
        const val WHITE_MIX = 0.02f
    }
}

/**
 * -1.0..1.0 표본을 16비트 PCM으로 바꾼다. 음량은 [gainFrom]에서 [gainTo]까지 버퍼 안에서 고르게 변한다(페이드).
 * 범위를 벗어나는 값은 잘라 낸다. [dst]는 [src]보다 짧으면 안 된다.
 */
fun writePcm16(src: FloatArray, dst: ShortArray, gainFrom: Float, gainTo: Float) {
    require(dst.size >= src.size) { "dst가 src보다 짧다" }
    val n = src.size
    if (n == 0) return
    val step = (gainTo - gainFrom) / n
    for (i in 0 until n) {
        val gain = (gainFrom + step * i).coerceIn(0f, 1f)
        val value = (src[i] * gain * Short.MAX_VALUE).toInt()
        dst[i] = value.coerceIn(-Short.MAX_VALUE, Short.MAX_VALUE.toInt()).toShort()
    }
}
