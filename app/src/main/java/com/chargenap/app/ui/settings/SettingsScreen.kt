package com.chargenap.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chargenap.app.R
import com.chargenap.app.data.AppSettings
import com.chargenap.app.ui.NapActions
import com.chargenap.app.ui.NoopNapActions
import com.chargenap.app.ui.components.NapCard
import com.chargenap.app.ui.components.ScreenHeader
import com.chargenap.app.ui.components.StepperRow
import com.chargenap.app.ui.components.ToggleRow
import com.chargenap.app.ui.components.UnavailableRow
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.theme.NapType
import com.chargenap.app.vehicle.VehicleCapabilities
import java.util.Locale

/**
 * 설정. 왼쪽은 수면 프리셋(등받이, 온도, 풍량, 시트 자동 조절), 오른쪽은 일반(목표 잔량, 음성, 데모 모드).
 * 값은 바꾸는 즉시 저장된다([NapActions]의 set 함수 → `SettingsRepository`).
 *
 * 차량이 시트·공조 제어를 지원하지 않으면 해당 줄에 조절 버튼 대신 안내 문구를 보여 준다.
 *
 * @param demoModeLocked true면 데모 모드 스위치를 누를 수 없다(세션 진행 중)
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    capabilities: VehicleCapabilities,
    actions: NapActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    demoModeLocked: Boolean = false,
) {
    val unsupported = stringResource(R.string.settings_unsupported)
    Column(modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
        ScreenHeader(
            title = stringResource(R.string.settings_title),
            backLabel = stringResource(R.string.action_back),
            onBack = onBack,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            SettingsCard(stringResource(R.string.settings_group_preset), Modifier.weight(1f).fillMaxHeight()) {
                val reclineLabel = stringResource(R.string.settings_recline)
                if (capabilities.seatRecline) {
                    StepperRow(
                        label = reclineLabel,
                        valueText = stringResource(R.string.settings_value_percent, settings.reclineLevel),
                        onDecrease = { actions.setReclineLevel(settings.reclineLevel - AppSettings.RECLINE_STEP) },
                        onIncrease = { actions.setReclineLevel(settings.reclineLevel + AppSettings.RECLINE_STEP) },
                        decreaseLabel = stringResource(R.string.settings_decrease, reclineLabel),
                        increaseLabel = stringResource(R.string.settings_increase, reclineLabel),
                        // 시트 자동 조절을 끄면 눕힘 정도는 쓰이지 않는다.
                        enabled = settings.seatControlEnabled,
                        canDecrease = settings.reclineLevel > AppSettings.RECLINE_MIN,
                        canIncrease = settings.reclineLevel < AppSettings.RECLINE_MAX,
                    )
                } else {
                    UnavailableRow(reclineLabel, unsupported)
                }

                val temperatureLabel = stringResource(R.string.settings_temperature)
                val fanLabel = stringResource(R.string.settings_fan)
                if (capabilities.climate) {
                    StepperRow(
                        label = temperatureLabel,
                        valueText = stringResource(R.string.settings_value_temperature, formatTemperature(settings.temperatureC)),
                        onDecrease = { actions.setTemperatureC(settings.temperatureC - AppSettings.TEMPERATURE_STEP) },
                        onIncrease = { actions.setTemperatureC(settings.temperatureC + AppSettings.TEMPERATURE_STEP) },
                        decreaseLabel = stringResource(R.string.settings_decrease, temperatureLabel),
                        increaseLabel = stringResource(R.string.settings_increase, temperatureLabel),
                        canDecrease = settings.temperatureC > AppSettings.TEMPERATURE_MIN,
                        canIncrease = settings.temperatureC < AppSettings.TEMPERATURE_MAX,
                    )
                    StepperRow(
                        label = fanLabel,
                        valueText = stringResource(R.string.settings_value_fan, settings.fanLevel),
                        onDecrease = { actions.setFanLevel(settings.fanLevel - 1) },
                        onIncrease = { actions.setFanLevel(settings.fanLevel + 1) },
                        decreaseLabel = stringResource(R.string.settings_decrease, fanLabel),
                        increaseLabel = stringResource(R.string.settings_increase, fanLabel),
                        canDecrease = settings.fanLevel > AppSettings.FAN_MIN,
                        canIncrease = settings.fanLevel < AppSettings.FAN_MAX,
                    )
                } else {
                    UnavailableRow(temperatureLabel, unsupported)
                    UnavailableRow(fanLabel, unsupported)
                }

                val seatControlLabel = stringResource(R.string.settings_seat_control)
                if (capabilities.seatRecline) {
                    ToggleRow(
                        label = seatControlLabel,
                        caption = stringResource(R.string.settings_seat_control_caption),
                        checked = settings.seatControlEnabled,
                        onCheckedChange = actions::setSeatControlEnabled,
                    )
                } else {
                    UnavailableRow(seatControlLabel, unsupported)
                }
            }

            SettingsCard(stringResource(R.string.settings_group_general), Modifier.weight(1f).fillMaxHeight()) {
                val targetLabel = stringResource(R.string.settings_target)
                StepperRow(
                    label = targetLabel,
                    valueText = stringResource(R.string.settings_value_percent, settings.targetPercent),
                    onDecrease = { actions.setTargetPercent(settings.targetPercent - AppSettings.TARGET_STEP) },
                    onIncrease = { actions.setTargetPercent(settings.targetPercent + AppSettings.TARGET_STEP) },
                    decreaseLabel = stringResource(R.string.settings_decrease, targetLabel),
                    increaseLabel = stringResource(R.string.settings_increase, targetLabel),
                    caption = stringResource(R.string.settings_target_caption),
                    canDecrease = settings.targetPercent > AppSettings.TARGET_MIN,
                    canIncrease = settings.targetPercent < AppSettings.TARGET_MAX,
                )
                ToggleRow(
                    label = stringResource(R.string.settings_voice),
                    caption = stringResource(R.string.settings_voice_caption),
                    checked = settings.voiceEnabled,
                    onCheckedChange = actions::setVoiceEnabled,
                )
                ToggleRow(
                    label = stringResource(R.string.settings_demo),
                    caption = stringResource(R.string.settings_demo_caption),
                    checked = settings.demoMode,
                    onCheckedChange = actions::setDemoMode,
                    enabled = !demoModeLocked,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    NapCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp)) {
        // 16:9보다 납작한 화면에서도 줄이 잘리지 않게 카드 안에서 스크롤한다.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(text = title, style = NapType.BodySmall, color = NapColors.Accent, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** 23.0 → "23", 23.5 → "23.5". */
internal fun formatTemperature(temperatureC: Float): String {
    val halves = Math.round(temperatureC * 2)
    return if (halves % 2 == 0) (halves / 2).toString() else String.format(Locale.US, "%.1f", halves / 2f)
}

// ---------------------------------------------------------------- 미리보기

private val previewCapabilities = VehicleCapabilities(
    batteryPercent = true,
    chargingState = true,
    remainingChargeTime = false,
    seatRecline = true,
    climate = true,
    doorState = true,
    isDemo = false,
)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun SettingsPreview() {
    ChargeNapTheme {
        DesignCanvas {
            SettingsScreen(
                settings = AppSettings(temperatureC = 23.5f),
                capabilities = previewCapabilities,
                actions = NoopNapActions,
                onBack = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun SettingsUnsupportedPreview() {
    ChargeNapTheme {
        DesignCanvas {
            SettingsScreen(
                settings = AppSettings(),
                capabilities = previewCapabilities.copy(seatRecline = false, climate = false),
                actions = NoopNapActions,
                onBack = {},
            )
        }
    }
}
