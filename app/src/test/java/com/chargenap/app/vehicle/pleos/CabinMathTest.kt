package com.chargenap.app.vehicle.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinMathTest {

    // ---- 등받이 목표 계산 ----

    @Test
    fun reclineTarget_isPercentOfRangeFromUpright() {
        assertEquals(100, reclineTargetPosition(min = 0, max = 100, reclineLevel = 0))
        assertEquals(40, reclineTargetPosition(min = 0, max = 100, reclineLevel = 60))
        assertEquals(0, reclineTargetPosition(min = 0, max = 100, reclineLevel = 100))
    }

    @Test
    fun reclineTarget_arbitraryRange_includingNegativeMin() {
        // 범위 -20..60(폭 80)의 60% = 48 → 60 - 48 = 12
        assertEquals(12, reclineTargetPosition(min = -20, max = 60, reclineLevel = 60))
        // 범위 3..10(폭 7)의 60% = 4.2 → 반올림 4 → 10 - 4 = 6
        assertEquals(6, reclineTargetPosition(min = 3, max = 10, reclineLevel = 60))
    }

    @Test
    fun reclineTarget_levelIsClampedTo0to100() {
        assertEquals(0, reclineTargetPosition(min = 0, max = 100, reclineLevel = 250))
        assertEquals(100, reclineTargetPosition(min = 0, max = 100, reclineLevel = -10))
    }

    @Test
    fun reclineTarget_degenerateRange_isNull() {
        assertNull(reclineTargetPosition(min = 5, max = 5, reclineLevel = 60))
        assertNull(reclineTargetPosition(min = 10, max = 0, reclineLevel = 60))
    }

    @Test
    fun reclineTarget_hugeRange_doesNotOverflow() {
        assertEquals(Int.MIN_VALUE, reclineTargetPosition(min = Int.MIN_VALUE, max = Int.MAX_VALUE, reclineLevel = 100))
        assertEquals(-1, reclineTargetPosition(min = Int.MIN_VALUE, max = Int.MAX_VALUE, reclineLevel = 50))
    }

    @Test
    fun planRecline_movesBackwardToTarget() {
        val plan = planRecline(min = 0, max = 100, current = 90, reclineLevel = 60)
        assertEquals(SeatMovePlan(start = 90, target = 40, direction = SeatDirection.RECLINE, tolerance = 1), plan)
    }

    @Test
    fun planRecline_levelZero_doesNotMove() {
        assertNull(planRecline(min = 0, max = 100, current = 90, reclineLevel = 0))
        assertNull(planRecline(min = 0, max = 100, current = 90, reclineLevel = -5))
    }

    @Test
    fun planRecline_alreadyReclinedPastTarget_doesNotMove() {
        // 목표 40인데 이미 30까지 누워 있다. 수면 프리셋은 세우는 방향으로 움직이지 않는다
        assertNull(planRecline(min = 0, max = 100, current = 30, reclineLevel = 60))
        assertNull(planRecline(min = 0, max = 100, current = 40, reclineLevel = 60))
    }

    @Test
    fun planRecline_withinTolerance_doesNotMove() {
        // 범위 0..1000 → 허용 오차 10. 목표 400, 현재 405
        assertNull(planRecline(min = 0, max = 1000, current = 405, reclineLevel = 60))
        assertNotNull(planRecline(min = 0, max = 1000, current = 420, reclineLevel = 60))
    }

    @Test
    fun planRecline_degenerateRange_doesNotMove() {
        assertNull(planRecline(min = 7, max = 7, current = 7, reclineLevel = 60))
        assertNull(planRecline(min = 10, max = 0, current = 5, reclineLevel = 60))
    }

    @Test
    fun planRaise_movesForwardToSnapshot() {
        val plan = planRaise(min = 0, max = 100, current = 40, snapshotPosition = 90)
        assertEquals(SeatMovePlan(start = 40, target = 90, direction = SeatDirection.RAISE, tolerance = 1), plan)
    }

    @Test
    fun planRaise_alreadyAtOrAboveSnapshot_doesNotMove() {
        // 복구는 눕히는 방향으로 움직이지 않는다
        assertNull(planRaise(min = 0, max = 100, current = 90, snapshotPosition = 90))
        assertNull(planRaise(min = 0, max = 100, current = 95, snapshotPosition = 90))
    }

    @Test
    fun planRaise_snapshotOutsideRange_isClamped() {
        assertEquals(100, planRaise(min = 0, max = 100, current = 40, snapshotPosition = 140)?.target)
    }

    @Test
    fun planRaise_degenerateRange_doesNotMove() {
        assertNull(planRaise(min = 5, max = 5, current = 5, snapshotPosition = 5))
    }

    @Test
    fun plan_reachedAndMovedAway() {
        val recline = SeatMovePlan(start = 90, target = 40, direction = SeatDirection.RECLINE, tolerance = 1)
        assertFalse(recline.reached(41))
        assertTrue(recline.reached(40))
        assertTrue(recline.reached(35))
        assertFalse(recline.movedAway(80)) // 목표 쪽으로 움직임
        assertFalse(recline.movedAway(91)) // 허용 오차 안
        assertTrue(recline.movedAway(92)) // 반대 방향

        val raise = SeatMovePlan(start = 40, target = 90, direction = SeatDirection.RAISE, tolerance = 0)
        assertTrue(raise.reached(90))
        assertFalse(raise.reached(89))
        assertFalse(raise.movedAway(41))
        assertTrue(raise.movedAway(39))
    }

    @Test
    fun backrestTolerance_isOnePercent_zeroForNarrowOrInvalidRange() {
        assertEquals(10, backrestTolerance(0, 1000))
        assertEquals(1, backrestTolerance(0, 100))
        assertEquals(0, backrestTolerance(0, 99))
        assertEquals(0, backrestTolerance(5, 5))
    }

    // ---- 풍량 변환 ----

    @Test
    fun fanSpeed_0to8_skipsOff_andSpreadsOver1to8() {
        assertEquals(listOf(1, 3, 5, 6, 8), (1..5).map { vehicleFanSpeed(it, min = 0, max = 8) })
    }

    @Test
    fun fanSpeed_1to5_isIdentity() {
        assertEquals(listOf(1, 2, 3, 4, 5), (1..5).map { vehicleFanSpeed(it, min = 1, max = 5) })
    }

    @Test
    fun fanSpeed_narrowRange() {
        assertEquals(listOf(1, 2, 2, 3, 3), (1..5).map { vehicleFanSpeed(it, min = 1, max = 3) })
        assertEquals(listOf(2, 2, 2, 2, 2), (1..5).map { vehicleFanSpeed(it, min = 2, max = 2) })
    }

    @Test
    fun fanSpeed_levelOutOfRange_isClamped() {
        assertEquals(1, vehicleFanSpeed(0, min = 0, max = 8))
        assertEquals(1, vehicleFanSpeed(-3, min = 0, max = 8))
        assertEquals(8, vehicleFanSpeed(9, min = 0, max = 8))
    }

    @Test
    fun fanSpeed_noUsableStep_isNull() {
        assertNull(vehicleFanSpeed(3, min = 0, max = 0)) // 꺼짐밖에 없다
        assertNull(vehicleFanSpeed(3, min = 5, max = 2)) // 잘못된 범위
    }

    @Test
    fun fanLevelFromVehicle_picksNearestLevel() {
        assertEquals(1, fanLevelFromVehicle(0, min = 0, max = 8))
        assertEquals(1, fanLevelFromVehicle(1, min = 0, max = 8))
        assertEquals(2, fanLevelFromVehicle(3, min = 0, max = 8))
        assertEquals(3, fanLevelFromVehicle(5, min = 0, max = 8))
        assertEquals(5, fanLevelFromVehicle(8, min = 0, max = 8))
        assertNull(fanLevelFromVehicle(3, min = 0, max = 0))
    }

    // ---- 온도 ----

    @Test
    fun clampTemperature_within16to30() {
        assertEquals(23f, clampTemperature(23f, min = 16f, max = 30f)!!, 0.001f)
        assertEquals(16f, clampTemperature(12f, min = 16f, max = 30f)!!, 0.001f)
        assertEquals(30f, clampTemperature(35.5f, min = 16f, max = 30f)!!, 0.001f)
    }

    @Test
    fun clampTemperature_snapsToStep() {
        assertEquals(22.5f, clampTemperature(22.3f, min = 16f, max = 30f, step = 0.5f)!!, 0.001f)
        assertEquals(22f, clampTemperature(22.2f, min = 16f, max = 30f, step = 0.5f)!!, 0.001f)
        assertEquals(23f, clampTemperature(22.6f, min = 16f, max = 30f, step = 1f)!!, 0.001f)
        // 눈금이 0 이하면 무시한다
        assertEquals(22.3f, clampTemperature(22.3f, min = 16f, max = 30f, step = 0f)!!, 0.001f)
    }

    @Test
    fun clampTemperature_degenerateRange_returnsThatValue() {
        assertEquals(21f, clampTemperature(25f, min = 21f, max = 21f, step = 0.5f)!!, 0.001f)
    }

    @Test
    fun clampTemperature_invalidInput_isNull() {
        assertNull(clampTemperature(23f, min = 30f, max = 16f))
        assertNull(clampTemperature(Float.NaN, min = 16f, max = 30f))
    }

    @Test
    fun sameTemperature_allowsHalfStep() {
        assertTrue(sameTemperature(22.5f, 22.5f, step = 0.5f))
        assertTrue(sameTemperature(22.5f, 22.7f, step = 0.5f))
        assertFalse(sameTemperature(22.5f, 23f, step = 0.5f))
        assertTrue(sameTemperature(22.5f, 23f, step = null)) // 눈금을 모르면 0.5℃까지
        assertFalse(sameTemperature(22f, 23f, step = null))
    }

    @Test
    fun wakePreset_isTwoDegreesCooler_andTwoLevelsStronger() {
        assertEquals(21f, wakeTemperature(23f), 0.001f)
        assertEquals(4, wakeFanLevel(2))
        assertEquals(5, wakeFanLevel(4))
        assertEquals(5, wakeFanLevel(5))
        assertEquals(3, wakeFanLevel(0)) // 범위 밖 입력은 1로 본 뒤 +2
    }

    @Test
    fun wakeTemperature_isClampedByVehicleRange() {
        assertEquals(16f, clampTemperature(wakeTemperature(17f), min = 16f, max = 30f, step = 0.5f)!!, 0.001f)
    }

    // ---- 도어 ----

    @Test
    fun isDoorOpen_comparesWithClosedPosition() {
        assertEquals(false, isDoorOpen(0, closedPosition = 0))
        assertEquals(true, isDoorOpen(1, closedPosition = 0))
        assertEquals(true, isDoorOpen(7, closedPosition = 0))
        assertEquals(false, isDoorOpen(2, closedPosition = 2))
        assertNull(isDoorOpen(null, closedPosition = 0))
    }

    @Test
    fun anyDoorOpen_trueIfAnyOpen_nullIfUnknownAndNoneOpen() {
        assertNull(anyDoorOpen(emptyList()))
        assertEquals(false, anyDoorOpen(listOf(false, false, false, false)))
        assertEquals(true, anyDoorOpen(listOf(false, true, false, false)))
        assertEquals(true, anyDoorOpen(listOf(null, true)))
        assertNull(anyDoorOpen(listOf(false, null)))
        assertNull(anyDoorOpen(listOf(null, null)))
    }

    // ---- 주행 가능 거리 ----

    @Test
    fun rangeKm_convertsMetersToWholeKm() {
        assertEquals(312, rangeKmFromMeters(312_000f))
        assertEquals(0, rangeKmFromMeters(999f))
        assertEquals(1, rangeKmFromMeters(1_999f))
        assertNull(rangeKmFromMeters(null))
        assertNull(rangeKmFromMeters(-1f))
        assertNull(rangeKmFromMeters(Float.NaN))
    }
}
