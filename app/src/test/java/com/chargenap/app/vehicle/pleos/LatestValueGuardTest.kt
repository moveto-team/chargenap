package com.chargenap.app.vehicle.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LatestValueGuardTest {

    private val guard = LatestValueGuard()
    private var value = "초기"

    @Test
    fun readStartedBeforePush_doesNotOverwritePushedValue() {
        val ticket = guard.beginRead("gear")              // 조회 시작(이때 차량은 P)
        guard.push("gear") { value = "D" }                // 그사이 리스너: D
        val written = guard.completeRead(ticket) { value = "P" }  // 오래된 조회 결과

        assertFalse(written)
        assertEquals("D", value)
    }

    @Test
    fun readStartedAfterPush_isWritten() {
        guard.push("door") { value = "열림" }
        val ticket = guard.beginRead("door")
        assertTrue(guard.completeRead(ticket) { value = "닫힘" })
        assertEquals("닫힘", value)
    }

    @Test
    fun olderRead_finishingLate_doesNotOverwriteNewerRead() {
        val older = guard.beginRead("battery")
        val newer = guard.beginRead("battery")
        assertTrue(guard.completeRead(newer) { value = "51" })
        assertFalse(guard.completeRead(older) { value = "50" })
        assertEquals("51", value)
    }

    @Test
    fun olderRead_finishingFirst_isStillOverwrittenByNewerRead() {
        val older = guard.beginRead("battery")
        val newer = guard.beginRead("battery")
        assertTrue(guard.completeRead(older) { value = "50" })
        assertTrue(guard.completeRead(newer) { value = "51" })
        assertEquals("51", value)
    }

    @Test
    fun keysAreIndependent() {
        val gearRead = guard.beginRead("gear")
        guard.push("door") { }
        assertTrue(guard.completeRead(gearRead) { value = "P" })
    }

    @Test
    fun pushDuringReadCompletion_waitsAndWins() {
        // 판정과 쓰기 사이에 리스너가 끼어들 수 없다: 조회 결과를 쓰는 동안 온 리스너 값은 그 뒤에 쓰인다
        val ticket = guard.beginRead("gear")
        val pushed = CountDownLatch(1)
        var pusher: Thread? = null
        guard.completeRead(ticket) {
            pusher = Thread {
                guard.push("gear") { value = "D" }
                pushed.countDown()
            }.also { it.start() }
            assertFalse("락을 쥔 동안에는 리스너가 쓰지 못한다", pushed.await(100, TimeUnit.MILLISECONDS))
            value = "P"
        }
        pusher!!.join(2_000)
        assertEquals("D", value)
    }
}
