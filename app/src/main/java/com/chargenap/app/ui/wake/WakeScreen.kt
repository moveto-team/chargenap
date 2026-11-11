package com.chargenap.app.ui.wake

import androidx.compose.foundation.layout.Arrangement
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
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.Glyph
import com.chargenap.app.ui.components.GlyphBadge
import com.chargenap.app.ui.components.NapCard
import com.chargenap.app.ui.components.StatTile
import com.chargenap.app.ui.failureKinds
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.ui.wakeReasonLabel

/**
 * 기상 화면. 서늘한 바람을 틀고 시트를 되돌리는 동안 보인다. 누를 것은 없다.
 * 사용자가 직접 깨운 경우에는 "일어날 시간" 대신 담담한 문구를 쓴다.
 */
@Composable
fun WakingScreen(reason: WakeReason, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
        Spacer(Modifier.height(36.dp))
        Text(
            text = stringResource(R.string.wake_restoring),
            style = NapType.Body,
            color = NapColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 브리핑. 잔량(시작 → 끝), 주행 가능 거리, 쉰 시간, 기상 사유, 적용하지 못한 항목을 보여 주고 [확인]을 받는다.
 */
@Composable
fun BriefingScreen(summary: SessionSummary, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val interrupted = summary.wakeReason == WakeReason.CHARGING_INTERRUPTED
    val failures = failureKinds(summary.failures)
    // 위쪽 가운데는 DEMO 배지 자리라 제목을 조금 내려서 시작한다.
    Column(modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 60.dp, bottom = 36.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
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
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BatteryTile(summary, Modifier.weight(1.3f).fillMaxHeight())
                RangeTile(summary.rangeKm, Modifier.weight(1f).fillMaxHeight())
                SleptTile(summary, Modifier.weight(1f).fillMaxHeight())
            }
            if (failures.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                FailureCard(failures)
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

@Composable
private fun BatteryTile(summary: SessionSummary, modifier: Modifier = Modifier) {
    val start = summary.batteryStartPercent
    val end = summary.batteryEndPercent
    val label = stringResource(R.string.briefing_battery)
    when {
        start != null && end != null -> StatTile(
            label = label,
            value = stringResource(R.string.briefing_battery_change, start.toString(), end.toString()),
            unit = stringResource(R.string.unit_percent),
            valueColor = NapColors.Charge,
            modifier = modifier,
        )
        end != null || start != null -> StatTile(
            label = label,
            value = (end ?: start).toString(),
            unit = stringResource(R.string.unit_percent),
            valueColor = NapColors.Charge,
            modifier = modifier,
        )
        else -> StatTile(
            label = label,
            value = stringResource(R.string.value_unknown),
            caption = stringResource(R.string.briefing_battery_unknown),
            modifier = modifier,
        )
    }
}

@Composable
private fun RangeTile(rangeKm: Int?, modifier: Modifier = Modifier) {
    if (rangeKm != null) {
        StatTile(
            label = stringResource(R.string.briefing_range),
            value = rangeKm.toString(),
            unit = stringResource(R.string.unit_km),
            modifier = modifier,
        )
    } else {
        StatTile(
            label = stringResource(R.string.briefing_range),
            value = stringResource(R.string.value_unknown),
            caption = stringResource(R.string.briefing_range_unknown),
            modifier = modifier,
        )
    }
}

@Composable
private fun SleptTile(summary: SessionSummary, modifier: Modifier = Modifier) {
    val label = stringResource(
        if (summary.mode == SessionMode.BREATHING) R.string.briefing_slept_breathing else R.string.briefing_slept,
    )
    if (summary.sleptMinutes >= 1) {
        StatTile(
            label = label,
            value = summary.sleptMinutes.toString(),
            unit = stringResource(R.string.unit_minutes),
            modifier = modifier,
        )
    } else {
        StatTile(label = label, value = "1", unit = stringResource(R.string.unit_under_minute), modifier = modifier)
    }
}

/** 수면·기상 프리셋 적용이나 복구에서 실패한 항목. SDK 오류 원문 대신 사용자가 할 일을 적는다. */
@Composable
private fun FailureCard(failures: List<FailureKind>) {
    NapCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 22.dp)) {
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
