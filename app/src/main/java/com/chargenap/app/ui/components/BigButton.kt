package com.chargenap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType

enum class BigButtonStyle {
    /** 화면의 주요 행동. 한 화면에 하나만 쓴다. */
    Primary,

    /** 보조 행동. */
    Secondary,

    /** 배경 없는 이동·취소 버튼. */
    Ghost,

    /** 수면 화면용. 눈부시지 않게 아주 어둡다. */
    Dim,
}

/** 터치 영역이 큰 버튼. 높이는 기본 96dp이고 76dp 아래로 줄이지 않는다. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BigButtonStyle = BigButtonStyle.Primary,
    enabled: Boolean = true,
    height: Dp = 96.dp,
    small: Boolean = false,
) {
    val shape = RoundedCornerShape(28.dp)
    val container: Color
    val content: Color
    val outline: Color?
    when (style) {
        BigButtonStyle.Primary -> {
            container = if (enabled) NapColors.Accent else NapColors.SurfaceRaised
            content = if (enabled) NapColors.OnAccent else NapColors.TextTertiary
            outline = null
        }
        BigButtonStyle.Secondary -> {
            container = NapColors.SurfaceRaised
            content = if (enabled) NapColors.TextPrimary else NapColors.TextTertiary
            outline = NapColors.Outline
        }
        BigButtonStyle.Ghost -> {
            container = Color.Transparent
            content = if (enabled) NapColors.TextSecondary else NapColors.TextTertiary
            outline = NapColors.Outline
        }
        BigButtonStyle.Dim -> {
            container = NapColors.SleepSurface
            content = NapColors.SleepText
            outline = NapColors.SleepOutline
        }
    }
    Box(
        modifier = modifier
            .height(height.coerceAtLeast(76.dp))
            .defaultMinSize(minWidth = 140.dp)
            .clip(shape)
            .background(container)
            .then(if (outline != null) Modifier.border(1.5.dp, outline, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (small) NapType.ButtonSmall else NapType.Button,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
