package com.chargenap.app.ui.safety

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.BigButtonStyle
import com.chargenap.app.ui.components.Glyph
import com.chargenap.app.ui.components.GlyphBadge
import com.chargenap.app.ui.components.NapCard
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapMotion
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.ui.theme.entrance
import com.chargenap.app.ui.theme.rememberEntrance

/**
 * 안전 확인 화면이 보여 줄 값. `SafetyCheck`와 `Preparing`이 같은 화면을 쓴다.
 *
 * @param report 점검 결과. `Preparing`에서는 엔진의 현재 점검 결과(`startBlockers`)를 넣는다
 * @param preparing true면 캐빈을 준비하는 중
 * @param seatMoving `Preparing`에서 시트를 눕히는 중이면 true
 */
data class SafetyUiModel(
    val minutes: Int,
    val mode: SessionMode,
    val report: SafetyReport,
    val rearSeatQuestionPending: Boolean = false,
    val doorConfirmPending: Boolean = false,
    val preparing: Boolean = false,
    val seatMoving: Boolean = false,
)

/** 점검 줄이 위에서부터 하나씩 나타나는 간격. */
private const val CHECK_STAGGER_MILLIS = 120

/** 오른쪽 카드(질문)는 점검 목록이 다 나타날 즈음 들어온다. */
private const val PROMPT_DELAY_MILLIS = 4 * CHECK_STAGGER_MILLIS + 60

/**
 * 안전 확인. 왼쪽은 조건 체크 목록, 오른쪽은 지금 사용자가 할 일 하나(질문 또는 안내)와 취소.
 * 질문은 음성 없이 화면 버튼으로 답한다.
 *
 * 처음 나타날 때 점검 줄이 위에서부터 120ms 간격으로 떠오르며 표시가 그려지고, 오른쪽 카드가 그 뒤에 들어온다.
 * 준비 중(Preparing)에는 오른쪽 카드에 운전석 일러스트([CabinIllustration])가 나온다.
 */
@Composable
fun SafetyScreen(
    model: SafetyUiModel,
    onAnswerRearSeat: (clear: Boolean) -> Unit,
    onConfirmDoorsClosed: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val promptIn = rememberEntrance(delayMillis = PROMPT_DELAY_MILLIS, durationMillis = 520)
    Column(modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Text(text = stringResource(R.string.safety_title), style = NapType.H2, color = NapColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                if (model.mode == SessionMode.BREATHING) R.string.safety_subtitle_breathing else R.string.safety_subtitle_nap,
                model.minutes,
            ),
            style = NapType.Body,
            color = NapColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            NapCard(Modifier.weight(1f).fillMaxHeight()) {
                Checklist(model.report)
            }
            NapCard(Modifier.weight(1f).fillMaxHeight().entrance(promptIn, rise = 32.dp)) {
                Prompt(model, onAnswerRearSeat, onConfirmDoorsClosed, onCancel)
            }
        }
    }
}

@Composable
private fun Checklist(report: SafetyReport) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        CheckRow(
            index = 0,
            label = stringResource(R.string.safety_item_gear),
            result = report.gear,
            failText = stringResource(R.string.safety_fail_gear),
        )
        CheckRow(
            index = 1,
            label = stringResource(R.string.safety_item_brake),
            result = report.parkingBrake,
            failText = stringResource(R.string.safety_fail_brake),
        )
        CheckRow(
            index = 2,
            label = stringResource(R.string.safety_item_charging),
            result = report.charging,
            failText = stringResource(R.string.safety_fail_charging),
        )
        CheckRow(
            index = 3,
            label = stringResource(R.string.safety_item_doors),
            result = report.doors,
            failText = stringResource(R.string.safety_fail_doors),
        )
    }
}

/**
 * 점검 항목 한 줄. 네 가지 결과를 도형과 색, 문구로 구분한다.
 * 처음에는 [index] 순서대로 떠오르고, 표시(체크 등)는 펜으로 긋듯 그려진다. 결과가 바뀌면 표시를 다시 긋는다.
 */
