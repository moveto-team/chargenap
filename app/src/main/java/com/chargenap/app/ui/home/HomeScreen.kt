package com.chargenap.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.ui.NapUiState
import com.chargenap.app.ui.chargingStateLabel
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.BigButtonStyle
import com.chargenap.app.ui.components.ChargeRing
import com.chargenap.app.ui.components.NapCard
import com.chargenap.app.ui.startBlockReasonLabel
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.vehicle.ChargingState

/** 직접 고를 수 있는 파워냅 길이(분). */
private val DURATION_OPTIONS = listOf(10, 15, 20, 25)
private const val DEFAULT_DURATION = 20

/**
 * 홈. 왼쪽에 충전 상태(잔량 고리, 상태, 남은 시간), 오른쪽에 제안 카드 또는 시간 선택 카드.
 *
 * `Idle`과 `Offer`에서 보인다. 시작할 수 없으면 버튼을 끄고 이유를 한 줄로 보여 준다.
 */
@Composable
fun HomeScreen(
    state: NapUiState,
    onStart: (Int?) -> Unit,
    onDecline: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.app_name), style = NapType.H2, color = NapColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            BigButton(
                text = stringResource(R.string.home_nav_report),
                onClick = onOpenReport,
                style = BigButtonStyle.Ghost,
                height = 76.dp,
                small = true,
            )
            Spacer(Modifier.width(16.dp))
            BigButton(
                text = stringResource(R.string.home_nav_settings),
                onClick = onOpenSettings,
                style = BigButtonStyle.Ghost,
                height = 76.dp,
                small = true,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            ChargeSummary(state, Modifier.weight(0.85f).fillMaxHeight())
            NapCard(Modifier.weight(1.15f).fillMaxHeight()) {
                val offer = state.session as? NapSessionState.Offer
                when (val recommendation = offer?.recommendation) {
                    is Recommendation.Nap -> OfferContent(
                        title = stringResource(R.string.home_offer_nap_title, recommendation.minutes),
                        body = stringResource(
                            R.string.home_offer_nap_body,
                            state.settings.targetPercent,
                            recommendation.remainingChargeMinutes,
                        ),
                        voiceHint = state.voiceAvailable,
                        onStart = { onStart(recommendation.minutes) },
                        onDecline = onDecline,
                    )
                    is Recommendation.Breathing -> OfferContent(
                        title = stringResource(R.string.home_offer_breathing_title, recommendation.minutes),
                        body = stringResource(R.string.home_offer_breathing_body),
                        voiceHint = state.voiceAvailable,
                        onStart = { onStart(recommendation.minutes) },
                        onDecline = onDecline,
                    )
                    is Recommendation.ChooseDuration -> ChooseContent(
                        options = recommendation.options,
                        onStart = onStart,
                        onDecline = onDecline,
                    )
                    Recommendation.None, null -> PickContent(state = state, onStart = onStart)
                }
            }
        }
    }
}

/** 잔량 고리 + 충전 상태 + 목표까지 남은 시간. */
@Composable
private fun ChargeSummary(state: NapUiState, modifier: Modifier = Modifier) {
    val charging = state.chargingState == ChargingState.CHARGING
    val chargeTone = charging || state.chargingState == ChargingState.COMPLETE
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(272.dp), contentAlignment = Alignment.Center) {
            ChargeRing(
                percent = state.batteryPercent,
                chargingState = state.chargingState,
                targetPercent = state.settings.targetPercent,
                modifier = Modifier.fillMaxSize(),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.batteryPercent?.toString() ?: stringResource(R.string.value_unknown),
                    style = NapType.Hero,
                    color = NapColors.TextPrimary,
                    modifier = Modifier.alignByBaseline(),
                )
                if (state.batteryPercent != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.unit_percent),
                        style = NapType.Title,
                        color = NapColors.TextSecondary,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (chargeTone) NapColors.Charge else NapColors.TextTertiary),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(chargingStateLabel(state.chargingState)),
                style = NapType.Title,
                color = if (chargeTone) NapColors.Charge else NapColors.TextSecondary,
                maxLines = 1,
            )
        }
        val detail = when {
            state.batteryPercent == null -> stringResource(R.string.home_battery_unknown)
            !charging -> null
            state.remainingChargeMinutes != null -> stringResource(
                R.string.home_remaining_to_target,
                state.settings.targetPercent,
                state.remainingChargeMinutes,
            )
            else -> stringResource(R.string.home_remaining_measuring)
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = detail, style = NapType.Body, color = NapColors.TextSecondary, maxLines = 1)
        }
        if (state.fallbackReason != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_fallback_demo),
                style = NapType.Caption,
                color = NapColors.Alert,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** 제안 카드: 제목 + 설명 + [괜찮아요] [시작]. */
