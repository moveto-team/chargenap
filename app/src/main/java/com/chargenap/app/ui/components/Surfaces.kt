package com.chargenap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType

/** 큰 카드. 화면의 한 덩어리 내용을 담는다. */
@Composable
fun NapCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(36.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(NapColors.Surface)
            .border(1.dp, NapColors.Outline, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** 이름표 + 큰 수치 + 단위. 브리핑과 리포트에 쓴다. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = NapColors.TextPrimary,
    caption: String? = null,
) {
    NapCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 26.dp)) {
        Column {
            Text(text = label, style = NapType.BodySmall, color = NapColors.TextSecondary, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = NapType.Stat,
                    color = valueColor,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (unit != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = unit,
                        style = NapType.Title,
                        color = NapColors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (caption != null) {
                Spacer(Modifier.height(6.dp))
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
}

/** 설정·리포트 화면의 머리줄: 뒤로 가기 + 제목. */
@Composable
fun ScreenHeader(title: String, backLabel: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
        BigButton(
            text = backLabel,
            onClick = onBack,
            style = BigButtonStyle.Ghost,
            height = 76.dp,
            small = true,
        )
        Spacer(Modifier.width(28.dp))
        Text(text = title, style = NapType.H2, color = NapColors.TextPrimary, maxLines = 1)
    }
}
