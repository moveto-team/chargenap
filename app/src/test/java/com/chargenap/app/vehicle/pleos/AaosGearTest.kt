package com.chargenap.app.vehicle.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AAOS `GEAR_SELECTION` 값 해석. 상수 값은 `android.car.VehicleGear`(platforms/android-35/optional/android.car.jar,
 * docs/sdk-notes.md 13절): GEAR_UNKNOWN = 0, GEAR_NEUTRAL = 1, GEAR_REVERSE = 2, GEAR_PARK = 4, GEAR_DRIVE = 8.
 * 테스트 클래스패스에 android.car가 없을 수 있어 값으로 적는다.
 */
class AaosGearTest {

    @Test
    fun gearUnknown_isNotKnown() {
        assertNull("GEAR_UNKNOWN(0)은 \"P 아님\"이 아니라 \"모름\"이다", aaosGearInPark(0))
    }

    @Test
    fun park_isInPark() {
        assertEquals(true, aaosGearInPark(4))
    }

    @Test
    fun otherGears_areNotInPark() {
        for (gear in listOf(1, 2, 8, 16)) assertEquals("gear=$gear", false, aaosGearInPark(gear))
    }
}