@Composable
private fun OfferContent(
    title: String,
    body: String,
    voiceHint: Boolean,
    onStart: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(text = stringResource(R.string.home_offer_label), style = NapType.BodySmall, color = NapColors.Accent)
        Spacer(Modifier.height(10.dp))
        Text(text = title, style = NapType.H1, color = NapColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(text = body, style = NapType.Body, color = NapColors.TextSecondary)
        if (voiceHint) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.home_voice_hint),
                style = NapType.BodySmall,
                color = NapColors.TextTertiary,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton(
                text = stringResource(R.string.home_offer_decline),
                onClick = onDecline,
                style = BigButtonStyle.Secondary,
                modifier = Modifier.weight(1f),
            )
            BigButton(
                text = stringResource(R.string.home_offer_start),
                onClick = onStart,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

/** 충전 속도를 아직 모를 때: 두 가지 길이 중에서 고른다. */
@Composable
private fun ChooseContent(options: List<Int>, onStart: (Int?) -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(text = stringResource(R.string.home_offer_label), style = NapType.BodySmall, color = NapColors.Accent)
        Spacer(Modifier.height(10.dp))
        Text(text = stringResource(R.string.home_offer_choose_title), style = NapType.H1, color = NapColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.home_offer_choose_body),
            style = NapType.Body,
            color = NapColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            options.forEach { minutes ->
                BigButton(
                    text = stringResource(R.string.minutes_format, minutes),
                    onClick = { onStart(minutes) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        BigButton(
            text = stringResource(R.string.home_offer_decline),
            onClick = onDecline,
            style = BigButtonStyle.Ghost,
            height = 76.dp,
            small = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 제안이 없을 때: 길이를 직접 골라 시작한다. 시작할 수 없으면 전부 끄고 이유를 보여 준다. */
@Composable
private fun PickContent(state: NapUiState, onStart: (Int?) -> Unit) {
    val blockReason = state.startBlockReason
    val enabled = blockReason == null
    var selected by rememberSaveable { mutableIntStateOf(DEFAULT_DURATION) }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(if (enabled) R.string.home_pick_title else R.string.home_blocked_title),
            style = NapType.H1,
            color = NapColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(if (blockReason != null) startBlockReasonLabel(blockReason) else R.string.home_pick_body),
            style = NapType.Body,
            color = NapColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DURATION_OPTIONS.forEach { minutes ->
                DurationChip(
                    text = stringResource(R.string.minutes_format, minutes),
                    selected = enabled && minutes == selected,
                    enabled = enabled,
                    onClick = { selected = minutes },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        BigButton(
            text = if (enabled) {
                stringResource(R.string.home_pick_start, selected)
            } else {
                stringResource(R.string.home_blocked_start)
            },
            onClick = { onStart(selected) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DurationChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .height(88.dp)
            .clip(shape)
            .background(if (selected) NapColors.AccentContainer else NapColors.SurfaceRaised)
            .border(1.5.dp, if (selected) NapColors.Accent else NapColors.Outline, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NapType.Button,
            color = when {
                !enabled -> NapColors.TextTertiary
                selected -> NapColors.Accent
                else -> NapColors.TextSecondary
            },
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 미리보기

private val allPass = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun HomeOfferPreview() {
    ChargeNapTheme {
        DesignCanvas {
            HomeScreen(
                state = NapUiState(
                    session = NapSessionState.Offer(Recommendation.Nap(20, 27)),
                    chargingState = ChargingState.CHARGING,
                    batteryPercent = 53,
                    remainingChargeMinutes = 27,
                    startBlockers = allPass,
                    gearInPark = true,
                    voiceAvailable = true,
                ),
                onStart = {},
                onDecline = {},
                onOpenReport = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun HomeNotChargingPreview() {
    ChargeNapTheme {
        DesignCanvas {
            HomeScreen(
                state = NapUiState(
                    chargingState = ChargingState.NOT_CONNECTED,
                    batteryPercent = 41,
                    startBlockers = allPass.copy(charging = CheckResult.FAIL),
                    gearInPark = true,
                ),
                onStart = {},
                onDecline = {},
                onOpenReport = {},
                onOpenSettings = {},
            )
        }
    }
}
