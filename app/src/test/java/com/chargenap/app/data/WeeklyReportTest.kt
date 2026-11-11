package com.chargenap.app.data

import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.WakeReason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WeeklyReportTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)
    private val now = millis(today.atTime(15, 0))

    private fun millis(dateTime: LocalDateTime, zone: ZoneId = seoul): Long = dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun record(startedAt: LocalDateTime, sleptMinutes: Int): SessionRecord = SessionRecord(
        startedAtMillis = millis(startedAt),
        endedAtMillis = millis(startedAt) + sleptMinutes * 60_000L,
        mode = SessionMode.NAP,
        plannedMinutes = 20,
        sleptMinutes = sleptMinutes,
        wakeReason = WakeReason.TIMER,
    )

    @Test
    fun weeklyReport_countsLast7Days() {
        val records = listOf(
            record(today.atTime(9, 0), 20),
            record(today.atTime(13, 30), 10),
            record(today.minusDays(1).atTime(18, 0), 15),
            record(today.minusDays(6).atTime(8, 0), 25),
        )

        val report = weeklyReport(records, now, seoul)

        assertEquals(4, report.sessionCount)
        assertEquals(70, report.totalMinutes)
        assertEquals(listOf(25, 0, 0, 0, 0, 15, 30), report.perDayMinutes)
    }

    @Test
    fun weeklyReport_excludesOlder() {
        val records = listOf(
            record(today.minusDays(7).atTime(23, 59), 20),
            record(today.minusDays(30).atTime(12, 0), 20),
            record(today.minusDays(6).atTime(0, 0), 5),
        )

        val report = weeklyReport(records, now, seoul)

        assertEquals(1, report.sessionCount)
        assertEquals(5, report.totalMinutes)
        assertEquals(listOf(5, 0, 0, 0, 0, 0, 0), report.perDayMinutes)
    }

    @Test
    fun weeklyReport_midnightBoundary_splitsDays() {
        val records = listOf(
            record(today.minusDays(1).atTime(23, 59, 59), 10),
            record(today.atStartOfDay(), 20),
        )

        val report = weeklyReport(records, now, seoul)

        assertEquals(listOf(0, 0, 0, 0, 0, 10, 20), report.perDayMinutes)
    }

    @Test
    fun weeklyReport_midnightBoundary_usesGivenZone() {
        // 서울 11/12 00:30은 UTC로 11/11 15:30이다. 같은 기록이 시간대에 따라 오늘 또는 어제로 간다.
        val records = listOf(record(today.atTime(0, 30), 10))
        val utc = ZoneId.of("UTC")
        val nowUtcSameDay = millis(today.atTime(12, 0), utc)

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 10), weeklyReport(records, now, seoul).perDayMinutes)
        assertEquals(listOf(0, 0, 0, 0, 0, 10, 0), weeklyReport(records, nowUtcSameDay, utc).perDayMinutes)
    }

    @Test
    fun weeklyReport_nowJustAfterMidnight_shiftsWindow() {
        val records = listOf(record(today.minusDays(6).atTime(12, 0), 10))
        val justBeforeMidnight = millis(today.atTime(23, 59, 59))
        val justAfterMidnight = millis(today.plusDays(1).atStartOfDay())

        assertEquals(1, weeklyReport(records, justBeforeMidnight, seoul).sessionCount)
        assertEquals(0, weeklyReport(records, justAfterMidnight, seoul).sessionCount)
    }

    @Test
    fun weeklyReport_emptyList_returnsSevenZeros() {
        val report = weeklyReport(emptyList(), now, seoul)

        assertEquals(WeeklyReport.EMPTY, report)
        assertEquals(7, report.perDayMinutes.size)
    }

    @Test
    fun weeklyReport_futureRecord_countsAsToday() {
        // 데모 모드는 시계가 60배로 흘러 기록 시각이 실제보다 앞설 수 있다.
        val records = listOf(record(today.plusDays(2).atTime(10, 0), 20))

        val report = weeklyReport(records, now, seoul)

        assertEquals(listOf(0, 0, 0, 0, 0, 0, 20), report.perDayMinutes)
    }

    @Test
    fun weeklyReport_negativeMinutes_countsAsZero() {
        val records = listOf(record(today.atTime(9, 0), -3))

        val report = weeklyReport(records, now, seoul)

        assertEquals(1, report.sessionCount)
        assertEquals(0, report.totalMinutes)
    }
}
