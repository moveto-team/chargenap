package com.chargenap.app.vehicle.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 운전석 좌석 값. **좌핸들 가정**이다. SDK에는 운전석 위치를 알려 주는 API가 없다(docs/sdk-notes.md 5.1).
 * 우핸들 차량을 지원하려면 여기만 바꾼다.
 */
internal val DRIVER_SEAT: SeatValue = SeatValue.ROW_1_LEFT

/**
 * 운전석 등받이. [positionArea]와 [moveArea]는 각각의 capability가 돌려준 키 그대로다.
 * [min] = 완전히 눕힌 위치, [max] = 가장 세운 위치(가정. [SeatDirection] 참고).
 */
internal data class SeatCapability(
    val positionArea: SeatArea,
    val moveArea: SeatArea,
    val min: Int,
    val max: Int,
)

/** 운전석이 포함된 공조 영역. 항목이 null이면 그 항목은 제어하지 않는다. */
internal data class ClimateCapability(
    val temperatureArea: SeatArea?,
    val temperature: TemperatureSpec?,
    val fanArea: SeatArea?,
    val fan: FanSpec?,
    val powerArea: SeatArea?,
    val autoArea: SeatArea?,
) {
    val temperatureControllable: Boolean get() = temperatureArea != null && temperature != null
    val fanControllable: Boolean get() = fanArea != null && fan != null

    /** 온도 또는 풍량 중 하나라도 제어할 수 있는지. */
    val controllable: Boolean get() = temperatureControllable || fanControllable

    companion object {
        val NONE = ClimateCapability(null, null, null, null, null, null)
    }
}

/** 감시할 승객 도어 영역과 그 닫힘 위치(capability의 min). */
internal data class DoorTarget(val area: DoorArea, val closedPosition: Int)

internal data class PleosCapabilities(
    val batteryReadable: Boolean,
    val chargeSwitchReadable: Boolean,
    val chargePortReadable: Boolean,
    val seat: SeatCapability?,
    val climate: ClimateCapability,
    val doors: List<DoorTarget>,
)

internal val AccessType.readable: Boolean get() = this == AccessType.READ || this == AccessType.READ_WRITE
internal val AccessType.writable: Boolean get() = this == AccessType.WRITE || this == AccessType.READ_WRITE

/**
 * capability가 돌려준 영역 키 중 운전석이 들어 있는 것.
 *
 * @param driverOnly true면 운전석 **하나만** 담은 키만 고른다. 시트는 운전석만 움직여야 하므로
 *   여러 좌석이 한 영역으로 묶인 차량에서는 시트를 제어하지 않는다. 공조는 묶인 영역도 쓴다.
 */
internal fun driverSeatArea(areas: Collection<SeatArea>, driverOnly: Boolean): SeatArea? =
    areas.firstOrNull { area ->
        if (driverOnly) area.values.size == 1 && area.values[0] == DRIVER_SEAT else area.values.contains(DRIVER_SEAT)
    }

/** 트렁크(REAR)·후드(HOOD)만으로 된 영역은 승객 도어가 아니다. */
internal fun isPassengerDoor(area: DoorArea): Boolean =
    area.values.any { it != DoorValue.HOOD && it != DoorValue.REAR }

/**
 * 도메인별 capability를 한꺼번에 조회한다. 실패·타임아웃·접근 불가는 모두 "미지원"이다(docs/sdk-notes.md 8.2).
 *
 * 오버로드 주의: 같은 이름의 Deprecated 오버로드가 있어서 람다 인자를 명시해야 새 시그니처가 잡힌다(8.1).
 *
 * @param batteryReadable 연결 판정에 쓴 잔량 capability의 결과
 */
internal suspend fun probeCapabilities(
    vehicle: Vehicle,
    sdk: SdkCalls,
    batteryReadable: Boolean,
): PleosCapabilities = coroutineScope {
    val evBattery = vehicle.getEvBattery()
    val seat = vehicle.getSeat()
    val hvac = vehicle.getHVAC()
    val door = vehicle.getDoor()

    val chargeSwitch = async {
        sdk.request<CapabilityConfig> { complete, fail ->
            evBattery.checkEvChargeSwitchCapabilities(onComplete = complete, onFailed = fail)
        }.valueOrNull()?.accessType?.readable == true
    }
    val chargePort = async {
        sdk.request<CapabilityConfig> { complete, fail ->
            evBattery.checkEvChargePortOpenCapabilities(onComplete = complete, onFailed = fail)
        }.valueOrNull()?.accessType?.readable == true
    }
    val seatPosition = async {
        sdk.request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { complete, fail ->
            seat.checkSeatBackrestAnglePositionCapabilities(
                onComplete = { config, bounds -> complete(config to bounds) },
                onFailed = fail,
            )
        }.valueOrNull()
    }
    val seatMove = async {
        sdk.request<Pair<CapabilityConfig, List<SeatArea>>> { complete, fail ->
            seat.checkMoveSeatBackrestAngleCapabilities(
                onComplete = { config, areas -> complete(config to areas) },
                onFailed = fail,
            )
        }.valueOrNull()
    }
    val temperature = async {
        sdk.request<Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>> { complete, fail ->
            hvac.checkHvacTemperatureCapabilities(
                onComplete = { config, bounds, configArray -> complete(Triple(config, bounds, configArray)) },
                onFailed = fail,
            )
        }.valueOrNull()
    }
    val fan = async {
        sdk.request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { complete, fail ->
            hvac.checkHvacFanSpeedCapabilities(
                onComplete = { config, bounds -> complete(config to bounds) },
                onFailed = fail,
            )
        }.valueOrNull()
    }
    val power = async {
        sdk.request<Pair<CapabilityConfig, List<SeatArea>>> { complete, fail ->
            hvac.checkHvacPowerCapabilities(
                onComplete = { config, areas -> complete(config to areas) },
                onFailed = fail,
            )
        }.valueOrNull()
    }
    val auto = async {
        sdk.request<Pair<CapabilityConfig, List<SeatArea>>> { complete, fail ->
            hvac.checkHvacAutoCapabilities(
                onComplete = { config, areas -> complete(config to areas) },
                onFailed = fail,
            )
        }.valueOrNull()
    }
    val doors = async {
        sdk.request<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { complete, fail ->
            door.checkDoorPositionCapabilities(
                onComplete = { config, bounds -> complete(config to bounds) },
                onFailed = fail,
            )
        }.valueOrNull()
    }

    PleosCapabilities(
        batteryReadable = batteryReadable,
        chargeSwitchReadable = chargeSwitch.await(),
        chargePortReadable = chargePort.await(),
        seat = seatCapability(seatPosition.await(), seatMove.await()),
        climate = climateCapability(temperature.await(), fan.await(), power.await(), auto.await()),
        doors = doorTargets(doors.await()),
    )
}

