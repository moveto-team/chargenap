package com.chargenap.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.chargenap.app.ui.theme.smoothStep

/**
 * 0→1을 일정한 속도로 되풀이하는 시계. 바람 곡선·파도처럼 계속 흐르는 그림에 쓴다.
 * `rememberInfiniteTransition` 기반이라 Compose 테스트 시계에서는 0에 멈춘다.
 */
@Composable
fun rememberFlowClock(periodMillis: Int, label: String): State<Float> =
    rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = label,
    )

/**
 * 공조 바람을 뜻하는 가는 곡선들. 곡선마다 희미한 자국 위로 짧은 빛줄기가 한 방향으로 흘러간다.
 * 곡선은 한 번만 만들어 두고, 그릴 때는 흐름 값([draw]의 phase)만 바뀐다.
 *
 * @param paths 바람이 흐르는 길. 시작점에서 끝점 쪽으로 흐른다
 */
class AirStreaks(private val paths: List<Path>) {
    private val measures = paths.map { path -> PathMeasure().apply { setPath(path, false) } }
    private val lengths = measures.map { it.length }
    private val segment = Path()

    /**
     * @param phase 흐름 시계(0~1)
     * @param trailAlpha 길 전체에 깔아 두는 희미한 자국의 진하기([color] 대비)
     * @param streakFraction 빛줄기 길이(곡선 길이 대비)
     */
    fun draw(
        scope: DrawScope,
        phase: Float,
        color: Color,
        strokeWidth: Float,
        trailAlpha: Float = 0.14f,
        streakFraction: Float = 0.3f,
    ) {
        for (i in paths.indices) {
            scope.drawPath(
                paths[i],
                color.copy(alpha = color.alpha * trailAlpha),
                style = Stroke(width = strokeWidth * 0.7f, cap = StrokeCap.Round),
            )
            val length = lengths[i]
            if (length <= 0f) continue
            // 줄마다 시작 시점을 엇갈리게 해서 한꺼번에 움직이지 않게 한다.
            val t = (phase + i * STAGGER) % 1f
            val streak = length * streakFraction
            val head = t * (length + streak)
            val start = (head - streak).coerceAtLeast(0f)
            val end = head.coerceAtMost(length)
            if (end - start < 1f) continue
            segment.reset()
            measures[i].getSegment(start, end, segment, true)
            // 들어올 때와 나갈 때 서서히 나타나고 사라진다.
            val fade = smoothStep(0f, 0.18f, t) * (1f - smoothStep(0.82f, 1f, t))
            scope.drawPath(
                segment,
                color.copy(alpha = color.alpha * fade),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }

    private companion object {
        const val STAGGER = 0.31f
    }
}
