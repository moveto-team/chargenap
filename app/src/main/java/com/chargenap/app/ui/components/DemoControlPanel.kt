package com.chargenap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType

/**
 * 데모 모드 전용 조작 패널. 발표·시연에서 충전 중단, 도어 열림, 기어 변경 같은 상황을 만들어
 * 안전 가드를 보여 주는 용도다.
 *
 * 접혀 있으면 작은 버튼 하나만 보인다. 펼치면 화면 맨 위에 가로 한 줄로 놓인다. 화면마다 내용이 있는 자리가
 * 달라서(홈의 카드, 수면 화면의 남은 시간과 버튼) 어느 화면에서도 내용을 가리지 않는 곳이 머리줄뿐이다.
 *
 * @param doorOpen 지금 가상 도어가 열려 있는가. 버튼 문구가 "도어 닫기"로 바뀐다
 * @param gearInPark 지금 가상 기어가 P인가. 버튼 문구가 "기어 D로"/"기어 P로"로 바뀐다
 * @param dim 수면 화면처럼 어두운 화면에서 눈에 거슬리지 않게 아주 흐리게 그린다
 * @param leading 펼쳤을 때 줄 맨 앞에 놓을 것(DEMO 배지)
 */
@Composable
fun DemoControlPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    doorOpen: Boolean,
    gearInPark: Boolean,
    onStartCharging: () -> Unit,
    onInterruptCharging: () -> Unit,
    onCompleteCharging: () -> Unit,
    onSetDoorOpen: (Boolean) -> Unit,
    onSetGearInPark: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    leading: @Composable () -> Unit = {},
) {
    val alpha = if (dim) (if (expanded) 0.45f else 0.3f) else 1f
    if (!expanded) {
        val shape = RoundedCornerShape(50)
        Box(
            modifier = modifier
                .alpha(alpha)
                .height(76.dp)
                .clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(NapColors.Surface)
                    .border(1.5.dp, NapColors.Outline, shape)
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.demo_panel_title),
                    style = NapType.Caption,
                    color = NapColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        return
    }

    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier = modifier
            .alpha(alpha)
            .fillMaxWidth()
            .clip(shape)
            .background(NapColors.Surface)
            .border(1.5.dp, NapColors.Outline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        DemoButton(stringResource(R.string.demo_start_charging), onStartCharging, Modifier.weight(1f))
        DemoButton(stringResource(R.string.demo_interrupt_charging), onInterruptCharging, Modifier.weight(1f))
        DemoButton(stringResource(R.string.demo_complete_charging), onCompleteCharging, Modifier.weight(1f))
        DemoButton(
            text = stringResource(if (doorOpen) R.string.demo_door_close else R.string.demo_door_open),
            onClick = { onSetDoorOpen(!doorOpen) },
            modifier = Modifier.weight(1f),
        )
        DemoButton(
            text = stringResource(if (gearInPark) R.string.demo_gear_to_d else R.string.demo_gear_to_p),
            onClick = { onSetGearInPark(!gearInPark) },
            modifier = Modifier.weight(1f),
        )
        BigButton(
            text = stringResource(R.string.demo_panel_close),
            onClick = onToggle,
            style = BigButtonStyle.Ghost,
            height = 76.dp,
            small = true,
        )
    }
}

@Composable
private fun DemoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BigButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        style = BigButtonStyle.Secondary,
        height = 76.dp,
        small = true,
    )
}
