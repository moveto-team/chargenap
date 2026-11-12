package com.chargenap.app.ui.sleep

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.session.SessionMode
import com.chargenap.app.ui.components.BigButton
import com.chargenap.app.ui.components.BigButtonStyle
import com.chargenap.app.ui.components.rememberFlowClock
import com.chargenap.app.ui.formatClock
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapMotion
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.ui.theme.at
import com.chargenap.app.ui.theme.smoothStep
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** 짧게 누른 뒤 버튼이 보이는 시간. */
private const val CONTROLS_VISIBLE_MILLIS = 3_000L

/** 호흡 한 주기: 들숨 4초, 날숨 6초. */
private const val INHALE_MILLIS = 4_000
private const val EXHALE_MILLIS = 6_000
private const val BREATH_CYCLE_MILLIS = INHALE_MILLIS + EXHALE_MILLIS

/** 호흡 안내와 백색소음 파도가 서로 바뀌는 시간. */
private const val AMBIENCE_CROSSFADE_MILLIS = 1_500

/** 파도가 한 파장만큼 흘러가는 시간. 아주 천천히. */
private const val WAVE_PERIOD_MILLIS = 16_000

/** 숫자가 바뀔 때 굴러가는 시간. */
private const val DIGIT_ROLL_MILLIS = 600

/** 호흡 원: 아주 어두운 남색. 수면 글자(SleepText)보다 훨씬 어둡다. */
private val BreathCore = Color(0xFF0C1322)

/** 백색소음 파도 선. */
private val WaveLine = NapColors.SleepTextDim

/**
 * 수면 화면(상태 보관 포함). 짧게 누르면 3초 동안 [5분 더]/[지금 일어나기]가 나타난다.
 *
 * @param breathingGuide 호흡 안내 구간이면 true(숫자 뒤에 호흡 원과 "들이마시고 · 내쉬고")
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
    breathingGuide: Boolean = false,
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
        breathingGuide = breathingGuide,
    )
}

/**
 * 수면 화면. 검은 바탕에 남은 시간만 크게, 아주 어두운 글자로 보여 준다.
 * 화면 아무 곳이나 길게 누르면 기상([onWake]), 짧게 누르면 [onTap].
 *
 * 움직임(밝기 원칙은 그대로: 검정 바탕, 아주 어두운 색만 쓴다):
 * - 남은 시간은 숫자가 바뀔 때 그 자리만 위로 살짝 굴러간다.
 * - 호흡 안내 구간([breathingGuide])에는 숫자 뒤의 아주 어두운 원이 4초 들숨·6초 날숨으로 커졌다 작아지고,
 *   "들이마시고"/"내쉬고"가 그에 맞춰 바뀐다.
 * - 그 뒤에는 원 대신 아주 희미한 파도 선(백색소음)이 천천히 흐른다.
 *
 * 계속 움직이는 값(호흡 시계, 파도)은 그리기 단계(drawBehind, graphicsLayer)에서만 읽어 재구성하지 않는다.
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
    breathingGuide: Boolean = false,
) {
    // 호흡 원과 파도는 1.5초에 걸쳐 서로 바뀐다. 다 사라진 쪽은 무한 애니메이션을 멈춘다.
    val breathAlpha = animateFloatAsState(
        targetValue = if (breathingGuide) 1f else 0f,
        animationSpec = tween(AMBIENCE_CROSSFADE_MILLIS, easing = LinearEasing),
        label = "breathAlpha",
    )
    val breathActive by remember { derivedStateOf { breathAlpha.value > 0f } }
    val waveActive by remember { derivedStateOf { breathAlpha.value < 1f } }
    val breathClock: State<Float>? = if (breathActive) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 0f,
            targetValue = BREATH_CYCLE_MILLIS.toFloat(),
            animationSpec = infiniteRepeatable(tween(BREATH_CYCLE_MILLIS, easing = LinearEasing)),
            label = "breathClock",
        )
    } else {
        null
    }
    val waveClock: State<Float>? = if (waveActive) rememberFlowClock(WAVE_PERIOD_MILLIS, label = "noiseWave") else null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NapColors.SleepBackground)
            .drawBehind {
                waveClock?.let { drawNoiseWaves(it.value, 1f - breathAlpha.value) }
            }
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
            Box(
                modifier = Modifier.drawBehind {
                    breathClock?.let { drawBreathCircle(it.value, breathAlpha.value) }
                },
                contentAlignment = Alignment.Center,
            ) {
                RollingClock(
                    text = formatClock(remainingSeconds),
                    style = NapType.Countdown,
                    color = NapColors.SleepText,
                )
            }
            BreathWords(breathClock, breathAlpha)
            Text(
                text = stringResource(R.string.sleep_hint_long_press),
                style = NapType.Body,
                color = NapColors.SleepTextDim,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            enter = fadeIn(tween(NapMotion.SHORT)) + slideInVertically(tween(NapMotion.MEDIUM, easing = NapMotion.Enter)) { it / 4 },
            exit = fadeOut(tween(NapMotion.SHORT)),
            label = "sleepControls",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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

/**
 * 남은 시간. 글자마다 따로 그려, 바뀐 숫자만 아래에서 위로 살짝 굴러 올라온다(옛 숫자는 위로 사라진다).
 * 자릿수가 줄어도(10:00 → 9:59) 오른쪽 끝 자리부터 맞춰 둔다. 접근성과 테스트를 위해 전체 문자열을 의미로 붙인다.
 */
