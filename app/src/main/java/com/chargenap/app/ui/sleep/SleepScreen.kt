package com.chargenap.app.ui.sleep

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.session.SessionMode
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.BigButtonStyle
import com.chargenap.app.ui.formatClock
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType
import kotlinx.coroutines.delay

/** 짧게 누른 뒤 버튼이 보이는 시간. */
private const val CONTROLS_VISIBLE_MILLIS = 3_000L

/**
 * 수면 화면(상태 보관 포함). 짧게 누르면 3초 동안 [5분 더]/[지금 일어나기]가 나타난다.
 *
 * @param controlsInitiallyVisible 스냅샷·미리보기에서 버튼이 보이는 상태를 만들 때 쓴다
 */
@Composable
fun SleepHost(
    remainingSeconds: Int,
    timerOnly: Boolean,
    mode: SessionMode,
    onWake: () -> Unit,
    onExtend: () -> Unit,
    modifier: Modifier = Modifier,
    controlsInitiallyVisible: Boolean = false,
) {
    var controlsVisible by remember { mutableStateOf(controlsInitiallyVisible) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsVisible, taps) {
        if (controlsVisible) {
            delay(CONTROLS_VISIBLE_MILLIS)
            controlsVisible = false
        }
    }
    SleepScreen(
        remainingSeconds = remainingSeconds,
        timerOnly = timerOnly,
        mode = mode,
        controlsVisible = controlsVisible,
        onTap = {
            controlsVisible = true
            taps += 1
        },
        onWake = onWake,
        onExtend = {
            taps += 1
            onExtend()
        },
        modifier = modifier,
    )
}

/**
 * 수면 화면. 검은 바탕에 남은 시간만 크게, 아주 어두운 글자로 보여 준다.
 * 화면 아무 곳이나 길게 누르면 기상([onWake]), 짧게 누르면 [onTap].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SleepScreen(
    remainingSeconds: Int,
    timerOnly: Boolean,
    mode: SessionMode,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    onWake: () -> Unit,
    onExtend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NapColors.SleepBackground)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
                onLongClick = onWake,
            ),
    ) {
        Column(
            // 위쪽은 DEMO 배지와 조작 패널 자리라 왼쪽 아래 구석에 둔다.
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (mode == SessionMode.BREATHING) {
                Text(
                    text = stringResource(R.string.sleep_mode_breathing),
                    style = NapType.BodySmall,
                    color = NapColors.SleepTextDim,
                )
            }
            if (timerOnly) {
                Text(
                    text = stringResource(R.string.sleep_timer_only),
                    style = NapType.BodySmall,
                    color = NapColors.SleepTextDim,
                )
            }
        }

        // 아래쪽 버튼과 안내가 들어갈 자리를 두려고 정중앙보다 조금 위에 둔다.
        Column(
            modifier = Modifier.align(Alignment.Center).offset(y = (-28).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatClock(remainingSeconds),
                style = NapType.Countdown,
                color = NapColors.SleepText,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sleep_hint_long_press),
                style = NapType.Body,
                color = NapColors.SleepTextDim,
            )
        }

        if (controlsVisible) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                BigButton(
                    text = stringResource(R.string.sleep_extend),
                    onClick = onExtend,
                    style = BigButtonStyle.Dim,
                    modifier = Modifier.width(260.dp),
                )
                BigButton(
                    text = stringResource(R.string.sleep_wake_now),
                    onClick = onWake,
                    style = BigButtonStyle.Dim,
                    modifier = Modifier.width(320.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 미리보기

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun SleepPreview() {
    ChargeNapTheme {
        DesignCanvas(background = NapColors.SleepBackground) {
            SleepScreen(
                remainingSeconds = 18 * 60 + 42,
                timerOnly = false,
                mode = SessionMode.NAP,
                controlsVisible = false,
                onTap = {},
                onWake = {},
                onExtend = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun SleepControlsPreview() {
    ChargeNapTheme {
        DesignCanvas(background = NapColors.SleepBackground) {
            SleepScreen(
                remainingSeconds = 4 * 60 + 5,
                timerOnly = true,
                mode = SessionMode.NAP,
                controlsVisible = true,
                onTap = {},
                onWake = {},
                onExtend = {},
            )
        }
    }
}
