package com.chargenap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType

/**
 * 가상 차량으로 동작 중임을 알리는 배지. 루트에서 한 번만 그린다.
 * [dim]이면 수면 화면에 맞춰 어둡게 그린다(그래도 읽을 수는 있다).
 */
@Composable
fun DemoBadge(modifier: Modifier = Modifier, dim: Boolean = false) {
    val shape = RoundedCornerShape(50)
    val color = if (dim) NapColors.SleepText else NapColors.Alert
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (dim) NapColors.SleepBackground else NapColors.Background)
            .border(1.5.dp, color.copy(alpha = if (dim) 0.6f else 0.8f), shape)
            .padding(horizontal = 18.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = stringResource(R.string.demo_badge), style = NapType.Badge, color = color, maxLines = 1)
    }
}
