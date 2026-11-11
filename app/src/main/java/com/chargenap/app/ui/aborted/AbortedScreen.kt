package com.chargenap.app.ui.aborted

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.ui.FailureKind
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.BigButtonStyle
import com.chargenap.app.ui.components.Glyph
import com.chargenap.app.ui.components.GlyphBadge
import com.chargenap.app.ui.failureKinds
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.vehicle.CabinApplyResult

/** 중단 안내 화면의 단계. */
enum class AbortedPhase {
    /** 도어가 열려 있어 시트를 되돌리지 않고 기다리는 중. */
    WAITING_DOOR,

    /** 도어 값을 읽지 못해 사용자의 확인을 기다리는 중. */
    WAITING_DOOR_UNKNOWN,

    /** 시트·공조를 되돌리는 중. */
    RESTORING,

    /** 복구가 끝났다. 결과를 보여 준다. */
    DONE,
}

/**
 * 엔진 상태와 도어 값으로 단계를 정한다.
 *
 * `Aborted(DOOR_OPENED, restoring = true)`는 도어 닫힘 대기와 복구 중을 모두 뜻하므로 [anyDoorOpen]으로 가른다.
 * 도어 값을 읽지 못하면(null) 엔진은 사용자 확인 없이는 진행하지 않으므로 버튼을 보여 준다.
 *
 * @param overrideRequested 사용자가 [지금 되돌리기]를 눌렀다. 도어가 열린 채여도 복구 중으로 본다
 */
fun abortedPhase(state: NapSessionState.Aborted, anyDoorOpen: Boolean?, overrideRequested: Boolean): AbortedPhase = when {
    !state.restoring -> AbortedPhase.DONE
    state.reason != AbortReason.DOOR_OPENED || overrideRequested -> AbortedPhase.RESTORING
    anyDoorOpen == true -> AbortedPhase.WAITING_DOOR
    anyDoorOpen == null -> AbortedPhase.WAITING_DOOR_UNKNOWN
    else -> AbortedPhase.RESTORING
}

/** 중단 안내(상태 보관 포함). [지금 되돌리기]를 누른 뒤에는 도어 값과 상관없이 "되돌리는 중"으로 보여 준다. */
@Composable
fun AbortedHost(
    state: NapSessionState.Aborted,
    anyDoorOpen: Boolean?,
    onRestoreNow: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overrideRequested by remember { mutableStateOf(false) }
    AbortedScreen(
        reason = state.reason,
        phase = abortedPhase(state, anyDoorOpen, overrideRequested),
        restore = state.restore,
        onRestoreNow = {
            overrideRequested = true
            onRestoreNow()
        },
        onConfirm = onConfirm,
        modifier = modifier,
    )
}

/**
 * 중단 안내. 사유(기어 변경/도어 열림)와 지금 일어나는 일 하나, 그리고 버튼 하나만 보여 준다.
 *
 * @param restore 복구 결과. [AbortedPhase.DONE]에서만 쓴다
 */
@Composable
fun AbortedScreen(
    reason: AbortReason,
    phase: AbortedPhase,
    restore: CabinApplyResult?,
    onRestoreNow: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphBadge(glyph = Glyph.Alert, tint = NapColors.Alert, size = 96.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(
                when (reason) {
                    AbortReason.GEAR_LEFT_PARK -> R.string.aborted_title_gear
                    AbortReason.DOOR_OPENED -> R.string.aborted_title_door
                },
            ),
            style = NapType.H1,
            color = NapColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        when (phase) {
            AbortedPhase.WAITING_DOOR -> StatusLine(stringResource(R.string.aborted_wait_door))
            AbortedPhase.WAITING_DOOR_UNKNOWN -> StatusLine(stringResource(R.string.aborted_wait_door_unknown))
            AbortedPhase.RESTORING -> StatusLine(stringResource(R.string.aborted_restoring))
            AbortedPhase.DONE -> RestoreResult(restore ?: CabinApplyResult.NOTHING)
        }
        Spacer(Modifier.height(44.dp))
        when (phase) {
            AbortedPhase.WAITING_DOOR, AbortedPhase.WAITING_DOOR_UNKNOWN -> BigButton(
                text = stringResource(R.string.aborted_restore_now),
                onClick = onRestoreNow,
                // 문을 닫는 것이 먼저다. 열린 채로 되돌리는 것은 사용자가 고른 경우에만 한다.
                style = BigButtonStyle.Secondary,
                modifier = Modifier.width(420.dp),
            )
            // 버튼 자리를 비워 두어 단계가 바뀌어도 글자가 위아래로 움직이지 않게 한다.
            AbortedPhase.RESTORING -> Spacer(Modifier.height(96.dp))
            AbortedPhase.DONE -> BigButton(
                text = stringResource(R.string.action_confirm),
                onClick = onConfirm,
                modifier = Modifier.width(420.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text = text, style = NapType.Title, color = NapColors.TextSecondary, textAlign = TextAlign.Center)
}

/** 복구 결과. 실패한 항목이 있으면 사용자가 직접 할 일을 적는다. */
@Composable
private fun RestoreResult(restore: CabinApplyResult) {
    val failures = failureKinds(restore.errors)
    if (failures.isEmpty()) {
        StatusLine(
            stringResource(
                if (restore.seatApplied || restore.climateApplied) {
                    R.string.aborted_restored
                } else {
                    R.string.aborted_nothing_to_restore
                },
            ),
        )
        return
    }
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        failures.forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphBadge(glyph = Glyph.Cross, tint = NapColors.Alert, size = 36.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = stringResource(
                        when (kind) {
                            FailureKind.SEAT -> R.string.aborted_restore_failed_seat
                            FailureKind.CLIMATE -> R.string.aborted_restore_failed_climate
                            FailureKind.OTHER -> R.string.aborted_restore_failed_other
                        },
                    ),
                    style = NapType.Body,
                    color = NapColors.TextPrimary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 미리보기

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun AbortedWaitingDoorPreview() {
    ChargeNapTheme {
        DesignCanvas {
            AbortedScreen(
                reason = AbortReason.DOOR_OPENED,
                phase = AbortedPhase.WAITING_DOOR,
                restore = null,
                onRestoreNow = {},
                onConfirm = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun AbortedDonePreview() {
    ChargeNapTheme {
        DesignCanvas {
            AbortedScreen(
                reason = AbortReason.GEAR_LEFT_PARK,
                phase = AbortedPhase.DONE,
                restore = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()),
                onRestoreNow = {},
                onConfirm = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun AbortedRestoreFailedPreview() {
    ChargeNapTheme {
        DesignCanvas {
            AbortedScreen(
                reason = AbortReason.GEAR_LEFT_PARK,
                phase = AbortedPhase.DONE,
                restore = CabinApplyResult(seatApplied = false, climateApplied = true, errors = listOf("seat: timeout")),
                onRestoreNow = {},
                onConfirm = {},
            )
        }
    }
}
