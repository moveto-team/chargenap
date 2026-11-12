package com.chargenap.app.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleGatewayTest {

    private val manual = FakeScenario(startPercent = 77, percentPerMinute = 1.0, autoStartChargingAfterMillis = null)

    @Test
    fun charging_increasesPercent_andCompletesAtTarget() = runTest {
        val fake = FakeVehicleGateway(manual, timeScale = 1.0, scope = backgroundScope)
        fake.start()
        fake.startCharging()
        assertEquals(ChargingState.CHARGING, fake.chargingState.value)

        advanceTimeBy(60_500)
        assertEquals(78, fake.batteryPercent.value)

        advanceTimeBy(120_000)
        assertEquals(80, fake.batteryPercent.value)
        assertEquals(ChargingState.COMPLETE, fake.chargingState.value)
    }

    @Test
    fun timeScale_speedsUpCharging() = runTest {
        val fake = FakeVehicleGateway(manual.copy(startPercent = 50), timeScale = 60.0, scope = backgroundScope)
        fake.start()
        fake.startCharging()
        advanceTimeBy(10_500)
        assertEquals(60, fake.batteryPercent.value)
    }

    @Test
    fun reportsRemainingTime_whenScenarioSaysSo() = runTest {
        val fake = FakeVehicleGateway(
            manual.copy(startPercent = 60, reportsRemainingTime = true), timeScale = 1.0, scope = backgroundScope,
        )
        fake.start()
        assertNull(fake.remainingChargeMinutes.value)
        fake.startCharging()
        assertEquals(20, fake.remainingChargeMinutes.value)
        assertTrue(fake.capabilities.value.remainingChargeTime)
    }

    @Test
    fun applyNap_thenRestore_returnsSeatToZero() = runTest {
        val fake = FakeVehicleGateway(manual, timeScale = 1.0, scope = backgroundScope)
        val snapshot = fake.captureCabinSnapshot()
        val before = fake.climate.value

        val applied = fake.applyNapPreset(NapPreset(reclineLevel = 60, temperatureC = 23f, fanLevel = 2))
        assertTrue(applied.seatApplied)
        assertTrue(applied.climateApplied)
        assertEquals(60, fake.seatReclineLevel.value)
        assertEquals(FakeClimate(23f, 2), fake.climate.value)

        val wake = fake.applyWakePreset()
        assertFalse(wake.seatApplied)
        assertEquals(FakeClimate(21f, 4), fake.climate.value)

        val restored = fake.restoreCabin(snapshot)
        assertTrue(restored.errors.isEmpty())
        assertEquals(0, fake.seatReclineLevel.value)
        assertEquals(before, fake.climate.value)
        assertEquals(listOf("snapshot", "applyNap(60,23.0,2)", "applyWake", "restore"), fake.commandLog)
    }

    @Test
    fun failSeat_reportsErrorWithoutThrowing() = runTest {
        val fake = FakeVehicleGateway(manual, timeScale = 1.0, scope = backgroundScope)
        fake.failSeat = true
        val result = fake.applyNapPreset(NapPreset(60, 23f, 2))
        assertFalse(result.seatApplied)
        assertTrue(result.seatFailed)
        assertTrue(result.climateApplied)
        assertEquals(0, fake.seatReclineLevel.value)
    }

    @Test
    fun reclineZero_doesNotMoveSeat() = runTest {
        val fake = FakeVehicleGateway(manual, timeScale = 1.0, scope = backgroundScope)
        val result = fake.applyNapPreset(NapPreset(reclineLevel = 0, temperatureC = 23f, fanLevel = 2))
        assertFalse(result.seatApplied)
        assertTrue(result.errors.isEmpty())
        assertEquals(0, fake.seatReclineLevel.value)
    }

    @Test
    fun cancelledApply_stopsSeatWhereItIs() = runTest {
        val fake = FakeVehicleGateway(manual, timeScale = 1.0, scope = backgroundScope)
        val job = launch { fake.applyNapPreset(NapPreset(100, 23f, 2)) }
        advanceTimeBy(600)
        job.cancel()
        runCurrent()
        val stoppedAt = fake.seatReclineLevel.value
        assertTrue("시트가 중간에서 멈춰야 한다: $stoppedAt", stoppedAt in 1..99)
        advanceTimeBy(5_000)
        assertEquals(stoppedAt, fake.seatReclineLevel.value)
    }

    @Test
    fun restartWhenNearlyFull_startChargingBeginsNewCharge() = runTest {
        val fake = FakeVehicleGateway(
            manual.copy(startPercent = 52, restartWhenNearlyFull = true), timeScale = 1.0, scope = backgroundScope,
        )
        fake.start()
        fake.startCharging()
        assertEquals(52, fake.batteryPercent.value)

        // 충전 중에는 다시 눌러도 잔량을 되돌리지 않는다
        fake.setBatteryPercent(70)
        fake.startCharging()
        assertEquals(70, fake.batteryPercent.value)

        // 다 찬 뒤 다시 시작하면 새 충전
        fake.completeCharging()
        assertEquals(ChargingState.COMPLETE, fake.chargingState.value)
        fake.startCharging()
        assertEquals(52, fake.batteryPercent.value)
        assertEquals(ChargingState.CHARGING, fake.chargingState.value)

        // 넉넉히 남았을 때 멈췄다 시작하면 이어서 충전
        fake.setBatteryPercent(60)
        fake.interruptCharging()
        fake.startCharging()
        assertEquals(60, fake.batteryPercent.value)
    }

    @Test
    fun withoutRestartFlag_startChargingAtTargetIsComplete() = runTest {
        val fake = FakeVehicleGateway(manual.copy(startPercent = 80), timeScale = 1.0, scope = backgroundScope)
        fake.startCharging()
        assertEquals(80, fake.batteryPercent.value)
        assertEquals(ChargingState.COMPLETE, fake.chargingState.value)
    }

    @Test
    fun unreadableGearAndDoor_areNull() = runTest {
        val fake = FakeVehicleGateway(manual, timeScale = 1.0, scope = backgroundScope)
        fake.setGearReadable(false)
        fake.setDoorReadable(false)
        assertNull(fake.parkState.value.gearInPark)
        assertNull(fake.anyDoorOpen.value)
    }
}
