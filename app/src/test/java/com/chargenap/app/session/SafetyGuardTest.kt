package com.chargenap.app.session

import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.NapPreset
import com.chargenap.app.vehicle.ParkState
import com.chargenap.app.vehicle.VehicleCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {

    private val parked = ParkState(gearInPark = true, parkingBrakeOn = true)
    private val allCaps = VehicleCapabilities(
        batteryPercent = true, chargingState = true, remainingChargeTime = false,
        seatRecline = true, climate = true, doorState = true, isDemo = true,
    )

    private fun report(
        park: ParkState = parked,
        charging: ChargingState = ChargingState.CHARGING,
        door: Boolean? = false,
    ) = SafetyGuard.evaluateStart(park, charging, door)

    // ---- 시작 판정 ----

    @Test
    fun allGood_passesEverything() {
        val report = report()
        assertEquals(SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS), report)
        assertFalse(report.blocksStart)
        assertFalse(report.mustWait)
    }

    @Test
    fun gear_trueIsPass_falseAndUnknownAreFail() {
        assertEquals(CheckResult.PASS, report(park = parked.copy(gearInPark = true)).gear)
        assertEquals(CheckResult.FAIL, report(park = parked.copy(gearInPark = false)).gear)
        assertEquals(CheckResult.FAIL, report(park = parked.copy(gearInPark = null)).gear)
        assertTrue(report(park = parked.copy(gearInPark = null)).blocksStart)
        assertTrue(report(park = parked.copy(gearInPark = false)).blocksStart)
    }

    @Test
    fun parkingBrake_unknownIsSkipped_offIsFailButOnlyWaits() {
        assertEquals(CheckResult.PASS, report(park = parked.copy(parkingBrakeOn = true)).parkingBrake)
        assertEquals(CheckResult.SKIPPED, report(park = parked.copy(parkingBrakeOn = null)).parkingBrake)

        val off = report(park = parked.copy(parkingBrakeOn = false))
        assertEquals(CheckResult.FAIL, off.parkingBrake)
        assertFalse(off.blocksStart)
        assertTrue(off.mustWait)

        val skipped = report(park = parked.copy(parkingBrakeOn = null))
        assertFalse(skipped.blocksStart)
        assertFalse(skipped.mustWait)
    }

    @Test
    fun charging_chargingOrConnectedPass_othersFail() {
        assertEquals(CheckResult.PASS, report(charging = ChargingState.CHARGING).charging)
        assertEquals(CheckResult.PASS, report(charging = ChargingState.CONNECTED_IDLE).charging)
        assertEquals(CheckResult.FAIL, report(charging = ChargingState.NOT_CONNECTED).charging)
        assertEquals(CheckResult.FAIL, report(charging = ChargingState.COMPLETE).charging)
        assertEquals(CheckResult.FAIL, report(charging = ChargingState.UNKNOWN).charging)
        assertTrue(report(charging = ChargingState.NOT_CONNECTED).blocksStart)
    }

    @Test
    fun doors_closedPass_openFailButOnlyWaits_unknownNeedsUserConfirm() {
        assertEquals(CheckResult.PASS, report(door = false).doors)

        val open = report(door = true)
        assertEquals(CheckResult.FAIL, open.doors)
        assertFalse(open.blocksStart)
        assertTrue(open.mustWait)

        val unknown = report(door = null)
        assertEquals(CheckResult.NEEDS_USER_CONFIRM, unknown.doors)
        assertFalse(unknown.blocksStart)
        assertFalse(unknown.mustWait)
    }

    // ---- 중단 판정 ----

    @Test
    fun abortReason_gearFalse_alwaysGearLeftPark() {
        assertEquals(AbortReason.GEAR_LEFT_PARK, SafetyGuard.abortReason(gearInPark = false, anyDoorOpen = true))
        assertEquals(AbortReason.GEAR_LEFT_PARK, SafetyGuard.abortReason(gearInPark = false, anyDoorOpen = null))
        assertEquals(AbortReason.GEAR_LEFT_PARK, SafetyGuard.abortReason(gearInPark = false, anyDoorOpen = false))
    }

    @Test
    fun abortReason_gearUnknown_onlyDoorOpenAborts() {
        assertEquals(AbortReason.DOOR_OPENED, SafetyGuard.abortReason(gearInPark = null, anyDoorOpen = true))
        assertNull(SafetyGuard.abortReason(gearInPark = null, anyDoorOpen = null))
        assertNull(SafetyGuard.abortReason(gearInPark = null, anyDoorOpen = false))
    }

    @Test
    fun abortReason_gearInPark_onlyDoorOpenAborts() {
        assertEquals(AbortReason.DOOR_OPENED, SafetyGuard.abortReason(gearInPark = true, anyDoorOpen = true))
        assertNull(SafetyGuard.abortReason(gearInPark = true, anyDoorOpen = null))
        assertNull(SafetyGuard.abortReason(gearInPark = true, anyDoorOpen = false))
    }

    // ---- 시트 이동 ----

    @Test
    fun rearSeatQuestion_onlyWhenSeatCouldMove() {
        val config = SessionConfig()
        assertTrue(SafetyGuard.shouldAskRearSeat(config, allCaps, SessionMode.NAP))
        assertFalse(SafetyGuard.shouldAskRearSeat(config, allCaps, SessionMode.BREATHING))
        assertFalse(SafetyGuard.shouldAskRearSeat(config.copy(seatControlEnabled = false), allCaps, SessionMode.NAP))
        assertFalse(SafetyGuard.shouldAskRearSeat(config, allCaps.copy(seatRecline = false), SessionMode.NAP))
        // 리클라인 설정이 0이면 움직일 일이 없으니 묻지 않는다.
        assertFalse(
            SafetyGuard.shouldAskRearSeat(config.copy(preset = NapPreset(0, 23f, 2)), allCaps, SessionMode.NAP),
        )
    }

    @Test
    fun napPreset_withoutConsent_hasReclineZero() {
        val preset = NapPreset(reclineLevel = 60, temperatureC = 23f, fanLevel = 2)
        assertEquals(preset, SafetyGuard.napPreset(preset, seatAllowed = true))
        assertEquals(preset.copy(reclineLevel = 0), SafetyGuard.napPreset(preset, seatAllowed = false))
    }

    @Test
    fun napPreset_clampsReclineToRange() {
        assertEquals(100, SafetyGuard.napPreset(NapPreset(250, 23f, 2), seatAllowed = true).reclineLevel)
        assertEquals(0, SafetyGuard.napPreset(NapPreset(-10, 23f, 2), seatAllowed = true).reclineLevel)
    }
}
