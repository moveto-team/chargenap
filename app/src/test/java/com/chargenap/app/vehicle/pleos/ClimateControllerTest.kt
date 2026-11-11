package com.chargenap.app.vehicle.pleos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClimateControllerTest {

    /** 가상 공조. 기본 범위는 Virtual Console과 같은 온도 16..30 ℃(0.5 단위), 풍량 0..8. */
    private class FakeClimate(
        override val temperature: TemperatureSpec? = TemperatureSpec(min = 16f, max = 30f, step = 0.5f),
        override val fan: FanSpec? = FanSpec(min = 0, max = 8),
        override val powerControllable: Boolean = true,
        override val autoControllable: Boolean = true,
    ) : ClimatePort {
        var temperatureC: Float? = 22f
        var fanSpeed: Int? = 3
        var powerOn: Boolean? = true
        var autoOn: Boolean? = false

        /** 요청은 받지만 값이 바뀌지 않는 차량(sdk-notes 12.4의 "SET 완료 후에도 그대로" 사례). */
        var dropTemperatureWrites = false
        var dropFanWrites = false

        /** 오토 모드가 켜져 있으면 수동 풍량을 무시하는 차량. */
        var fanIgnoredWhileAuto = false

        /** 전원이 꺼져 있으면 다른 값을 읽지 못하는 차량. */
        var valuesHiddenWhenOff = false
        var rejectTemperature: String? = null

        val writes = mutableListOf<String>()

        private val hidden: Boolean get() = valuesHiddenWhenOff && powerOn == false

        override suspend fun readTemperature(): Float? = if (hidden) null else temperatureC
        override suspend fun readFan(): Int? = if (hidden) null else fanSpeed
        override suspend fun readPower(): Boolean? = powerOn
        override suspend fun readAuto(): Boolean? = if (hidden) null else autoOn

        override suspend fun writeTemperature(celsius: Float): String? {
            writes += "temperature=$celsius"
            rejectTemperature?.let { return it }
            if (!dropTemperatureWrites) temperatureC = celsius
            return null
        }

        override suspend fun writeFan(speed: Int): String? {
            writes += "fan=$speed"
            if (!dropFanWrites && !(fanIgnoredWhileAuto && autoOn == true)) fanSpeed = speed
            return null
        }

        override suspend fun writePower(on: Boolean): String? {
            writes += "power=$on"
            powerOn = on
            return null
        }

        override suspend fun writeAuto(on: Boolean): String? {
            writes += "auto=$on"
            autoOn = on
            return null
        }
    }

    // ---- 수면 프리셋 ----

    @Test
    fun apply_setsTemperatureAndConvertedFan() = runTest {
        val car = FakeClimate()
        val outcome = ClimateController(car).apply(temperatureC = 24f, fanLevel = 2)

        assertTrue(outcome.applied)
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(24f, car.temperatureC)
        assertEquals(3, car.fanSpeed) // 2단계 → 0..8에서 3. 이미 3이라 쓰지 않는다
        assertEquals(listOf("temperature=24.0"), car.writes)
    }

    @Test
    fun apply_powerOff_turnsPowerOnFirst() = runTest {
        val car = FakeClimate().apply { powerOn = false }
        val outcome = ClimateController(car).apply(temperatureC = 23f, fanLevel = 1)

        assertTrue(outcome.applied)
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(listOf("power=true", "temperature=23.0", "fan=1"), car.writes)
        assertEquals(true, car.powerOn)
    }

    @Test
    fun apply_clampsTemperatureToVehicleRange_andSnapsToStep() = runTest {
        val car = FakeClimate()
        ClimateController(car).apply(temperatureC = 35f, fanLevel = null)
        assertEquals(30f, car.temperatureC)

        ClimateController(car).apply(temperatureC = 22.3f, fanLevel = null)
        assertEquals(22.5f, car.temperatureC)
    }

    @Test
    fun apply_alreadyAtTarget_writesNothing_butCountsAsApplied() = runTest {
        val car = FakeClimate().apply {
            temperatureC = 23f
            fanSpeed = 5
        }
        val outcome = ClimateController(car).apply(temperatureC = 23f, fanLevel = 3)

        assertTrue(outcome.applied)
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(emptyList<String>(), car.writes)
    }

    @Test
    fun apply_valueNotReflected_reportsErrorPerItem() = runTest {
        val car = FakeClimate().apply { dropTemperatureWrites = true }
        val outcome = ClimateController(car).apply(temperatureC = 25f, fanLevel = 5)

        assertEquals(listOf("${ClimateErrors.NOT_REFLECTED} (${ClimateErrors.TEMPERATURE})"), outcome.errors)
        assertTrue(outcome.applied) // 풍량은 반영됐다
        assertEquals(8, car.fanSpeed)
        assertEquals(22f, car.temperatureC)
    }

    @Test
    fun apply_nothingReflected_isNotApplied() = runTest {
        val car = FakeClimate().apply {
            dropTemperatureWrites = true
            dropFanWrites = true
        }
        val outcome = ClimateController(car).apply(temperatureC = 25f, fanLevel = 5)

        assertFalse(outcome.applied)
        assertEquals(
            listOf(
                "${ClimateErrors.NOT_REFLECTED} (${ClimateErrors.TEMPERATURE})",
                "${ClimateErrors.NOT_REFLECTED} (${ClimateErrors.FAN})",
            ),
            outcome.errors,
        )
    }

    @Test
    fun apply_writeRejected_reportsReason() = runTest {
        val car = FakeClimate().apply { rejectTemperature = "ERROR_NOT_SUPPORTED_AREA" }
        val outcome = ClimateController(car).apply(temperatureC = 25f, fanLevel = null)

        assertFalse(outcome.applied)
        assertEquals(listOf("${ClimateErrors.TEMPERATURE} 설정 실패: ERROR_NOT_SUPPORTED_AREA"), outcome.errors)
    }

    @Test
    fun apply_fanIgnoredWhileAuto_turnsAutoOff_andRetries() = runTest {
        val car = FakeClimate().apply {
            autoOn = true
            fanIgnoredWhileAuto = true
        }
        val outcome = ClimateController(car).apply(temperatureC = null, fanLevel = 5)

        assertTrue(outcome.applied)
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(8, car.fanSpeed)
        assertEquals(false, car.autoOn)
        assertEquals(listOf("fan=8", "auto=false", "fan=8"), car.writes)
    }

    @Test
    fun apply_fanAcceptedWhileAuto_leavesAutoAlone() = runTest {
        val car = FakeClimate().apply { autoOn = true }
        val outcome = ClimateController(car).apply(temperatureC = null, fanLevel = 5)

        assertTrue(outcome.applied)
        assertEquals(true, car.autoOn)
        assertEquals(listOf("fan=8"), car.writes)
    }

    @Test
    fun apply_fanNotReflected_autoOff_reportsError() = runTest {
        val car = FakeClimate().apply { dropFanWrites = true }
        val outcome = ClimateController(car).apply(temperatureC = null, fanLevel = 5)

        assertFalse(outcome.applied)
        assertEquals(listOf("${ClimateErrors.NOT_REFLECTED} (${ClimateErrors.FAN})"), outcome.errors)
        assertEquals(listOf("fan=8"), car.writes)
    }

    @Test
    fun apply_onlyFanControllable_skipsTemperature() = runTest {
        val car = FakeClimate(temperature = null, powerControllable = false, autoControllable = false)
        val outcome = ClimateController(car).apply(temperatureC = 25f, fanLevel = 4)

        assertTrue(outcome.applied)
        assertEquals(listOf("fan=6"), car.writes)
    }

    // ---- 기상 프리셋 ----

    @Test
    fun wake_afterNap_isTwoDegreesCooler_andTwoLevelsStronger() = runTest {
        val car = FakeClimate()
        val controller = ClimateController(car)
        controller.apply(temperatureC = 23f, fanLevel = 2)

        val outcome = controller.apply(wakeTemperature(23f), wakeFanLevel(2))

        assertTrue(outcome.applied)
        assertEquals(21f, car.temperatureC)
        assertEquals(6, car.fanSpeed) // 4단계 → 0..8에서 6
    }

    @Test
    fun wakeFromCurrent_usesVehicleValuesAsBase() = runTest {
        val car = FakeClimate().apply {
            temperatureC = 17f
            fanSpeed = 3 // 2단계에 해당
        }
        val outcome = ClimateController(car).applyWakeFromCurrent()

        assertTrue(outcome.applied)
        assertEquals(16f, car.temperatureC) // 15 ℃는 범위 밖이라 16 ℃
        assertEquals(6, car.fanSpeed)
    }

    // ---- 스냅샷과 복구 ----

    @Test
    fun snapshot_capturesControllableValues() = runTest {
        val car = FakeClimate().apply {
            temperatureC = 21.5f
            fanSpeed = 4
            powerOn = true
            autoOn = true
        }
        assertEquals(ClimateSnapshot(21.5f, 4, powerOn = true, autoOn = true), ClimateController(car).snapshot())
    }

    @Test
    fun snapshot_nothingReadable_isNull() = runTest {
        val car = FakeClimate().apply {
            temperatureC = null
            fanSpeed = null
            powerOn = null
            autoOn = null
        }
        assertNull(ClimateController(car).snapshot())
    }

    @Test
    fun restore_returnsToSnapshotValues() = runTest {
        val car = FakeClimate()
        val controller = ClimateController(car)
        val snapshot = controller.snapshot()!!
        controller.apply(temperatureC = 25f, fanLevel = 5)
        car.writes.clear()

        val outcome = controller.restore(snapshot)

        assertTrue(outcome.applied)
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(22f, car.temperatureC)
        assertEquals(3, car.fanSpeed)
        assertEquals(true, car.powerOn)
        assertEquals(listOf("temperature=22.0", "fan=3"), car.writes)
    }

    @Test
    fun restore_powerWasOff_restoresValuesThenTurnsPowerOffLast() = runTest {
        val car = FakeClimate().apply { powerOn = false }
        val controller = ClimateController(car)
        val snapshot = controller.snapshot()!!
        controller.apply(temperatureC = 25f, fanLevel = 5)
        assertEquals(true, car.powerOn)
        car.writes.clear()

        val outcome = controller.restore(snapshot)

        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(listOf("temperature=22.0", "fan=3", "power=false"), car.writes)
        assertEquals(false, car.powerOn)
        assertEquals(22f, car.temperatureC)
        assertEquals(3, car.fanSpeed)
    }

    @Test
    fun restore_valuesHiddenWhilePowerOff_usesValuesReadRightAfterPowerOn() = runTest {
        val car = FakeClimate().apply {
            powerOn = false
            valuesHiddenWhenOff = true
        }
        val controller = ClimateController(car)
        val snapshot = controller.snapshot()!!
        assertEquals(ClimateSnapshot(temperatureC = null, fanSpeed = null, powerOn = false, autoOn = null), snapshot)

        controller.apply(temperatureC = 25f, fanLevel = 5)
        controller.apply(wakeTemperature(25f), wakeFanLevel(5)) // 기상 프리셋이 원래 값을 덮어쓰지 않아야 한다
        val outcome = controller.restore(snapshot)

        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(22f, car.temperatureC)
        assertEquals(3, car.fanSpeed)
        assertEquals(false, car.powerOn)
        assertEquals("power=false", car.writes.last())
    }

    @Test
    fun restore_autoWasOn_turnsAutoBackOn() = runTest {
        val car = FakeClimate().apply {
            autoOn = true
            fanIgnoredWhileAuto = true
        }
        val controller = ClimateController(car)
        val snapshot = controller.snapshot()!!
        controller.apply(temperatureC = 25f, fanLevel = 5)
        assertEquals(false, car.autoOn)

        val outcome = controller.restore(snapshot)

        assertTrue(outcome.applied)
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(true, car.autoOn)
        assertEquals(22f, car.temperatureC)
        assertEquals(3, car.fanSpeed)
        assertEquals("auto=true", car.writes.last())
    }

    @Test
    fun restore_valueNotReflected_reportsError() = runTest {
        val car = FakeClimate()
        val controller = ClimateController(car)
        val snapshot = controller.snapshot()!!
        controller.apply(temperatureC = 25f, fanLevel = 5)
        car.dropTemperatureWrites = true

        val outcome = controller.restore(snapshot)

        assertEquals(listOf("${ClimateErrors.NOT_REFLECTED} (${ClimateErrors.TEMPERATURE})"), outcome.errors)
        assertEquals(3, car.fanSpeed) // 나머지는 되돌렸다
        assertTrue(outcome.applied)
    }

    @Test
    fun restore_nothingChanged_writesNothing() = runTest {
        val car = FakeClimate()
        val controller = ClimateController(car)
        val snapshot = controller.snapshot()!!

        val outcome = controller.restore(snapshot)

        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(emptyList<String>(), car.writes)
    }
}
