package com.chargenap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType

/** 설정 한 줄의 왼쪽: 이름과 (있으면) 설명. */
@Composable
private fun SettingLabel(label: String, caption: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = NapType.Title,
            color = if (enabled) NapColors.TextPrimary else NapColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = NapType.Caption,
                color = NapColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 값을 한 단계씩 올리고 내리는 줄: 이름 | − 값 +.
 *
 * @param caption 줄 아래에 붙는 설명. 조절 버튼 때문에 이름 칸이 좁아 줄 전체 너비로 따로 쓴다
 * @param enabled false면 값과 버튼을 흐리게 하고 누를 수 없다
 */
@Composable
fun StepperRow(
    label: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingLabel(label, null, enabled, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            StepButton(Glyph.Minus, decreaseLabel, enabled && canDecrease, onDecrease)
            Text(
                text = valueText,
                style = NapType.H2,
                color = if (enabled) NapColors.TextPrimary else NapColors.TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(140.dp),
            )
            StepButton(Glyph.Plus, increaseLabel, enabled && canIncrease, onIncrease)
        }
        if (caption != null) {
            Text(
                text = caption,
                style = NapType.Caption,
                color = NapColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = (-10).dp),
            )
        }
    }
}

/** 이 차량에서 쓸 수 없는 설정 줄: 이름과 그 아래 안내 문구. 조절 버튼이나 스위치는 그리지 않는다. */
@Composable
fun UnavailableRow(label: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 96.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = NapType.Title,
            color = NapColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = message,
            style = NapType.BodySmall,
            color = NapColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepButton(glyph: Glyph, description: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(shape)
            .background(NapColors.SurfaceRaised)
            .border(1.5.dp, NapColors.Outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph = glyph, tint = if (enabled) NapColors.TextPrimary else NapColors.TextTertiary.copy(alpha = 0.5f))
    }
}

/** 켜고 끄는 줄: 이름 | 스위치. 줄 전체가 터치 영역이다. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(20.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabel(label, caption, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        BigSwitch(checked = checked, enabled = enabled)
    }
}

/** 큰 스위치. 터치는 [ToggleRow]가 받는다. */
@Composable
private fun BigSwitch(checked: Boolean, enabled: Boolean) {
    val track = when {
        !enabled -> NapColors.SurfaceRaised
        checked -> NapColors.Accent
        else -> NapColors.SurfaceRaised
    }
    val thumb = when {
        !enabled -> NapColors.TextTertiary.copy(alpha = 0.5f)
        checked -> NapColors.OnAccent
        else -> NapColors.TextSecondary
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .size(width = 116.dp, height = 64.dp)
            .clip(shape)
            .background(track)
            .border(1.5.dp, if (checked && enabled) NapColors.Accent else NapColors.Outline, shape)
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = if (checked) 52.dp else 0.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}
