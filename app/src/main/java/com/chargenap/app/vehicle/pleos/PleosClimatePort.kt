package com.chargenap.app.vehicle.pleos

import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit

/**
 * 운전석 공조 영역의 SDK 호출. 판단은 [ClimateController]가 한다.
 *
 * 사용 API(docs/sdk-notes.md 6절): 온도 `getHvacTemperature` / `setHvacTemperature`(항상 `CELSIUS`),
 * 풍량 `getHvacFanSpeed` / `setHvacFanSpeed`, 전원 `getHvacPowerState` / `setHvacPower`,
 * 오토 `getHvacAutoState` / `setHvacAuto`. 영역은 capability가 돌려준 키 그대로 쓴다.
 */
internal class PleosClimatePort(
    private val hvac: HVAC,
    private val sdk: SdkCalls,
    private val capability: ClimateCapability,
) : ClimatePort {

    override val temperature: TemperatureSpec? = capability.temperature.takeIf { capability.temperatureArea != null }
    override val fan: FanSpec? = capability.fan.takeIf { capability.fanArea != null }
    override val powerControllable: Boolean = capability.powerArea != null
    override val autoControllable: Boolean = capability.autoArea != null

    override suspend fun readTemperature(): Float? {
        val area = capability.temperatureArea ?: return null
        return sdk.request<Float?> { complete, fail ->
            hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, complete, fail)
        }.valueOrNull()
    }

    override suspend fun writeTemperature(celsius: Float): String? {
        val area = capability.temperatureArea ?: return UNSUPPORTED
        return sdk.request<Unit> { complete, fail ->
            hvac.setHvacTemperature(
                area = area,
                value = celsius,
                unit = TemperatureUnit.CELSIUS,
                onComplete = { _, _ -> complete(Unit) },
                onFailed = fail,
            )
        }.errorOrNull()
    }

    override suspend fun readFan(): Int? {
        val area = capability.fanArea ?: return null
        return sdk.request<Int?> { complete, fail -> hvac.getHvacFanSpeed(area, complete, fail) }.valueOrNull()
    }

    override suspend fun writeFan(speed: Int): String? {
        val area = capability.fanArea ?: return UNSUPPORTED
        return sdk.request<Unit> { complete, fail ->
            hvac.setHvacFanSpeed(area = area, value = speed, onComplete = { _, _ -> complete(Unit) }, onFailed = fail)
        }.errorOrNull()
    }

    override suspend fun readPower(): Boolean? {
        val area = capability.powerArea ?: return null
        return sdk.request<Boolean?> { complete, fail -> hvac.getHvacPowerState(area, complete, fail) }.valueOrNull()
    }

    override suspend fun writePower(on: Boolean): String? {
        val area = capability.powerArea ?: return UNSUPPORTED
        return sdk.request<Unit> { complete, fail ->
            hvac.setHvacPower(area = area, value = on, onComplete = { _, _ -> complete(Unit) }, onFailed = fail)
        }.errorOrNull()
    }

    override suspend fun readAuto(): Boolean? {
        val area = capability.autoArea ?: return null
        return sdk.request<Boolean?> { complete, fail -> hvac.getHvacAutoState(area, complete, fail) }.valueOrNull()
    }

    override suspend fun writeAuto(on: Boolean): String? {
        val area = capability.autoArea ?: return UNSUPPORTED
        return sdk.request<Unit> { complete, fail ->
            hvac.setHvacAuto(area = area, value = on, onComplete = { _, _ -> complete(Unit) }, onFailed = fail)
        }.errorOrNull()
    }

    private companion object {
        const val UNSUPPORTED = "지원하지 않는 항목"
    }
}
