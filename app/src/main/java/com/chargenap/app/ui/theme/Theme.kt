package com.chargenap.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * 밤의 차 안에서 쓰는 색. 거의 검은 남색 바탕, 채도 낮은 표면, 포인트 색 하나(하늘색).
 * 노랑([Charge])은 충전 표시에만, [Alert]는 확인이 필요한 항목에만 쓴다.
 */
object NapColors {
    val Background = Color(0xFF060A12)
    val Surface = Color(0xFF0F1622)
    val SurfaceRaised = Color(0xFF182233)
    val Outline = Color(0xFF263247)

    val TextPrimary = Color(0xFFD9DFEA)
    val TextSecondary = Color(0xFF95A2B9)
    val TextTertiary = Color(0xFF66738C)

    val Accent = Color(0xFF86BDEB)
    val AccentContainer = Color(0xFF16304A)
    val OnAccent = Color(0xFF06131F)

    val Charge = Color(0xFFF0C25A)
    val Alert = Color(0xFFE39C86)

    // 수면 화면: OLED 검정 위에 아주 어두운 글자.
    val SleepBackground = Color(0xFF000000)
    val SleepText = Color(0xFF3D485F)
    val SleepTextDim = Color(0xFF2A3345)
    val SleepSurface = Color(0xFF0A0E16)
    val SleepOutline = Color(0xFF161C28)
}

/**
 * 글자 크기. 가로 1280dp 설계 캔버스 기준이다([DesignCanvas]). 본문은 24sp 이상.
 */
object NapType {
    private fun style(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        lineHeight: Float = 1.3f,
        tabular: Boolean = false,
    ) = TextStyle(
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * lineHeight).sp,
        fontFeatureSettings = if (tabular) "tnum" else null,
    )

    /** 수면 화면의 남은 시간. */
    val Countdown = style(200, FontWeight.Light, lineHeight = 1.1f, tabular = true)

    /** 홈의 잔량 숫자. */
    val Hero = style(100, FontWeight.Medium, lineHeight = 1.1f, tabular = true)

    /** 브리핑·리포트의 큰 수치. */
    val Stat = style(64, FontWeight.Medium, lineHeight = 1.15f, tabular = true)

    val H1 = style(46, FontWeight.SemiBold, lineHeight = 1.25f)
    val H2 = style(38, FontWeight.SemiBold, lineHeight = 1.25f)
    val Title = style(31, FontWeight.Medium)
    val Body = style(27)
    val BodySmall = style(24)
    val Caption = style(22)
    val Button = style(30, FontWeight.SemiBold, lineHeight = 1.2f)
    val ButtonSmall = style(25, FontWeight.Medium, lineHeight = 1.2f)
    val Badge = style(20, FontWeight.Bold, lineHeight = 1.2f).copy(letterSpacing = 3.sp)
}

private val NapColorScheme = darkColorScheme(
    primary = NapColors.Accent,
    onPrimary = NapColors.OnAccent,
    primaryContainer = NapColors.AccentContainer,
    onPrimaryContainer = NapColors.Accent,
    secondary = NapColors.Charge,
    background = NapColors.Background,
    onBackground = NapColors.TextPrimary,
    surface = NapColors.Surface,
    onSurface = NapColors.TextPrimary,
    surfaceVariant = NapColors.SurfaceRaised,
    onSurfaceVariant = NapColors.TextSecondary,
    outline = NapColors.Outline,
    error = NapColors.Alert,
)

@Composable
fun ChargeNapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NapColorScheme, content = content)
}

/** 설계 캔버스의 기준 크기(dp). 16:9 화면이면 정확히 이 크기가 된다. */
const val DESIGN_WIDTH_DP = 1280f
const val DESIGN_HEIGHT_DP = 720f

/**
 * 화면 전체를 [background]로 칠하고, 시스템 바를 피한 영역을 **가로 1280dp 기준 설계 캔버스**로 만든다.
 *
 * Pleos Connect 화면은 2,560×1,440px에 160dpi라 1dp = 1px이다. 일반 dp 값으로 그리면 전부 깨알같이
 * 작아지므로 `LocalDensity`를 덮어써 어떤 밀도·해상도에서도 같은 비율로 보이게 한다.
 * 16:9보다 납작한 영역(시스템 바가 넓거나 초광폭 화면)에서는 세로 720dp를 확보하도록 세로 기준으로 맞춘다.
 * 따라서 안쪽 내용은 항상 1280×720dp 이상의 공간을 쓴다.
 */
@Composable
fun DesignCanvas(
    modifier: Modifier = Modifier,
    background: Color = NapColors.Background,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val scale = minOf(
            constraints.maxWidth / DESIGN_WIDTH_DP,
            constraints.maxHeight / DESIGN_HEIGHT_DP,
        ).coerceAtLeast(0.25f)
        CompositionLocalProvider(LocalDensity provides Density(density = scale, fontScale = 1f)) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}
