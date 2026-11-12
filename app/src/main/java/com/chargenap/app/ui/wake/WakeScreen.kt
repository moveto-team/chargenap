package com.chargenap.app.ui.wake

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import com.chargenap.app.ui.FailureKind
import com.chargenap.app.ui.components.AirStreaks
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.Glyph
import com.chargenap.app.ui.components.GlyphBadge
import com.chargenap.app.ui.components.NapCard
import com.chargenap.app.ui.components.StatTile
import com.chargenap.app.ui.components.rememberFlowClock
import com.chargenap.app.ui.failureKinds
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapMotion
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.ui.theme.animatedCount
import com.chargenap.app.ui.theme.entrance
import com.chargenap.app.ui.theme.rememberEntrance
import com.chargenap.app.ui.wakeReasonLabel

/** 새벽빛이 아래에서 위로 다 번지는 시간. */
private const val DAWN_MILLIS = 1_500

/** 서늘한 바람 곡선이 화면을 한 번 가로지르는 시간. */
private const val BREEZE_PERIOD_MILLIS = 3_400

/** 새벽빛: 아래쪽 지평선의 옅은 자주빛에서 위로 갈수록 남색, 그 위는 바탕 그대로. */
private val DawnHorizon = Color(0xFF3A2D44)
private val DawnSky = Color(0xFF16233D)

/** 브리핑 타일이 왼쪽부터 떠오르는 간격. */
private const val TILE_STAGGER_MILLIS = 90
private const val TILE_DELAY_MILLIS = 150

/**
 * 기상 화면. 서늘한 바람을 틀고 시트를 되돌리는 동안 보인다. 누를 것은 없다.
 * 사용자가 직접 깨운 경우에는 "일어날 시간" 대신 담담한 문구를 쓴다.
 *
 * 화면 아래에서 위로 새벽빛 그라데이션이 1.5초에 걸쳐 밝아지고, 위쪽에는 서늘한 바람을 뜻하는 가는 곡선이 흐른다.
 * 둘 다 그리기 단계에서만 값을 읽는다.
 */
@Composable
fun WakingScreen(reason: WakeReason, modifier: Modifier = Modifier) {
    val dawn = rememberEntrance(durationMillis = DAWN_MILLIS, easing = LinearOutSlowInEasing)
    val breeze = rememberFlowClock(BREEZE_PERIOD_MILLIS, label = "wakeBreeze")
    val titleIn = rememberEntrance(delayMillis = 200, durationMillis = NapMotion.LONG)
    val restoringIn = rememberEntrance(delayMillis = 450, durationMillis = NapMotion.LONG)
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val streaks = AirStreaks(breezePaths(size.width, size.height))
                val strokeWidth = 2.dp.toPx()
                onDrawBehind {
                    val p = dawn.value
                    if (p > 0f) {
                        // 아래에서 위로 번진다: 빛의 윗단이 화면 아래에서 위쪽 1/5 지점까지 올라온다.
                        val top = size.height * (1f - 0.8f * p)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to DawnSky,
                                1f to DawnHorizon,
                                startY = top,
                                endY = size.height,
                            ),
                            alpha = p,
                        )
                    }
                    streaks.draw(
                        scope = this,
                        phase = breeze.value,
                        color = NapColors.Accent.copy(alpha = 0.45f * p),
                        strokeWidth = strokeWidth,
                    )
                }
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.entrance(titleIn, rise = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(if (reason == WakeReason.USER) R.string.wake_title_user else R.string.wake_title),
                    style = NapType.Display,
                    color = NapColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                if (reason != WakeReason.USER) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(wakeReasonLabel(reason)),
                        style = NapType.Title,
                        color = if (reason == WakeReason.CHARGING_INTERRUPTED) NapColors.Alert else NapColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text(
                text = stringResource(R.string.wake_restoring),
                style = NapType.Body,
                color = NapColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.entrance(restoringIn, rise = 12.dp),
            )
        }
    }
}

