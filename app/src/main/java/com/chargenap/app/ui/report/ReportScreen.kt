package com.chargenap.app.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.WeeklyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.WakeReason
import com.chargenap.app.ui.components.NapCard
import com.chargenap.app.ui.components.ScreenHeader
import com.chargenap.app.ui.components.StatTile
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.ui.wakeReasonShortLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 휴식 리포트. 왼쪽은 최근 7일 요약(횟수, 총 시간, 날짜별 막대), 오른쪽은 최근 세션 목록.
 *
 * @param recentRecords 새 것이 먼저
 * @param todayEpochDay 세션 시계 기준 오늘. 막대 아래 요일 표시에 쓴다
 * @param zone 기록 시각을 날짜·시각으로 바꿀 시간대
 */
@Composable
fun ReportScreen(
    report: WeeklyReport,
    recentRecords: List<SessionRecord>,
    todayEpochDay: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Column(modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        ScreenHeader(
            title = stringResource(R.string.report_title),
            backLabel = stringResource(R.string.action_back),
            onBack = onBack,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    StatTile(
                        label = stringResource(R.string.report_week_count),
                        value = report.sessionCount.toString(),
                        unit = stringResource(R.string.unit_count),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    StatTile(
                        label = stringResource(R.string.report_week_minutes),
                        value = report.totalMinutes.toString(),
                        unit = stringResource(R.string.unit_minutes),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                NapCard(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            text = stringResource(R.string.report_chart_title),
                            style = NapType.BodySmall,
                            color = NapColors.TextSecondary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(12.dp))
                        WeekChart(
                            perDayMinutes = report.perDayMinutes,
                            todayEpochDay = todayEpochDay,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
            NapCard(Modifier.weight(1f).fillMaxHeight()) {
                if (recentRecords.isEmpty()) {
                    EmptyHistory()
                } else {
                    RecentList(recentRecords, zone)
                }
            }
        }
    }
}

/**
 * 날짜별 쉰 시간 막대 7개. 마지막(오른쪽)이 오늘이다. 막대는 Canvas로 직접 그린다.
 * 가장 긴 날을 기준으로 높이를 정하고, 쉰 적이 없는 날은 바닥에 짧은 표시만 남긴다.
 */
@Composable
private fun WeekChart(perDayMinutes: List<Int>, todayEpochDay: Long, modifier: Modifier = Modifier) {
    val days = perDayMinutes.takeLast(WeeklyReport.DAYS)
    val maxMinutes = (days.maxOrNull() ?: 0).coerceAtLeast(1)
    val weekdays = stringArrayResource(R.array.report_weekdays)
    val todayLabel = stringResource(R.string.report_today)
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val barMax: Dp = (maxHeight - VALUE_LABEL_HEIGHT).coerceAtLeast(8.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { index, minutes ->
                    val today = index == days.lastIndex
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        if (minutes > 0) {
                            Text(
                                text = minutes.toString(),
                                style = NapType.Caption,
                                color = if (today) NapColors.TextPrimary else NapColors.TextSecondary,
                                maxLines = 1,
                                modifier = Modifier.height(VALUE_LABEL_HEIGHT),
                            )
                        }
                        val barHeight = if (minutes > 0) (barMax * (minutes.toFloat() / maxMinutes)).coerceAtLeast(8.dp) else 4.dp
                        val color = when {
                            minutes <= 0 -> NapColors.Outline
                            today -> NapColors.Accent
                            // 반투명으로 칠하면 둥근 막대와 아래쪽 덮개가 겹치는 곳이 진해 보여서 미리 섞은 색을 쓴다.
                            else -> lerp(NapColors.Surface, NapColors.Accent, 0.45f)
                        }
                        Bar(color, Modifier.width(52.dp).height(barHeight))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(NapColors.Outline))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            days.indices.forEach { index ->
                val today = index == days.lastIndex
                val date = LocalDate.ofEpochDay(todayEpochDay - (days.lastIndex - index))
                Text(
                    text = if (today) todayLabel else weekdays.getOrElse(date.dayOfWeek.value - 1) { "" },
                    style = NapType.Caption,
                    color = if (today) NapColors.Accent else NapColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 위쪽 모서리만 둥근 막대. */
@Composable
private fun Bar(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = minOf(12.dp.toPx(), size.height / 2f)
        drawRoundRect(color = color, cornerRadius = CornerRadius(radius, radius))
        // 아래쪽 모서리는 바닥선에 붙도록 각지게 덮는다.
        drawRect(color = color, topLeft = Offset(0f, size.height - radius), size = Size(size.width, radius))
    }
}

@Composable
private fun RecentList(records: List<SessionRecord>, zone: ZoneId) {
    val listState = rememberLazyListState()
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.report_recent_title),
            style = NapType.BodySmall,
            color = NapColors.TextSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(records) { index, record ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(NapColors.Outline))
                    RecordRow(record, zone)
                }
            }
            if (canScrollForward) {
                // 아래에 더 있다는 표시. 마지막 줄이 잘린 것처럼 보이지 않게 흐리게 덮는다.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, NapColors.Surface))),
                )
            }
        }
    }
}

