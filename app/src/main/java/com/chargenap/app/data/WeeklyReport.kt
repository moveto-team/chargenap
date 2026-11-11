package com.chargenap.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 최근 7일(오늘 포함)의 휴식 요약.
 *
 * @param sessionCount 7일 동안의 세션 수
 * @param totalMinutes 7일 동안 쉰 시간의 합(분)
 * @param perDayMinutes 날짜별 쉰 시간(분). 7칸이고 마지막 칸이 오늘이다
 */
data class WeeklyReport(val sessionCount: Int, val totalMinutes: Int, val perDayMinutes: List<Int>) {
    companion object {
        const val DAYS = 7
        val EMPTY = WeeklyReport(sessionCount = 0, totalMinutes = 0, perDayMinutes = List(DAYS) { 0 })
    }
}

/**
 * [nowMillis]가 속한 날을 오늘로 보고 최근 7일을 집계한다. 날짜는 세션 시작 시각을 [zone]의 달력 날짜로 바꿔 정한다.
 *
 * 오늘보다 뒤의 날짜로 찍힌 기록은 오늘로 센다. 데모 모드는 시계가 빨리 흘러 기록 시각이 실제보다 앞설 수 있다.
 */
fun weeklyReport(records: List<SessionRecord>, nowMillis: Long, zone: ZoneId): WeeklyReport {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val perDay = IntArray(WeeklyReport.DAYS)
    var count = 0
    var total = 0
    for (record in records) {
        val day = Instant.ofEpochMilli(record.startedAtMillis).atZone(zone).toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(day, today).coerceAtLeast(0)
        if (daysAgo >= WeeklyReport.DAYS) continue
        val minutes = record.sleptMinutes.coerceAtLeast(0)
        perDay[WeeklyReport.DAYS - 1 - daysAgo.toInt()] += minutes
        count += 1
        total += minutes
    }
    return WeeklyReport(sessionCount = count, totalMinutes = total, perDayMinutes = perDay.toList())
}
