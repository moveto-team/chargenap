package com.chargenap.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapMotion
import com.chargenap.app.vehicle.ChargingState
import kotlin.math.cos
import kotlin.math.sin

/** 아이콘 라이브러리 없이 Canvas로 그리는 간단한 도형들. */
enum class Glyph { Check, Cross, Dash, Question, Plus, Minus, Alert }

/**
 * 원 안에 [glyph]를 그린다. [filled]면 원을 [tint]의 옅은 색으로 채우고, 아니면 테두리만 그린다.
 *
 * @param progress 그려진 정도(0~1). 체크·가위표·줄은 펜으로 긋듯이 길이가 자라고, 나머지는 서서히 나타난다.
 *   그리기 단계에서만 읽으므로 움직이는 동안 재구성하지 않는다
 */
@Composable
fun GlyphBadge(
    glyph: Glyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    filled: Boolean = true,
    progress: () -> Float = { 1f },
) {
    Canvas(modifier.size(size)) {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f) return@Canvas
        val stroke = this.size.minDimension * 0.085f
        // 원은 그림보다 조금 먼저 나타난다.
        val circleAlpha = (p * 1.8f).coerceAtMost(1f)
        if (filled) {
            drawCircle(color = tint.copy(alpha = 0.16f * circleAlpha))
        } else {
            drawCircle(
                color = tint.copy(alpha = 0.55f * circleAlpha),
                style = Stroke(width = stroke * 0.6f),
                radius = this.size.minDimension / 2 - stroke,
            )
        }
        drawGlyph(glyph, tint, stroke, progress = p)
    }
}

/** 배경 없이 [glyph]만 그린다. 스테퍼의 +/− 버튼에 쓴다. */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Canvas(modifier.size(size)) {
        drawGlyph(glyph, tint, this.size.minDimension * 0.11f, inset = 0.12f)
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, strokeWidth: Float, inset: Float = 0.3f, progress: Float = 1f) {
    val w = size.width
    val h = size.height
    fun at(x: Float, y: Float) = Offset(w * x, h * y)

    /** [from]→[to] 선을 [fraction]만큼만 긋는다. */
    fun line(from: Offset, to: Offset, fraction: Float = 1f) {
        if (fraction <= 0f) return
        drawLine(
            color = tint,
            start = from,
            end = from + (to - from) * fraction.coerceAtMost(1f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }

    val low = inset
    val high = 1f - inset
    val strokeStyle = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
        Glyph.Check -> {
            val path = Path().apply {
                moveTo(w * 0.29f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.67f)
                lineTo(w * 0.72f, h * 0.36f)
            }
            drawPath(partialPath(path, progress), tint, style = strokeStyle)
        }
        Glyph.Cross -> {
            line(at(0.34f, 0.34f), at(0.66f, 0.66f), progress * 2f)
            line(at(0.66f, 0.34f), at(0.34f, 0.66f), progress * 2f - 1f)
        }
        Glyph.Dash -> line(at(0.32f, 0.5f), at(0.68f, 0.5f), progress)
        Glyph.Minus -> line(at(low, 0.5f), at(high, 0.5f))
        Glyph.Plus -> {
            line(at(low, 0.5f), at(high, 0.5f))
            line(at(0.5f, low), at(0.5f, high))
        }
        Glyph.Alert -> {
            val faded = tint.copy(alpha = tint.alpha * progress)
            drawLine(faded, at(0.5f, 0.28f), at(0.5f, 0.56f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawCircle(color = faded, radius = strokeWidth * 0.7f, center = at(0.5f, 0.71f))
        }
        Glyph.Question -> {
            val faded = tint.copy(alpha = tint.alpha * progress)
            val path = Path().apply {
                moveTo(w * 0.39f, h * 0.40f)
                cubicTo(w * 0.39f, h * 0.24f, w * 0.62f, h * 0.24f, w * 0.62f, h * 0.40f)
                cubicTo(w * 0.62f, h * 0.50f, w * 0.50f, h * 0.50f, w * 0.50f, h * 0.60f)
            }
            drawPath(path, faded, style = strokeStyle)
            drawCircle(color = faded, radius = strokeWidth * 0.7f, center = at(0.5f, 0.74f))
        }
    }
}

/** [path]의 앞에서부터 [fraction]만큼만 잘라 낸다. 1이면 그대로 돌려준다. */
private fun partialPath(path: Path, fraction: Float): Path {
    if (fraction >= 1f) return path
    val measure = PathMeasure()
    measure.setPath(path, false)
    return Path().also { measure.getSegment(0f, measure.length * fraction, it, true) }
}

/**
 * 잔량 고리. 12시 방향에서 시계 방향으로 채운다. 충전 중·충전 완료일 때만 노랑이다.
 * [targetPercent] 위치에 목표 잔량 눈금을 그린다.
 *
 * @param fill 채울 양(0~100). 애니메이션 값을 넘기면 그리기 단계에서만 읽는다. null이면 [percent] 그대로
 * @param pulse 충전 중이면 true. 고리 끝에 아주 약한 빛이 천천히 숨 쉬듯 커졌다 작아진다
 */
@Composable
fun ChargeRing(
    percent: Int?,
    chargingState: ChargingState,
    targetPercent: Int,
    modifier: Modifier = Modifier,
    fill: (() -> Float)? = null,
    pulse: Boolean = false,
) {
    val active = chargingState == ChargingState.CHARGING || chargingState == ChargingState.COMPLETE
    val progressColor = if (active) NapColors.Charge else NapColors.TextTertiary
    // 무한 애니메이션은 rememberInfiniteTransition으로만 만든다(테스트 시계에서는 첫 값으로 멈춘다).
    val glow: State<Float>? = if (pulse && percent != null) {
        rememberInfiniteTransition(label = "chargePulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = NapMotion.Calm), RepeatMode.Reverse),
            label = "chargePulseGlow",
        )
    } else {
        null
    }
    Canvas(modifier) {
        val strokeWidth = size.minDimension * 0.065f
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = NapColors.SurfaceRaised,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth),
        )
        if (percent != null) {
            val value = (fill?.invoke() ?: percent.toFloat()).coerceIn(0f, 100f)
            val sweep = 360f * value / 100f
            if (sweep > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            if (glow != null && sweep > 0f) {
                // 고리 끝의 맥동: 아주 약하게. 충전 중이라는 것만 느껴지게 한다.
                val g = glow.value
                val end = Math.toRadians(-90.0 + sweep)
                val tip = center + Offset(cos(end).toFloat(), sin(end).toFloat()) * (diameter / 2f)
                drawCircle(
                    color = progressColor.copy(alpha = 0.10f + 0.16f * g),
                    radius = strokeWidth * (0.75f + 0.45f * g),
                    center = tip,
                )
            }
        }
        // 목표 잔량 눈금: 고리 바깥쪽의 짧은 선.
        val angle = Math.toRadians((-90.0 + 360.0 * targetPercent.coerceIn(0, 100) / 100.0))
        val outer = size.minDimension / 2f
        val inner = outer - strokeWidth
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(
            color = NapColors.TextPrimary,
            start = center + direction * (inner - strokeWidth * 0.25f),
            end = center + direction * (outer + strokeWidth * 0.05f),
            strokeWidth = strokeWidth * 0.22f,
            cap = StrokeCap.Round,
        )
    }
}

private const val PULSE_MILLIS = 1_800