@Composable
private fun RecordRow(record: SessionRecord, zone: ZoneId) {
    val breathing = record.mode == SessionMode.BREATHING
    val title = when {
        record.sleptMinutes < 1 && breathing -> stringResource(R.string.report_item_breathing_under_minute)
        record.sleptMinutes < 1 -> stringResource(R.string.report_item_nap_under_minute)
        breathing -> stringResource(R.string.report_item_breathing, record.sleptMinutes)
        else -> stringResource(R.string.report_item_nap, record.sleptMinutes)
    }
    val started = Instant.ofEpochMilli(record.startedAtMillis).atZone(zone)
    val date = stringResource(
        R.string.report_item_date,
        started.monthValue,
        started.dayOfMonth,
        String.format(Locale.US, "%02d:%02d", started.hour, started.minute),
    )
    val detail = stringResource(R.string.report_item_detail, date, stringResource(wakeReasonShortLabel(record.wakeReason)))
    val start = record.batteryStartPercent
    val end = record.batteryEndPercent
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = NapType.Title,
                color = NapColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = NapType.BodySmall,
                color = NapColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (start != null && end != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.report_item_battery, start, end),
                style = NapType.Body,
                color = NapColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.report_empty_title),
            style = NapType.Title,
            color = NapColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.report_empty_body),
            style = NapType.Body,
            color = NapColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private val VALUE_LABEL_HEIGHT = 32.dp

// ---------------------------------------------------------------- 미리보기

/** 미리보기와 스냅샷에 쓰는 예시 기록. [todayEpochDay] 낮 12시를 기준으로 며칠에 걸쳐 만든다. 새 것이 먼저다. */
internal fun sampleRecords(todayEpochDay: Long, zone: ZoneId): List<SessionRecord> {
    fun at(daysAgo: Long, hour: Int, minute: Int): Long =
        LocalDate.ofEpochDay(todayEpochDay - daysAgo).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    fun record(daysAgo: Long, hour: Int, minute: Int, slept: Int, from: Int, to: Int, reason: WakeReason, mode: SessionMode = SessionMode.NAP) =
        SessionRecord(
            startedAtMillis = at(daysAgo, hour, minute),
            endedAtMillis = at(daysAgo, hour, minute) + slept * 60_000L,
            mode = mode,
            plannedMinutes = if (mode == SessionMode.BREATHING) 5 else 20,
            sleptMinutes = slept,
            batteryStartPercent = from,
            batteryEndPercent = to,
            rangeKm = to * 4,
            wakeReason = reason,
            seatMoved = mode == SessionMode.NAP,
        )
    return listOf(
        record(0, 14, 5, 20, 53, 73, WakeReason.TIMER),
        record(1, 18, 40, 15, 61, 76, WakeReason.CHARGE_NEARLY_DONE),
        record(3, 12, 20, 5, 72, 77, WakeReason.TIMER, SessionMode.BREATHING),
        record(3, 8, 10, 25, 34, 60, WakeReason.TIMER),
        record(4, 21, 15, 12, 48, 59, WakeReason.CHARGING_INTERRUPTED),
        record(6, 13, 30, 18, 55, 74, WakeReason.USER),
    )
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun ReportPreview() {
    val zone = ZoneId.of("Asia/Seoul")
    val today = LocalDate.of(2026, 11, 12).toEpochDay()
    ChargeNapTheme {
        DesignCanvas {
            ReportScreen(
                report = WeeklyReport(sessionCount = 6, totalMinutes = 95, perDayMinutes = listOf(18, 0, 12, 30, 0, 15, 20)),
                recentRecords = sampleRecords(today, zone),
                todayEpochDay = today,
                onBack = {},
                zone = zone,
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun ReportEmptyPreview() {
    ChargeNapTheme {
        DesignCanvas {
            ReportScreen(
                report = WeeklyReport.EMPTY,
                recentRecords = emptyList(),
                todayEpochDay = LocalDate.of(2026, 11, 12).toEpochDay(),
                onBack = {},
            )
        }
    }
}
