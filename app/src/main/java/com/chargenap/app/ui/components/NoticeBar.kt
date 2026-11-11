package com.chargenap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType

/** 화면 아래에 잠깐 뜨는 한 줄 안내. 누르면 바로 사라진다. [dim]이면 수면 화면에 맞춰 어둡게 그린다. */
@Composable
fun NoticeBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .widthIn(max = 900.dp)
            .clip(shape)
            .background(if (dim) NapColors.SleepSurface else NapColors.SurfaceRaised)
            .border(1.5.dp, if (dim) NapColors.SleepOutline else NapColors.Outline, shape)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 36.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NapType.Body,
            color = if (dim) NapColors.SleepText else NapColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