/** 운전석 등받이: 위치를 읽을 수 있고, 범위가 유효하고, 이동 API를 쓸 수 있어야 한다. */
private fun seatCapability(
    position: Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>?,
    move: Pair<CapabilityConfig, List<SeatArea>>?,
): SeatCapability? {
    if (position == null || move == null) return null
    if (!position.first.accessType.readable || !move.first.accessType.writable) return null
    val positionArea = driverSeatArea(position.second.keys, driverOnly = true) ?: return null
    val moveArea = driverSeatArea(move.second, driverOnly = true) ?: return null
    val bounds = position.second[positionArea] ?: return null
    val min = bounds.minInt() ?: return null
    val max = bounds.maxInt() ?: return null
    if (!isValidBackrestRange(min, max)) return null
    return SeatCapability(positionArea = positionArea, moveArea = moveArea, min = min, max = max)
}

/** 설정 뒤 다시 읽어 확인해야 하므로 읽기·쓰기가 모두 되는 항목만 제어 대상으로 삼는다. */
private fun climateCapability(
    temperature: Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>?,
    fan: Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>?,
    power: Pair<CapabilityConfig, List<SeatArea>>?,
    auto: Pair<CapabilityConfig, List<SeatArea>>?,
): ClimateCapability {
    var temperatureArea: SeatArea? = null
    var temperatureSpec: TemperatureSpec? = null
    if (temperature != null && temperature.first.accessType.readWrite) {
        val area = driverSeatArea(temperature.second.keys, driverOnly = false)
        val bounds = area?.let { temperature.second[it] }
        val min = bounds?.minFloat()
        val max = bounds?.maxFloat()
        if (area != null && min != null && max != null && min <= max) {
            temperatureArea = area
            temperatureSpec = TemperatureSpec(
                min = min,
                max = max,
                step = temperature.third?.incrementCelsius?.takeIf { it > 0f },
            )
        }
    }

    var fanArea: SeatArea? = null
    var fanSpec: FanSpec? = null
    if (fan != null && fan.first.accessType.readWrite) {
        val area = driverSeatArea(fan.second.keys, driverOnly = false)
        val bounds = area?.let { fan.second[it] }
        val min = bounds?.minInt()
        val max = bounds?.maxInt()
        // 켜 둘 수 있는 단계가 하나도 없는 범위(예: 0..0)는 제어 대상이 아니다
        if (area != null && min != null && max != null && vehicleFanSpeed(FAN_LEVEL_MIN, min, max) != null) {
            fanArea = area
            fanSpec = FanSpec(min = min, max = max)
        }
    }

    return ClimateCapability(
        temperatureArea = temperatureArea,
        temperature = temperatureSpec,
        fanArea = fanArea,
        fan = fanSpec,
        powerArea = power?.takeIf { it.first.accessType.readWrite }?.let { driverSeatArea(it.second, driverOnly = false) },
        autoArea = auto?.takeIf { it.first.accessType.readWrite }?.let { driverSeatArea(it.second, driverOnly = false) },
    )
}

private fun doorTargets(doors: Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>?): List<DoorTarget> {
    if (doors == null || !doors.first.accessType.readable) return emptyList()
    return doors.second.entries
        .filter { isPassengerDoor(it.key) }
        .mapNotNull { (area, bounds) -> bounds.minInt()?.let { DoorTarget(area, closedPosition = it) } }
}

private val AccessType.readWrite: Boolean get() = this == AccessType.READ_WRITE

// 범위 값은 SDK가 서비스가 알려 준 타입으로 변환해 담는다. 선언된 타입과 다르게 올 가능성에 대비해
// 캐스트 대신 Number로 받아 변환한다(잘못된 타입이면 ClassCastException 대신 미지원으로 처리).
private fun AreaBounds<*>.minInt(): Int? = (minValue as? Number)?.toInt()
private fun AreaBounds<*>.maxInt(): Int? = (maxValue as? Number)?.toInt()
private fun AreaBounds<*>.minFloat(): Float? = (minValue as? Number)?.toFloat()
private fun AreaBounds<*>.maxFloat(): Float? = (maxValue as? Number)?.toFloat()
