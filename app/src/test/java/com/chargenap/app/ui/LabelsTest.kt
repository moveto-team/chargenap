package com.chargenap.app.ui

import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.ui.aborted.AbortedPhase
import com.chargenap.app.ui.aborted.abortedPhase
import com.chargenap.app.ui.settings.formatTemperature
import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.ChargingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {

    private val allPass = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS)

    @Test
    fun failureKinds_groupsByPrefixWithoutDuplicates() {
        assertEquals(
            listOf(FailureKind.SEAT, FailureKind.CLIMATE),
            failureKinds(listOf("climate: rejected", "seat: timeout", "seat: 복구 오류")),
        )
        assertEquals(listOf(FailureKind.OTHER), failureKinds(listOf("알 수 없는 오류")))
        assertEquals(emptyList<FailureKind>(), failureKinds(emptyList()))
    }

    @Test
    fun formatClock_padsSeconds() {
        assertEquals("18:42", formatClock(18 * 60 + 42))
        assertEquals("0:05", formatClock(5))
        assertEquals("0:00", formatClock(-3))
        assertEquals("25:00", formatClock(25 * 60))
    }

    @Test
    fun formatTemperature_dropsTrailingZero() {
        assertEquals("23", formatTemperature(23f))
        assertEquals("23.5", formatTemperature(23.5f))
        assertEquals("18", formatTemperature(18f))
    }

    @Test
    fun startBlockReason_prefersGearNotParkThenCharging() {
        val gearFail = allPass.copy(gear = CheckResult.FAIL)
        val chargeFail = allPass.copy(charging = CheckResult.FAIL)
        val bothFail = gearFail.copy(charging = CheckResult.FAIL)

        assertNull(startBlockReason(allPass, ChargingState.CHARGING, gearInPark = true))
        assertEquals(StartBlockReason.GEAR_NOT_PARK, startBlockReason(bothFail, ChargingState.NOT_CONNECTED, gearInPark = false))
        assertEquals(StartBlockReason.GEAR_UNKNOWN, startBlockReason(gearFail, ChargingState.CHARGING, gearInPark = null))
        assertEquals(StartBlockReason.CHARGER_NOT_CONNECTED, startBlockReason(bothFail, ChargingState.NOT_CONNECTED, gearInPark = null))
        assertEquals(StartBlockReason.CHARGER_NOT_CONNECTED, startBlockReason(chargeFail, ChargingState.NOT_CONNECTED, gearInPark = true))
        assertEquals(StartBlockReason.CHARGE_COMPLETE, startBlockReason(chargeFail, ChargingState.COMPLETE, gearInPark = true))
        assertEquals(StartBlockReason.CHARGE_UNKNOWN, startBlockReason(chargeFail, ChargingState.UNKNOWN, gearInPark = true))
    }

    @Test
    fun abortedPhase_distinguishesDoorWaitFromRestoring() {
        val doorRestoring = NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null)
        val gearRestoring = NapSessionState.Aborted(AbortReason.GEAR_LEFT_PARK, restoring = true, restore = null)
        val done = NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = false, restore = CabinApplyResult.NOTHING)

        assertEquals(AbortedPhase.WAITING_DOOR, abortedPhase(doorRestoring, anyDoorOpen = true, overrideRequested = false))
        assertEquals(AbortedPhase.RESTORING, abortedPhase(doorRestoring, anyDoorOpen = false, overrideRequested = false))
        assertEquals(AbortedPhase.WAITING_DOOR_UNKNOWN, abortedPhase(doorRestoring, anyDoorOpen = null, overrideRequested = false))
        assertEquals(AbortedPhase.RESTORING, abortedPhase(doorRestoring, anyDoorOpen = true, overrideRequested = true))
        assertEquals(AbortedPhase.RESTORING, abortedPhase(gearRestoring, anyDoorOpen = true, overrideRequested = false))
        assertEquals(AbortedPhase.DONE, abortedPhase(done, anyDoorOpen = true, overrideRequested = false))
    }
}
