package com.chargenap.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 움직임 규칙. 밤의 차 안이라 차분하게 움직인다: 감속 곡선 위주, 바운스 없는 스프링(감쇠 0.85),
 * 길이는 250~700ms. 수면 진입·기상처럼 분위기가 바뀌는 곳만 900ms 이상 쓴다.
 *
 * 무한 애니메이션은 반드시 `rememberInfiniteTransition`으로만 만든다. Compose 테스트는 무한 애니메이션을
 * 테스트 시계에서 돌리지 않는데(InfiniteAnimationPolicy), `LaunchedEffect` 안의 `while (true)` 루프는
 * 그 정책을 거치지 않아 `waitForIdle()`이 끝나지 않는다.
 *
 * 순서대로 나타나는 지연은 코루틴 `delay`가 아니라 `tween(delayMillis = …)`로 준다. 지연이 애니메이션 안에
 * 있어야 테스트의 `waitForIdle()`이 끝까지 흘려 보내 스냅샷이 최종 모습이 된다.
 */
object NapMotion {
    /** 들어오는 것: 빠르게 시작해 천천히 멈춘다. */
    val Enter: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 나가는 것: 천천히 시작해 빠르게 사라진다. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** 오가는 것(호흡 원, 맥동). 사인 곡선에 가깝다. */
    val Calm: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    const val SHORT = 250
    const val MEDIUM = 450
    const val LONG = 700

    /** 수면 진입: 화면이 천천히 어두워진다. */
    const val SLEEP_DIM = 900

    /** 기상: 화면이 밝아진다. */
    const val WAKE_BRIGHTEN = 900

    /** 중단: 빠르고 단호하게. */
    const val ABORT = 200

    /** 숫자 세기. */
    const val COUNT = 900

    /** 저감쇠 스프링. 눌림 같은 작은 반응에 쓴다. */
    fun <T> gentleSpring(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
}

/**
 * 처음 놓일 때 0에서 1로 가는 진행도. [delayMillis]를 기다렸다가 [durationMillis] 동안 [easing]으로 움직인다.
 * 미리보기(`LocalInspectionMode`)에서는 처음부터 1이다. [enabled]가 false여도 처음부터 1이다.
 */
@Composable
fun rememberEntrance(
    delayMillis: Int = 0,
    durationMillis: Int = NapMotion.MEDIUM,
    easing: Easing = NapMotion.Enter,
    enabled: Boolean = true,
): State<Float> {
    val skip = LocalInspectionMode.current || !enabled
    val progress = remember { Animatable(if (skip) 1f else 0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis, delayMillis, easing))
    }
    return progress.asState()
}

/**
 * [progress]에 따라 [rise]만큼 아래에서 떠오르며 나타난다. 값은 그리기 단계(graphicsLayer)에서만 읽으므로
 * 움직이는 동안 재구성하지 않는다.
 */
fun Modifier.entrance(progress: State<Float>, rise: Dp = 24.dp): Modifier = graphicsLayer {
    val p = progress.value
    alpha = p.coerceIn(0f, 1f)
    translationY = (1f - p) * rise.toPx()
}

/**
 * [target]까지 세어 가는 정수. 처음 놓일 때 [from]에서 시작하고, 나중에 [target]이 바뀌면 지금 값에서 이어서 센다.
 * 미리보기에서는 처음부터 [target]이다.
 */
@Composable
fun animatedCount(
    target: Int,
    from: Int = 0,
    durationMillis: Int = NapMotion.COUNT,
    delayMillis: Int = 0,
): Int {
    val skip = LocalInspectionMode.current
    val value = remember { Animatable(if (skip) target.toFloat() else from.toFloat()) }
    LaunchedEffect(target) {
        value.animateTo(target.toFloat(), tween(durationMillis, delayMillis, NapMotion.Enter))
    }
    return value.value.roundToInt()
}

/** [value]를 구간 [start]~[end]에서 0~1로 바꾼 뒤 부드럽게(smoothstep) 만든다. */
fun smoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 선형 진행도를 이 곡선으로 바꾼다. 그리기 단계에서 진행도 하나로 여러 값을 만들 때 쓴다. */
fun Easing.at(fraction: Float): Float = transform(fraction.coerceIn(0f, 1f))
