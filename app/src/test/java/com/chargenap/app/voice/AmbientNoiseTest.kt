package com.chargenap.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** 백색소음 표본 생성(순수 함수) 검증. `AudioTrack` 재생은 기기에서만 확인할 수 있다. */
class AmbientNoiseTest {

    private val sampleRate = 22_050

    private fun generate(seed: Long, count: Int, chunk: Int = sampleRate / 10): FloatArray {
        val generator = SoftNoiseGenerator(seed)
        val out = FloatArray(count)
        val buffer = FloatArray(chunk)
        var offset = 0
        while (offset < count) {
            generator.fill(buffer)
            val n = minOf(chunk, count - offset)
            buffer.copyInto(out, offset, 0, n)
            offset += n
        }
        return out
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    @Test
    fun samples_stayInRange() {
        for (seed in 1L..5L) {
            val samples = generate(seed, sampleRate * 10)
            assertTrue(samples.all { it >= -1f && it <= 1f && !it.isNaN() })
        }
    }

    @Test
    fun samples_haveNoDcOffset() {
        for (seed in 1L..5L) {
            val samples = generate(seed, sampleRate * 10)
            val mean = samples.sumOf { it.toDouble() } / samples.size
            assertTrue("평균이 0에 가까워야 한다(seed=$seed): $mean", abs(mean) < 0.02)
            // 1초 단위로 봐도 한쪽으로 쏠리지 않는다.
            for (second in 0 until 10) {
                val slice = samples.copyOfRange(second * sampleRate, (second + 1) * sampleRate)
                val sliceMean = slice.sumOf { it.toDouble() } / slice.size
                assertTrue("1초 평균(seed=$seed, ${second}초): $sliceMean", abs(sliceMean) < 0.08)
            }
        }
    }

    @Test
    fun samples_areAudibleButSoft_andRarelyClip() {
        val samples = generate(seed = 7, count = sampleRate * 10)
        val level = rms(samples)
        assertTrue("소리가 난다: $level", level > 0.05)
        assertTrue("너무 크지 않다: $level", level < 0.5)
        val clipped = samples.count { abs(it) >= 1f }
        assertTrue("잘린 표본이 거의 없다: $clipped", clipped < samples.size / 1_000)
    }

    @Test
    fun samples_areSmootherThanWhiteNoise() {
        val samples = generate(seed = 11, count = sampleRate * 5)
        // 이웃 표본의 차이가 작을수록 저음 위주의 부드러운 소리다. 백색소음이면 차이의 RMS가 신호 RMS의 약 1.4배다.
        val diffs = FloatArray(samples.size - 1) { samples[it + 1] - samples[it] }
        assertTrue(rms(diffs) < rms(samples) * 0.5)
    }

    @Test
    fun sameSeed_sameSamples_andChunkSizeDoesNotMatter() {
        val a = generate(seed = 42, count = 5_000, chunk = 2_205)
        val b = generate(seed = 42, count = 5_000, chunk = 2_205)
        val c = generate(seed = 42, count = 5_000, chunk = 300)
        val other = generate(seed = 43, count = 5_000, chunk = 2_205)
        assertArrayEquals(a, b, 0f)
        assertArrayEquals("이어서 불러도 끊기지 않는다", a, c, 0f)
        assertFalse(a.contentEquals(other))
    }

    // ---------------------------------------------------------------- PCM 변환과 페이드

    @Test
    fun writePcm16_scalesAndClips() {
        val src = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 2f, -2f)
        val dst = ShortArray(src.size)
        writePcm16(src, dst, gainFrom = 1f, gainTo = 1f)
        assertEquals(0, dst[0].toInt())
        assertEquals(16_383, dst[1].toInt())
        assertEquals(-16_383, dst[2].toInt())
        assertEquals(32_767, dst[3].toInt())
        assertEquals(-32_767, dst[4].toInt())
        assertEquals("범위를 넘으면 잘라 낸다", 32_767, dst[5].toInt())
        assertEquals(-32_767, dst[6].toInt())
    }

    @Test
    fun writePcm16_zeroGain_isSilent() {
        val src = generate(seed = 3, count = 2_205)
        val dst = ShortArray(src.size) { 123 }
        writePcm16(src, dst, gainFrom = 0f, gainTo = 0f)
        assertTrue(dst.all { it.toInt() == 0 })
    }

    @Test
    fun writePcm16_fadesLinearlyAcrossBuffer() {
        val src = FloatArray(1_000) { 1f }
        val fadeIn = ShortArray(src.size)
        writePcm16(src, fadeIn, gainFrom = 0f, gainTo = 1f)
        assertEquals(0, fadeIn.first().toInt())
        assertTrue(fadeIn.toList().zipWithNext().all { (a, b) -> b >= a })
        assertEquals(16_383.0, fadeIn[500].toDouble(), 40.0)
        assertTrue(fadeIn.last() > 32_000)

        val fadeOut = ShortArray(src.size)
        writePcm16(src, fadeOut, gainFrom = 1f, gainTo = 0f)
        assertEquals(32_767, fadeOut.first().toInt())
        assertTrue(fadeOut.toList().zipWithNext().all { (a, b) -> b <= a })
        assertTrue(fadeOut.last() < 100)
    }

    @Test
    fun writePcm16_consecutiveBuffers_joinWithoutJump() {
        // 0.1초 버퍼 20개로 2초 페이드 인을 만들 때 버퍼 경계에서 음량이 튀지 않는다.
        val src = FloatArray(2_205) { 1f }
        val step = 1f / 20
        var gain = 0f
        var previousLast: Short? = null
        repeat(20) {
            val next = minOf(1f, gain + step)
            val dst = ShortArray(src.size)
            writePcm16(src, dst, gain, next)
            previousLast?.let { last -> assertTrue(abs(dst.first() - last) < 40) }
            previousLast = dst.last()
            gain = next
        }
        assertTrue(previousLast!! > 32_000)
    }

    @Test
    fun writePcm16_emptyOrShortDestination() {
        writePcm16(FloatArray(0), ShortArray(0), 0f, 1f)
        assertThrows(IllegalArgumentException::class.java) {
            writePcm16(FloatArray(10), ShortArray(5), 1f, 1f)
        }
    }
}
