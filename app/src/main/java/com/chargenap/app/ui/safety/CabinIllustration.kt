package com.chargenap.app.ui.safety

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.components.AirStreaks
import com.chargenap.app.ui.components.rememberFlowClock
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapMotion
import com.chargenap.app.ui.theme.rememberEntrance

/** 설계 단위. 그림은 이 좌표로 그린 뒤, 실제로 그림이 있는 세로 구간(ART_TOP부터 ART_H)만 자리에 맞게 같은 비율로 키운다. */
private const val ART_W = 300f
private const val ART_TOP = 8f
private const val ART_H = 176f

/** 등받이 각도(연출용). 세운 자세와 눕힌 자세. 화면에 숫자로 보여 주지 않는다. */
private const val UPRIGHT_DEG = 12f
private const val RECLINED_DEG = 36f

/** 등받이가 젖혀지는 시간. */
private const val RECLINE_MILLIS = 1_200
private const val RECLINE_DELAY_MILLIS = 250

/** 바람이 통풍구에서 머리 쪽까지 한 번 흐르는 시간. */
private const val AIR_PERIOD_MILLIS = 2_600

/** 등받이 아래쪽 경첩(회전 중심). */
private val PIVOT = Offset(228f, 136f)

/**
 * 캐빈 준비 중 일러스트: 운전석 옆모습(앞이 왼쪽). 등받이가 세운 자세에서 눕힌 자세로 천천히 젖혀지고
 * (약 1.2초, 끝나면 그 자세 유지), 통풍구에서 좌석 쪽으로 가는 바람 곡선 세 줄이 계속 흐른다.
 *
 * 이 그림은 **연출용**이다. 실제 시트 위치 값은 화면에 들어오지 않으므로 그림의 각도는 차량 값이 아니고,
 * 그래서 각도 숫자는 표시하지 않는다. 시트를 움직이지 않는 세션([reclining]이 false)에서는 등받이를 세운 채
 * 바람만 그린다.
 */
@Composable
fun CabinIllustration(reclining: Boolean, modifier: Modifier = Modifier) {
    val inspection = LocalInspectionMode.current
    val recline = remember { Animatable(if (inspection && reclining) 1f else 0f) }
    LaunchedEffect(reclining) {
        val target = if (reclining) 1f else 0f
        if (recline.value != target) {
            recline.animateTo(target, tween(RECLINE_MILLIS, RECLINE_DELAY_MILLIS, NapMotion.Calm))
        }
    }
    val airIn = rememberEntrance(delayMillis = 150, durationMillis = NapMotion.LONG)
    val air = rememberFlowClock(AIR_PERIOD_MILLIS, label = "cabinAir")
    val streaks = remember { AirStreaks(airPaths()) }

    Canvas(modifier) {
        val k = minOf(size.width / ART_W, size.height / ART_H)
        val left = (size.width - ART_W * k) / 2f
        val top = (size.height - ART_H * k) / 2f
        withTransform({
            translate(left, top - ART_TOP * k)
            scale(k, k, pivot = Offset.Zero)
        }) {
            drawCabin()
            streaks.draw(
                scope = this,
                phase = air.value,
                color = NapColors.Accent.copy(alpha = 0.75f * airIn.value),
                strokeWidth = 2.4f,
            )
            drawSeat(angle = UPRIGHT_DEG + (RECLINED_DEG - UPRIGHT_DEG) * recline.value)
        }
    }
}

/** 바닥, 대시보드(통풍구), 운전대. 움직이지 않는 부분. */
private fun DrawScope.drawCabin() {
    val line = NapColors.Outline
    // 바닥
    drawLine(line, Offset(18f, 178f), Offset(288f, 178f), strokeWidth = 2f, cap = StrokeCap.Round)
    // 대시보드: 왼쪽 위의 둥근 덩어리. 오른쪽 끝이 통풍구다.
    val dash = Path().apply {
        moveTo(0f, 60f)
        lineTo(56f, 60f)
        quadraticTo(74f, 60f, 74f, 76f)
        lineTo(74f, 82f)
        quadraticTo(74f, 98f, 56f, 98f)
        lineTo(0f, 98f)
        close()
    }
    drawPath(dash, NapColors.SurfaceRaised)
    drawPath(dash, line, style = Stroke(width = 1.6f))
    // 통풍구 날개
    for (y in listOf(71f, 77f, 83f)) {
        drawLine(NapColors.TextTertiary, Offset(66f, y), Offset(73f, y), strokeWidth = 1.6f, cap = StrokeCap.Round)
    }
    // 운전대: 기둥과 옆에서 본 바퀴 테두리
    drawLine(line, Offset(40f, 96f), Offset(86f, 118f), strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(NapColors.TextTertiary, Offset(80f, 96f), Offset(96f, 142f), strokeWidth = 6f, cap = StrokeCap.Round)
}

/** 좌석: 다리, 방석, 등받이와 머리 받침. 등받이는 경첩을 중심으로 [angle]도 뒤로 젖혀진다. */
private fun DrawScope.drawSeat(angle: Float) {
    val fill = lerp(NapColors.SurfaceRaised, NapColors.Accent, 0.12f)
    val edge = NapColors.Accent.copy(alpha = 0.5f)
    val edgeStroke = Stroke(width = 1.6f)
    // 다리(레일)
    drawRoundRect(NapColors.Outline, Offset(160f, 148f), Size(52f, 30f), CornerRadius(4f))
    // 방석
    drawRoundRect(fill, Offset(128f, 126f), Size(110f, 24f), CornerRadius(11f))
    drawRoundRect(edge, Offset(128f, 126f), Size(110f, 24f), CornerRadius(11f), style = edgeStroke)
    // 등받이 + 머리 받침: 경첩을 중심으로 돌린다(시계 방향 = 뒤로 젖힘).
    rotate(degrees = angle, pivot = PIVOT) {
        val back = Offset(PIVOT.x - 20f, PIVOT.y - 94f)
        drawRoundRect(fill, back, Size(26f, 100f), CornerRadius(12f))
        drawRoundRect(edge, back, Size(26f, 100f), CornerRadius(12f), style = edgeStroke)
        val head = Offset(PIVOT.x - 17f, PIVOT.y - 120f)
        drawRoundRect(fill, head, Size(20f, 20f), CornerRadius(8f))
        drawRoundRect(edge, head, Size(20f, 20f), CornerRadius(8f), style = edgeStroke)
    }
}

/** 통풍구에서 좌석 위쪽으로 가는 바람 길 세 줄(설계 단위). */
private fun airPaths(): List<Path> = listOf(
    Path().apply {
        moveTo(78f, 70f)
        cubicTo(118f, 54f, 158f, 50f, 200f, 56f)
    },
    Path().apply {
        moveTo(78f, 77f)
        cubicTo(122f, 70f, 162f, 80f, 206f, 76f)
    },
    Path().apply {
        moveTo(78f, 84f)
        cubicTo(118f, 96f, 156f, 104f, 196f, 98f)
    },
)

// ---------------------------------------------------------------- 미리보기

@Preview(widthDp = 480, heightDp = 320, backgroundColor = 0xFF0F1622, showBackground = true)
@Composable
private fun CabinIllustrationPreview() {
    ChargeNapTheme {
        CabinIllustration(reclining = true, modifier = Modifier.size(480.dp, 320.dp))
    }
}
