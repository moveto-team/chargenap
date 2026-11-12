package com.chargenap.app

import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.WakeReason
import com.chargenap.app.vehicle.CabinApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRulesTest {

    @Test
    fun monotonicClock_ignoresWallClockJumps() {
        var wall = 1_800_000_000_000L
        var nanos = 5_000_000_000L
        val clock = MonotonicWallClock(wallMillis = { wall }, monotonicNanos = { nanos })
        assertEquals(1_800_000_000_000L, clock.nowMillis())

        nanos += 60_000L * 1_000_000      // 1분 흐름
        wall -= 30 * 60_000L              // 그사이 기기 시각이 30분 뒤로 맞춰짐
        assertEquals(1_800_000_060_000L, clock.nowMillis())

        wall += 2 * 60 * 60_000L          // 이번에는 2시간 앞으로
        nanos += 1_000L * 1_000_000
        assertEquals(1_800_000_061_000L, clock.nowMillis())
    }

    @Test
    fun foregroundService_onlyWhileSessionInProgress() {
        val report = SafetyReport(CheckResult.PASS, CheckResult.SKIPPED, CheckResult.PASS, CheckResult.PASS)
        val inactive = listOf(
            NapSessionState.Idle,
            NapSessionState.Offer(Recommendation.Nap(20, 27)),
        )
        val active = listOf(
            NapSessionState.SafetyCheck(20, SessionMode.NAP, report, rearSeatQuestionPending = true, doorConfirmPending = false),
            NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true),
            NapSessionState.Napping(0, 1_200_000, 20, SessionMode.NAP, timerOnly = false, cabin = CabinApplyResult.NOTHING),
            NapSessionState.Waking(WakeReason.TIMER),
            NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null),
        )
        inactive.forEach { assertFalse("$it", it.needsForegroundService()) }
        active.forEach { assertTrue("$it", it.needsForegroundService()) }
    }
}