@Composable
private fun CheckRow(index: Int, label: String, result: CheckResult, failText: String) {
    val glyph = when (result) {
        CheckResult.PASS -> Glyph.Check
        CheckResult.FAIL -> Glyph.Cross
        CheckResult.SKIPPED -> Glyph.Dash
        CheckResult.NEEDS_USER_CONFIRM -> Glyph.Question
    }
    val tint by animateColorAsState(
        targetValue = when (result) {
            CheckResult.PASS -> NapColors.Accent
            CheckResult.FAIL -> NapColors.Alert
            CheckResult.SKIPPED -> NapColors.TextTertiary
            CheckResult.NEEDS_USER_CONFIRM -> NapColors.TextPrimary
        },
        animationSpec = tween(NapMotion.SHORT),
        label = "checkTint",
    )
    val status = when (result) {
        CheckResult.PASS -> stringResource(R.string.safety_pass)
        CheckResult.FAIL -> failText
        CheckResult.SKIPPED -> stringResource(R.string.safety_skipped)
        CheckResult.NEEDS_USER_CONFIRM -> stringResource(R.string.safety_needs_confirm)
    }
    val rowIn = rememberEntrance(delayMillis = index * CHECK_STAGGER_MILLIS, durationMillis = 420)
    // 표시 긋기: 처음에는 줄이 떠오른 뒤에, 결과가 바뀐 뒤에는 바로.
    val inspection = LocalInspectionMode.current
    val firstDraw = remember { booleanArrayOf(true) }
    val drawn = remember(result) { Animatable(if (inspection) 1f else 0f) }
    LaunchedEffect(result) {
        val delay = if (firstDraw[0]) index * CHECK_STAGGER_MILLIS + 200 else 0
        firstDraw[0] = false
        drawn.animateTo(1f, tween(durationMillis = 420, delayMillis = delay, easing = FastOutSlowInEasing))
    }
    Row(Modifier.fillMaxWidth().entrance(rowIn, rise = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphBadge(
            glyph = glyph,
            tint = tint,
            size = 56.dp,
            filled = result != CheckResult.SKIPPED,
            progress = { drawn.value },
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, style = NapType.Title, color = NapColors.TextPrimary, maxLines = 1)
            Text(
                text = status,
                style = NapType.BodySmall,
                color = if (result == CheckResult.FAIL) NapColors.Alert else NapColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 오른쪽 카드가 지금 보여 주는 것. 바뀌면 내용이 교차 전환된다. */
private enum class PromptKind { PREPARING, REAR_SEAT, DOOR_CONFIRM, WAIT_DOOR, WAIT_BRAKE, CHECKING }

private fun promptKind(model: SafetyUiModel): PromptKind = when {
    model.preparing -> PromptKind.PREPARING
    model.rearSeatQuestionPending -> PromptKind.REAR_SEAT
    model.doorConfirmPending -> PromptKind.DOOR_CONFIRM
    model.report.doors == CheckResult.FAIL -> PromptKind.WAIT_DOOR
    model.report.parkingBrake == CheckResult.FAIL -> PromptKind.WAIT_BRAKE
    else -> PromptKind.CHECKING
}

/** 오른쪽 카드: 지금 필요한 것 하나만 보여 준다. 뒷좌석 질문 → 도어 확인 → 조치 대기 순이다. */
@Composable
private fun Prompt(
    model: SafetyUiModel,
    onAnswerRearSeat: (Boolean) -> Unit,
    onConfirmDoorsClosed: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = promptKind(model),
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = {
                (
                    fadeIn(tween(NapMotion.MEDIUM, delayMillis = 120)) +
                        slideInVertically(tween(NapMotion.LONG, easing = NapMotion.Enter)) { it / 14 }
                    ) togetherWith fadeOut(tween(NapMotion.SHORT))
            },
            label = "safetyPrompt",
        ) { kind ->
            Column(Modifier.fillMaxSize()) {
                when (kind) {
                    PromptKind.PREPARING -> {
                        PromptText(
                            title = stringResource(R.string.preparing_title),
                            body = stringResource(
                                if (model.seatMoving) R.string.preparing_body_seat else R.string.preparing_body_climate,
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        CabinIllustration(
                            reclining = model.seatMoving,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    PromptKind.REAR_SEAT -> {
                        PromptText(
                            title = stringResource(R.string.safety_rear_question),
                            body = stringResource(R.string.safety_rear_body),
                        )
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            BigButton(
                                text = stringResource(R.string.safety_rear_clear),
                                onClick = { onAnswerRearSeat(true) },
                                modifier = Modifier.weight(1f),
                            )
                            BigButton(
                                text = stringResource(R.string.safety_rear_occupied),
                                onClick = { onAnswerRearSeat(false) },
                                style = BigButtonStyle.Secondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    PromptKind.DOOR_CONFIRM -> {
                        PromptText(
                            title = stringResource(R.string.safety_door_question),
                            body = stringResource(R.string.safety_door_body),
                        )
                        Spacer(Modifier.weight(1f))
                        BigButton(
                            text = stringResource(R.string.safety_door_confirm),
                            onClick = onConfirmDoorsClosed,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    PromptKind.WAIT_DOOR -> PromptText(
                        title = stringResource(R.string.safety_wait_door_title),
                        body = stringResource(R.string.safety_wait_body),
                    )
                    PromptKind.WAIT_BRAKE -> PromptText(
                        title = stringResource(R.string.safety_wait_brake_title),
                        body = stringResource(R.string.safety_wait_body),
                    )
                    PromptKind.CHECKING -> PromptText(title = stringResource(R.string.safety_checking_title), body = null)
                }
            }
        }
        BigButton(
            text = stringResource(R.string.action_cancel),
            onClick = onCancel,
            style = BigButtonStyle.Ghost,
            height = 76.dp,
            small = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PromptText(title: String, body: String?) {
    Text(text = title, style = NapType.H1, color = NapColors.TextPrimary)
    if (body != null) {
        Spacer(Modifier.height(14.dp))
        Text(text = body, style = NapType.Body, color = NapColors.TextSecondary)
    }
}

// ---------------------------------------------------------------- 미리보기

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun SafetyRearSeatPreview() {
    ChargeNapTheme {
        DesignCanvas {
            SafetyScreen(
                model = SafetyUiModel(
                    minutes = 20,
                    mode = SessionMode.NAP,
                    report = SafetyReport(CheckResult.PASS, CheckResult.SKIPPED, CheckResult.PASS, CheckResult.PASS),
                    rearSeatQuestionPending = true,
                ),
                onAnswerRearSeat = {},
                onConfirmDoorsClosed = {},
                onCancel = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun PreparingPreview() {
    ChargeNapTheme {
        DesignCanvas {
            SafetyScreen(
                model = SafetyUiModel(
                    minutes = 20,
                    mode = SessionMode.NAP,
                    report = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS),
                    preparing = true,
                    seatMoving = true,
                ),
                onAnswerRearSeat = {},
                onConfirmDoorsClosed = {},
                onCancel = {},
            )
        }
    }
}
