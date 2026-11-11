package com.chargenap.app.data

import com.chargenap.app.session.SessionConfig
import com.chargenap.app.vehicle.NapPreset
import kotlin.math.roundToInt

/**
 * 사용자가 바꿀 수 있는 설정. [SettingsRepository]가 DataStore에 저장한다.
 *
 * 범위와 단위는 [normalized]가 강제한다.
 *
 * @param reclineLevel 수면 프리셋 등받이 눕힘 정도(%). 20..100, 10 단위
 * @param temperatureC 수면 프리셋 온도(℃). 18..28, 0.5 단위
 * @param fanLevel 수면 프리셋 풍량 단계. 1..5
 * @param targetPercent 충전 목표 잔량(%). 50..100, 5 단위
 * @param seatControlEnabled false면 시트를 움직이지 않는다
 * @param demoMode true면 가상 차량으로 실행한다
 * @param voiceEnabled false면 음성 안내와 음성 인식을 쓰지 않는다
 */
data class AppSettings(
    val reclineLevel: Int = 60,
    val temperatureC: Float = 23f,
    val fanLevel: Int = 2,
    val targetPercent: Int = 80,
    val seatControlEnabled: Boolean = true,
    val demoMode: Boolean = false,
    val voiceEnabled: Boolean = true,
) {
    companion object {
        const val RECLINE_MIN = 20
        const val RECLINE_MAX = 100
        const val RECLINE_STEP = 10

        const val TEMPERATURE_MIN = 18f
        const val TEMPERATURE_MAX = 28f
        const val TEMPERATURE_STEP = 0.5f

        const val FAN_MIN = 1
        const val FAN_MAX = 5

        const val TARGET_MIN = 50
        const val TARGET_MAX = 100
        const val TARGET_STEP = 5
    }
}

/** 값을 허용 범위로 자르고 단위(10%, 0.5℃, 5%)에 맞춘다. */
fun AppSettings.normalized(): AppSettings = copy(
    reclineLevel = snap(reclineLevel, AppSettings.RECLINE_STEP)
        .coerceIn(AppSettings.RECLINE_MIN, AppSettings.RECLINE_MAX),
    temperatureC = snapTemperature(temperatureC),
    fanLevel = fanLevel.coerceIn(AppSettings.FAN_MIN, AppSettings.FAN_MAX),
    targetPercent = snap(targetPercent, AppSettings.TARGET_STEP)
        .coerceIn(AppSettings.TARGET_MIN, AppSettings.TARGET_MAX),
)

/** 세션 엔진에 넘길 설정으로 바꾼다. `AppContainer`가 설정 Flow를 엔진의 config로 이을 때 쓴다. */
fun AppSettings.toSessionConfig(): SessionConfig = SessionConfig(
    preset = NapPreset(reclineLevel = reclineLevel, temperatureC = temperatureC, fanLevel = fanLevel),
    targetPercent = targetPercent,
    seatControlEnabled = seatControlEnabled,
)

private fun snap(value: Int, step: Int): Int = (value.toFloat() / step).roundToInt() * step

private fun snapTemperature(value: Float): Float {
    if (value.isNaN()) return AppSettings().temperatureC
    val clamped = value.coerceIn(AppSettings.TEMPERATURE_MIN, AppSettings.TEMPERATURE_MAX)
    return (clamped / AppSettings.TEMPERATURE_STEP).roundToInt() * AppSettings.TEMPERATURE_STEP
}