@Composable
private fun RollingClock(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        (
                            slideInVertically(tween(DIGIT_ROLL_MILLIS, easing = NapMotion.Enter)) { it / 4 } +
                                fadeIn(tween(DIGIT_ROLL_MILLIS, easing = LinearEasing))
                            ) togetherWith (
                            slideOutVertically(tween(DIGIT_ROLL_MILLIS, easing = NapMotion.Enter)) { -it / 4 } +
                                fadeOut(tween(DIGIT_ROLL_MILLIS * 2 / 3, easing = LinearEasing))
                            ) using SizeTransform(clip = false)
                    },
                    label = "clockDigit",
                ) { shown ->
                    Text(text = shown.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}

/**
 * "들이마시고"/"내쉬고". 두 문구를 겹쳐 두고 호흡 시계에 맞춰 투명도만 바꾼다(그리기 단계에서만 읽는다).
 * 자리는 늘 차지해서 호흡 안내가 끝나도 숫자가 움직이지 않는다.
 */
@Composable
private fun BreathWords(breathClock: State<Float>?, breathAlpha: State<Float>) {
    Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
        if (breathClock != null) {
            Text(
                text = stringResource(R.string.sleep_breathe_in),
                style = NapType.Title,
                color = NapColors.SleepText,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = breathAlpha.value * inhaleWordAlpha(breathClock.value) },
            )
            Text(
                text = stringResource(R.string.sleep_breathe_out),
                style = NapType.Title,
                color = NapColors.SleepText,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = breathAlpha.value * exhaleWordAlpha(breathClock.value) },
            )
        }
    }
}

/** 호흡 원 크기(0~1). 들숨 4초 동안 커지고 날숨 6초 동안 작아진다. */
private fun breathSize(clockMillis: Float): Float = if (clockMillis < INHALE_MILLIS) {
    NapMotion.Calm.at(clockMillis / INHALE_MILLIS)
} else {
    1f - NapMotion.Calm.at((clockMillis - INHALE_MILLIS) / EXHALE_MILLIS)
}

/** 바뀌는 순간보다 조금 일찍 다음 문구로 넘어가 미리 알려 준다. 주기의 시작(0)에서는 "들이마시고"가 온전히 보인다. */
private fun inhaleWordAlpha(clockMillis: Float): Float =
    1f - smoothStep(3_600f, 4_200f, clockMillis) + smoothStep(9_600f, 10_000f, clockMillis)

private fun exhaleWordAlpha(clockMillis: Float): Float =
    smoothStep(3_800f, 4_400f, clockMillis) - smoothStep(9_400f, 10_000f, clockMillis)

/** 숫자 뒤의 호흡 원. 가장자리가 부드럽게 사라지는 아주 어두운 원이다. */
private fun DrawScope.drawBreathCircle(clockMillis: Float, alpha: Float) {
    if (alpha <= 0f) return
    val base = size.width * 0.5f
    val radius = base * (0.62f + 0.38f * breathSize(clockMillis))
    drawCircle(
        brush = Brush.radialGradient(
            0f to BreathCore,
            0.7f to BreathCore.copy(alpha = 0.85f),
            1f to BreathCore.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        alpha = alpha,
    )
}

/** 백색소음 파도: 화면 아래쪽에 아주 희미한 사인 곡선 세 줄이 천천히 오른쪽으로 흐른다. */
private fun DrawScope.drawNoiseWaves(phase: Float, alpha: Float) {
    if (alpha <= 0f) return
    val strokeWidth = 1.5.dp.toPx()
    for (line in 0 until 3) {
        val baseY = size.height * (0.74f + line * 0.035f)
        val amplitude = 9.dp.toPx() + line * 3.dp.toPx()
        val wavelength = size.width * (0.55f + line * 0.12f)
        // 줄마다 속도와 출발점을 조금씩 달리한다.
        val shift = (phase * (1f + line * 0.35f) + line * 0.27f) * 2f * PI.toFloat()
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y = baseY + amplitude * sin(x / wavelength * 2f * PI.toFloat() - shift)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = WaveLine,
            alpha = alpha * (0.55f - line * 0.12f),
            style = Stroke(width = strokeWidth),
        )
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
                breathingGuide = true,
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