/** 위쪽 띠를 왼쪽에서 오른쪽으로 가로지르는 바람 길 네 줄. */
private fun breezePaths(w: Float, h: Float): List<Path> = (0 until 4).map { i ->
    val y = h * (0.15f + i * 0.05f)
    val lift = h * (0.035f + i * 0.008f)
    Path().apply {
        moveTo(-0.04f * w, y)
        cubicTo(0.28f * w, y - lift, 0.62f * w, y + lift, 1.04f * w, y - lift * 0.3f)
    }
}

/**
 * 브리핑. 잔량(시작 → 끝), 주행 가능 거리, 쉰 시간, 기상 사유, 적용하지 못한 항목을 보여 주고 [확인]을 받는다.
 *
 * 타일 세 개가 왼쪽부터 90ms 간격으로 떠오르고, 숫자는 0에서 값까지 센다(잔량은 시작 값에서 끝 값까지).
 * 적용하지 못한 항목 카드는 타일 뒤에 들어온다.
 */
@Composable
fun BriefingScreen(summary: SessionSummary, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val interrupted = summary.wakeReason == WakeReason.CHARGING_INTERRUPTED
    val failures = failureKinds(summary.failures)
    val headerIn = rememberEntrance(durationMillis = NapMotion.MEDIUM)
    val failuresIn = rememberEntrance(delayMillis = TILE_DELAY_MILLIS + 3 * TILE_STAGGER_MILLIS + 180, durationMillis = 520)
    // 위쪽 가운데는 DEMO 배지 자리라 제목을 조금 내려서 시작한다.
    Column(modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 58.dp, bottom = 36.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.entrance(headerIn, rise = 12.dp)) {
                Text(
                    text = stringResource(if (interrupted) R.string.briefing_title_interrupted else R.string.briefing_title),
                    style = NapType.H1,
                    color = NapColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (interrupted) R.string.briefing_interrupted_body else wakeReasonLabel(summary.wakeReason),
                    ),
                    style = NapType.Body,
                    color = if (interrupted) NapColors.Alert else NapColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BatteryTile(summary, tileModifier(0).weight(1.3f).fillMaxHeight())
                RangeTile(summary.rangeKm, tileModifier(1).weight(1f).fillMaxHeight(), countDelay = tileDelay(1))
                SleptTile(summary, tileModifier(2).weight(1f).fillMaxHeight(), countDelay = tileDelay(2))
            }
            if (failures.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                FailureCard(failures, Modifier.entrance(failuresIn, rise = 24.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        BigButton(
            text = stringResource(R.string.action_confirm),
            onClick = onConfirm,
            modifier = Modifier.align(Alignment.End).width(420.dp),
        )
    }
}

private fun tileDelay(index: Int): Int = TILE_DELAY_MILLIS + index * TILE_STAGGER_MILLIS

/** [index]번째 타일이 왼쪽부터 차례로 떠오르게 한다. */
@Composable
private fun tileModifier(index: Int): Modifier {
    val tileIn = rememberEntrance(delayMillis = tileDelay(index), durationMillis = 520)
    return Modifier.entrance(tileIn, rise = 28.dp)
}

@Composable
private fun BatteryTile(summary: SessionSummary, modifier: Modifier = Modifier) {
    val start = summary.batteryStartPercent
    val end = summary.batteryEndPercent
    val label = stringResource(R.string.briefing_battery)
    when {
        start != null && end != null -> {
            // 끝 잔량은 시작 잔량에서부터 세어 올라간다(쉬는 동안 충전된 만큼).
            val counted = animatedCount(target = end, from = start, delayMillis = tileDelay(0) + 120)
            StatTile(
                label = label,
                value = stringResource(R.string.briefing_battery_change, start.toString(), counted.toString()),
                finalValue = stringResource(R.string.briefing_battery_change, start.toString(), end.toString()),
                unit = stringResource(R.string.unit_percent),
                valueColor = NapColors.Charge,
                modifier = modifier,
            )
        }
        end != null || start != null -> {
            val value = end ?: start ?: 0
            val counted = animatedCount(target = value, delayMillis = tileDelay(0) + 120)
            StatTile(
                label = label,
                value = counted.toString(),
                finalValue = value.toString(),
                unit = stringResource(R.string.unit_percent),
                valueColor = NapColors.Charge,
                modifier = modifier,
            )
        }
        else -> UnknownTile(label, stringResource(R.string.briefing_battery_unknown), modifier)
    }
}

@Composable
private fun RangeTile(rangeKm: Int?, modifier: Modifier = Modifier, countDelay: Int = 0) {
    if (rangeKm != null) {
        val counted = animatedCount(target = rangeKm, delayMillis = countDelay + 120)
        StatTile(
            label = stringResource(R.string.briefing_range),
            value = counted.toString(),
            finalValue = rangeKm.toString(),
            unit = stringResource(R.string.unit_km),
            modifier = modifier,
        )
    } else {
        UnknownTile(stringResource(R.string.briefing_range), stringResource(R.string.briefing_range_unknown), modifier)
    }
}

/** 값을 읽지 못한 항목. 큰 숫자 자리에 사유를 두 줄로 적는다(다른 타일과 높이가 같다). */
@Composable
private fun UnknownTile(label: String, message: String, modifier: Modifier = Modifier) {
    NapCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 26.dp)) {
        Column {
            Text(text = label, style = NapType.BodySmall, color = NapColors.TextSecondary, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Text(text = message, style = NapType.Body, color = NapColors.TextTertiary, maxLines = 2)
        }
    }
}

@Composable
private fun SleptTile(summary: SessionSummary, modifier: Modifier = Modifier, countDelay: Int = 0) {
    val label = stringResource(
        if (summary.mode == SessionMode.BREATHING) R.string.briefing_slept_breathing else R.string.briefing_slept,
    )
    if (summary.sleptMinutes >= 1) {
        val counted = animatedCount(target = summary.sleptMinutes, delayMillis = countDelay + 120)
        StatTile(
            label = label,
            value = counted.toString(),
            finalValue = summary.sleptMinutes.toString(),
            unit = stringResource(R.string.unit_minutes),
            modifier = modifier,
        )
    } else {
        StatTile(label = label, value = "1", unit = stringResource(R.string.unit_under_minute), modifier = modifier)
    }
}

/** 수면·기상 프리셋 적용이나 복구에서 실패한 항목. SDK 오류 원문 대신 사용자가 할 일을 적는다. */
@Composable
private fun FailureCard(failures: List<FailureKind>, modifier: Modifier = Modifier) {
    NapCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.briefing_failures_title),
                style = NapType.BodySmall,
                color = NapColors.Alert,
            )
            failures.forEach { kind ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphBadge(glyph = Glyph.Alert, tint = NapColors.Alert, size = 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(
                            when (kind) {
                                FailureKind.SEAT -> R.string.failure_seat
                                FailureKind.CLIMATE -> R.string.failure_climate
                                FailureKind.OTHER -> R.string.failure_other
                            },
                        ),
                        style = NapType.BodySmall,
                        color = NapColors.TextPrimary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 미리보기

internal val previewSummary = SessionSummary(
    startedAtMillis = 0,
    endedAtMillis = 18 * 60_000L,
    mode = SessionMode.NAP,
    plannedMinutes = 20,
    sleptMinutes = 18,
    batteryStartPercent = 53,
    batteryEndPercent = 71,
    rangeKm = 319,
    wakeReason = WakeReason.CHARGE_NEARLY_DONE,
    seatMoved = true,
    failures = emptyList(),
)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun WakingPreview() {
    ChargeNapTheme { DesignCanvas { WakingScreen(WakeReason.CHARGE_NEARLY_DONE) } }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun BriefingPreview() {
    ChargeNapTheme { DesignCanvas { BriefingScreen(previewSummary, onConfirm = {}) } }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun BriefingFailuresPreview() {
    ChargeNapTheme {
        DesignCanvas {
            BriefingScreen(
                summary = previewSummary.copy(
                    sleptMinutes = 7,
                    batteryEndPercent = 60,
                    rangeKm = null,
                    wakeReason = WakeReason.CHARGING_INTERRUPTED,
                    failures = listOf("seat: timeout", "climate: rejected"),
                ),
                onConfirm = {},
            )
        }
    }
}
