package com.chargenap.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.vehicle.ChargingState

/** 아이콘 라이브러리 없이 Canvas로 그리는 간단한 도형들. */
enum class Glyph { Check, Cross, Dash, Question, Plus, Minus, Alert }

/**
 * 원 안에 [glyph]를 그린다. [filled]면 원을 [tint]의 옅은 색으로 채우고, 아니면 테두리만 그린다.
 */
@Composable
fun GlyphBadge(
    glyph: Glyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    filled: Boolean = true,
) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.085f
        if (filled) {
            drawCircle(color = tint.copy(alpha = 0.16f))
        } else {
            drawCircle(color = tint.copy(alpha = 0.55f), style = Stroke(width = stroke * 0.6f), radius = this.size.minDimension / 2 - stroke)
        }
        drawGlyph(glyph, tint, stroke)
    }
}

/** 배경 없이 [glyph]만 그린다. 스테퍼의 +/− 버튼에 쓴다. */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Canvas(modifier.size(size)) {
        drawGlyph(glyph, tint, this.size.minDimension * 0.11f, inset = 0.12f)
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, strokeWidth: Float, inset: Float = 0.3f) {
    val w = size.width
    val h = size.height
    fun at(x: Float, y: Float) = Offset(w * x, h * y)
    fun line(from: Offset, to: Offset) =
        drawLine(color = tint, start = from, end = to, strokeWidth = strokeWidth, cap = StrokeCap.Round)

    val low = inset
    val high = 1f - inset
    when (glyph) {
        Glyph.Check -> {
            val path = Path().apply {
                moveTo(w * 0.29f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.67f)
                lineTo(w * 0.72f, h * 0.36f)
            }
            drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Glyph.Cross -> {
            line(at(0.34f, 0.34f), at(0.66f, 0.66f))
            line(at(0.66f, 0.34f), at(0.34f, 0.66f))
        }
        Glyph.Dash -> line(at(0.32f, 0.5f), at(0.68f, 0.5f))
        Glyph.Minus -> line(at(low, 0.5f), at(high, 0.5f))
        Glyph.Plus -> {
            line(at(low, 0.5f), at(high, 0.5f))
            line(at(0.5f, low), at(0.5f, high))
        }
        Glyph.Alert -> {
            line(at(0.5f, 0.28f), at(0.5f, 0.56f))
            drawCircle(color = tint, radius = strokeWidth * 0.7f, center = at(0.5f, 0.71f))
        }
        Glyph.Question -> {
            val path = Path().apply {
                moveTo(w * 0.39f, h * 0.40f)
                cubicTo(w * 0.39f, h * 0.24f, w * 0.62f, h * 0.24f, w * 0.62f, h * 0.40f)
                cubicTo(w * 0.62f, h * 0.50f, w * 0.50f, h * 0.50f, w * 0.50f, h * 0.60f)
            }
            drawPath(path, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color = tint, radius = strokeWidth * 0.7f, center = at(0.5f, 0.74f))
        }
    }
}

/**
 * 잔량 고리. 12시 방향에서 시계 방향으로 [percent]만큼 채운다. 충전 중·충전 완료일 때만 노랑이다.
 * [targetPercent] 위치에 목표 잔량 눈금을 그린다.
 */
@Composable
fun ChargeRing(
    percent: Int?,
    chargingState: ChargingState,
    targetPercent: Int,
    modifier: Modifier = Modifier,
) {
    val active = chargingState == ChargingState.CHARGING || chargingState == ChargingState.COMPLETE
    val progressColor = if (active) NapColors.Charge else NapColors.TextTertiary
    Canvas(modifier) {
        val strokeWidth = size.minDimension * 0.065f
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
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
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * percent.coerceIn(0, 100) / 100f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        // 목표 잔량 눈금: 고리 바깥쪽의 짧은 선.
        val angle = Math.toRadians((-90.0 + 360.0 * targetPercent.coerceIn(0, 100) / 100.0))
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f
        val inner = outer - strokeWidth
        val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
        drawLine(
            color = NapColors.TextPrimary,
            start = center + direction * (inner - strokeWidth * 0.25f),
            end = center + direction * (outer + strokeWidth * 0.05f),
            strokeWidth = strokeWidth * 0.22f,
            cap = StrokeCap.Round,
        )
    }
}
