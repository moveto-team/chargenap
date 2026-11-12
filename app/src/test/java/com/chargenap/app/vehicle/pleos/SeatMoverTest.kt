package com.chargenap.app.vehicle.pleos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeatMoverTest {

    /**
     * 가상 등받이. 명령을 받으면 [tickMs]마다 [speed]만큼 움직인다.
     * 값이 작을수록 누운 것(AAOS 정의). [inverted]면 명령과 반대로 움직이는 차량을 흉내 낸다.
     */
    private class FakeBackrest(
        start: Int,
        private val min: Int = 0,
        private val max: Int = 100,
        private val speed: Int = 5,
        private val inverted: Boolean = false,
        private val tickMs: Long = 100,
    ) : BackrestPort {
        private val _position = MutableStateFlow<Int?>(start)
        override val position: StateFlow<Int?> = _position

        val commands = mutableListOf<SeatCommand>()
        var velocity = 0
            private set
        var frozen = false
        var rejectMove: String? = null
        var stopFailures = 0

        override suspend fun command(command: SeatCommand): String? {
            commands += command
            when (command) {
                SeatCommand.STOP -> {
                    if (stopFailures > 0) {
                        stopFailures--
                        return "stop rejected"
                    }
                    velocity = 0
                }
                SeatCommand.RECLINE -> {
                    rejectMove?.let { return it }
                    velocity = if (inverted) speed else -speed
                }
                SeatCommand.RAISE -> {
                    rejectMove?.let { return it }
                    velocity = if (inverted) -speed else speed
                }
            }
            return null
        }

        override suspend fun readPosition(): Int? = _position.value

        suspend fun run() {
            while (true) {
                delay(tickMs)
                if (!frozen && velocity != 0) {
                    _position.value = (_position.value!! + velocity).coerceIn(min, max)
                }
            }
        }
    }

    private fun TestScope.backrest(start: Int, speed: Int = 5, inverted: Boolean = false, max: Int = 100): FakeBackrest {
        val seat = FakeBackrest(start = start, speed = speed, inverted = inverted, max = max)
        backgroundScope.launch { seat.run() }
        return seat
    }

    private suspend fun TestScope.move(
        seat: FakeBackrest,
        plan: SeatMovePlan,
        abortReason: () -> String? = { null },
    ): SeatMoveResult = runSeatMove(seat, plan, SeatMoveLimits(), nowMs = { currentTime }, abortReason = abortReason)

    @Test
    fun recline_reachesTarget_thenStops() = runTest {
        val seat = backrest(start = 90)
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertTrue(result.moved)
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf(SeatCommand.RECLINE, SeatCommand.STOP), seat.commands)
        assertEquals(40, seat.position.value)
        assertEquals(0, seat.velocity)
    }

    @Test
    fun raise_reachesSnapshot_thenStops() = runTest {
        val seat = backrest(start = 40)
        val plan = planRaise(min = 0, max = 100, current = 40, snapshotPosition = 90)!!

        val result = move(seat, plan)

        assertTrue(result.moved)
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf(SeatCommand.RAISE, SeatCommand.STOP), seat.commands)
        assertEquals(90, seat.position.value)
    }

    @Test
    fun wrongDirection_stopsImmediately_andReports() = runTest {
        // BACKWARD를 보냈는데 값이 커진다 = 방향 가정이 틀린 차량
        val seat = backrest(start = 50, inverted = true)
        val plan = planRecline(min = 0, max = 100, current = 50, reclineLevel = 80)!!

        val result = move(seat, plan)

        assertEquals(listOf(SeatMoveErrors.WRONG_DIRECTION), result.errors)
        assertEquals(SeatCommand.STOP, seat.commands.last())
        assertEquals(0, seat.velocity)
        // 한 번 어긋난 것을 본 즉시 멈췄다(100 ms에 5씩 움직이는 시트)
        assertTrue("position=${seat.position.value}", seat.position.value!! <= 60)
        assertTrue(result.moved)
    }

    @Test
    fun noPositionChange_stopsAfterTwoSeconds_andReports() = runTest {
        val seat = backrest(start = 90).apply { frozen = true }
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertEquals(listOf(SeatMoveErrors.NO_RESPONSE), result.errors)
        assertFalse(result.moved)
        assertEquals(listOf(SeatCommand.RECLINE, SeatCommand.STOP), seat.commands)
        // 2초에 멈추고, 정지를 확인하는 데 한 번(500 ms) 더 본다
        assertEquals(2_000 + SeatMoveLimits().stopCheckIntervalMs, currentTime)
    }

    @Test
    fun seatStopsAtMechanicalLimitBeforeTarget_reportsNoResponseButMoved() = runTest {
        // 시트가 60에서 더 내려가지 않는다(목표 40)
        val seat = FakeBackrest(start = 90, min = 60)
        backgroundScope.launch { seat.run() }
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertEquals(listOf(SeatMoveErrors.NO_RESPONSE), result.errors)
        assertTrue(result.moved)
        assertEquals(SeatCommand.STOP, seat.commands.last())
    }

    @Test
    fun slowSeat_stopsAtTotalTimeout() = runTest {
        // 1초에 1칸씩만 움직여서 20초 안에 목표에 닿지 못한다(멈춘 것은 아니다)
        val seat = FakeBackrest(start = 1_000, max = 1_000, speed = 1, tickMs = 1_000)
        backgroundScope.launch { seat.run() }
        val plan = planRecline(min = 0, max = 1_000, current = 1_000, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertEquals(listOf(SeatMoveErrors.TIMED_OUT), result.errors)
        assertTrue(result.moved)
        assertEquals(SeatCommand.STOP, seat.commands.last())
        // 20초에 멈추고, 정지를 확인하는 데 한 번(500 ms) 더 본다
        val check = SeatMoveLimits().stopCheckIntervalMs
        assertTrue("elapsed=$currentTime", currentTime in (20_000 + check)..(20_100 + check))
    }

    @Test
    fun cancellation_sendsStop_beforePropagating() = runTest {
        val seat = backrest(start = 90)
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 100)!!
        val job = async { move(seat, plan) }

        advanceTimeBy(500)
        assertEquals(listOf(SeatCommand.RECLINE), seat.commands)
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(listOf(SeatCommand.RECLINE, SeatCommand.STOP), seat.commands)
        assertEquals(0, seat.velocity)
        val stoppedAt = seat.position.value
        advanceTimeBy(1_000)
        assertEquals(stoppedAt, seat.position.value) // 멈춘 채로 있다
    }

    @Test
    fun abortReason_stopsAndReports() = runTest {
        val seat = backrest(start = 90)
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 100)!!
        var doorOpen = false
        backgroundScope.launch {
            delay(450)
            doorOpen = true
        }

        val result = move(seat, plan) { if (doorOpen) "도어 열림" else null }

        assertEquals(listOf("도어 열림"), result.errors)
        assertTrue(result.moved)
        assertEquals(SeatCommand.STOP, seat.commands.last())
        assertTrue("position=${seat.position.value}", seat.position.value!! >= 60)
    }

    @Test
    fun moveCommandRejected_stillSendsStop() = runTest {
        val seat = backrest(start = 90).apply { rejectMove = "ERROR_DRIVING_RESTRICTED" }
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertEquals(listOf("${SeatMoveErrors.MOVE_REJECTED}: ERROR_DRIVING_RESTRICTED"), result.errors)
        assertFalse(result.moved)
        assertEquals(listOf(SeatCommand.RECLINE, SeatCommand.STOP), seat.commands)
    }

    @Test
    fun stopFailsOnce_isRetried() = runTest {
        val seat = backrest(start = 90).apply { stopFailures = 1 }
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf(SeatCommand.RECLINE, SeatCommand.STOP, SeatCommand.STOP), seat.commands)
        assertEquals(0, seat.velocity)
    }

    @Test
    fun stopFailsTwice_retriesUntilPositionSettles() = runTest {
        val seat = backrest(start = 90).apply { stopFailures = 2 }
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!

        val result = move(seat, plan)

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(
            listOf(SeatCommand.RECLINE, SeatCommand.STOP, SeatCommand.STOP, SeatCommand.STOP),
            seat.commands,
        )
        assertEquals(0, seat.velocity)
        val settled = seat.position.value
        advanceTimeBy(2_000)
        assertEquals("정지를 확인한 뒤에는 움직이지 않는다", settled, seat.position.value)
    }

    @Test
    fun stopNeverConfirmed_reportsError() = runTest {
        // 정지 명령을 늘 거절하고 계속 움직이는 시트(범위가 넓어 10초 안에 끝에 닿지 않는다)
        val seat = FakeBackrest(start = 1_000, max = 1_000).apply { stopFailures = Int.MAX_VALUE }
        backgroundScope.launch { seat.run() }
        val plan = planRecline(min = 0, max = 1_000, current = 1_000, reclineLevel = 2)!!

        val result = move(seat, plan)

        assertTrue(result.errors.toString(), SeatMoveErrors.STOP_UNCONFIRMED in result.errors)
        assertTrue(result.errors.toString(), "${SeatMoveErrors.STOP_FAILED}: stop rejected" in result.errors)
        val stops = seat.commands.count { it == SeatCommand.STOP }
        assertTrue("위치가 바뀌는 동안 정지 명령을 다시 보낸다: $stops", stops >= 15)
        assertTrue("10초 안팎에서 그만둔다: $currentTime", currentTime in 10_000..11_500)
    }

    @Test
    fun stopAccepted_butSeatKeepsMoving_resendsStop() = runTest {
        // 첫 정지 명령은 받아들였다고 하고도 움직임이 이어진다
        val seat = backrest(start = 90)
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)!!
        var ignoredStops = 1
        val port = object : BackrestPort by seat {
            override suspend fun command(command: SeatCommand): String? {
                if (command == SeatCommand.STOP && ignoredStops > 0) {
                    ignoredStops--
                    seat.commands += command
                    return null
                }
                return seat.command(command)
            }
        }

        val result = runSeatMove(port, plan, SeatMoveLimits(), nowMs = { currentTime })

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf(SeatCommand.RECLINE, SeatCommand.STOP, SeatCommand.STOP), seat.commands)
        assertEquals(0, seat.velocity)
    }

    @Test
    fun restoreLimits_waitLongerForSlowSeat() = runTest {
        // 2.5초마다 5씩 움직이는 느린 시트. 40 → 90은 25초가 걸린다
        val plan = planRaise(min = 0, max = 100, current = 40, snapshotPosition = 90)!!

        val forRecline = FakeBackrest(start = 40, tickMs = 2_500)
        backgroundScope.launch { forRecline.run() }
        val reclinePolicy = runSeatMove(forRecline, plan, SeatMoveLimits.RECLINE, nowMs = { currentTime })
        assertEquals("눕히기 정책은 2초 무응답에 멈춘다", listOf(SeatMoveErrors.NO_RESPONSE), reclinePolicy.errors)

        val forRestore = FakeBackrest(start = 40, tickMs = 2_500)
        backgroundScope.launch { forRestore.run() }
        val restorePolicy = runSeatMove(forRestore, plan, SeatMoveLimits.RESTORE, nowMs = { currentTime })
        assertEquals("복구 정책은 4초 무응답·45초 제한이라 끝까지 세운다", emptyList<String>(), restorePolicy.errors)
        assertEquals(90, forRestore.position.value)

        assertEquals(SeatMoveLimits(), SeatMoveLimits.RECLINE)
        assertEquals(4_000L, SeatMoveLimits.RESTORE.stallTimeoutMs)
        assertEquals(45_000L, SeatMoveLimits.RESTORE.totalTimeoutMs)
    }
}
