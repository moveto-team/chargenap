package com.chargenap.app.vehicle.pleos

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AAOS Car API에서 읽은 보강 신호. 쓸 수 없는 속성(없음·권한 없음·읽기 실패)은 null.
 *
 * @param chargeStateAvailable `EV_CHARGE_STATE`를 쓸 수 있는지(속성이 있고 권한이 부여됨)
 * @param chargeState `android.car.hardware.property.EvChargeState` 값([AaosEvChargeState])
 */
internal data class AaosSignals(
    val chargeStateAvailable: Boolean = false,
    val chargeState: Int? = null,
    val portConnected: Boolean? = null,
    val chargeTimeRemainingSec: Int? = null,
    val gearInPark: Boolean? = null,
    val parkingBrakeOn: Boolean? = null,
) {
    companion object {
        val NONE = AaosSignals()
    }
}

/**
 * AAOS `GEAR_SELECTION` 값 → P 여부(docs/sdk-notes.md 13절, `android.car.VehicleGear`).
 * `GEAR_UNKNOWN`(0)은 "P가 아님"이 아니라 "모름"(null)이다. false로 바꾸면 기어를 잠깐 못 읽은 것을
 * "P를 벗어남"으로 보고 세션을 중단하게 된다.
 */
internal fun aaosGearInPark(gear: Int): Boolean? = when (gear) {
    VehicleGear.GEAR_UNKNOWN -> null
    VehicleGear.GEAR_PARK -> true
    else -> false
}

/**
 * Pleos Vehicle SDK가 주지 않는 값을 AAOS `CarPropertyManager`로 보강한다(docs/sdk-notes.md 13절).
 * 주차 브레이크, 커넥터 연결, 충전 상태, 남은 충전 시간, 기어(SDK 기어를 못 읽을 때의 대체).
 *
 * - 자동차가 아닌 기기에는 `android.car` 클래스가 없다. `FEATURE_AUTOMOTIVE`를 먼저 확인하고
 *   모든 접근을 `catch (t: Throwable)`로 감싼다(`NoClassDefFoundError` 포함).
 * - 속성마다 "권한이 부여됨"이고 "`getCarPropertyConfig(id) != null`"일 때만 쓴다.
 *   `android.car.permission.CAR_ENERGY`는 dangerous라 처음에는 없을 수 있다. 부여된 뒤 [refresh]를 다시 부르면 반영된다.
 * - 콜백은 값이 바뀌었다는 신호로만 쓰고([changes]), 값은 [read]가 다시 조회한다.
 * - 아무 속성도 못 쓰는 환경에서는 [read]가 [AaosSignals.NONE]을 돌려줄 뿐이다. 예외를 던지지 않는다.
 */
internal class CarPropertyAdapter(private val context: Context) {

    private enum class Property(val permission: String) {
        GEAR("android.car.permission.CAR_POWERTRAIN"),
        PARKING_BRAKE("android.car.permission.CAR_POWERTRAIN"),
        PORT_CONNECTED("android.car.permission.CAR_ENERGY_PORTS"),
        CHARGE_STATE("android.car.permission.CAR_ENERGY"),
        CHARGE_TIME_REMAINING("android.car.permission.CAR_ENERGY"),
    }

    private val lock = Any()
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private val available = mutableSetOf<Property>()
    private val registered = mutableSetOf<Property>()
    private val lastValues = mutableMapOf<Property, Any>()
    private val failures = mutableMapOf<Property, Int>()

    private val _changes = MutableStateFlow(0L)

    /** 등록한 속성 중 하나라도 바뀌거나 오류가 나면 값이 올라간다. 값 자체는 [read]로 다시 읽는다. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /**
     * Car 서비스에 연결하고(아직이면) 쓸 수 있는 속성을 다시 확인해 콜백을 등록한다.
     * 여러 번 불러도 된다. 권한이 새로 부여된 속성이 이때 추가된다.
     */
    fun refresh() {
        synchronized(lock) {
            try {
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
                val propertyManager = connectLocked() ?: return
                for (property in Property.entries) {
                    if (property in available) continue
                    if (!isUsable(propertyManager, property)) continue
                    available += property
                }
                registerLocked(propertyManager)
            } catch (t: Throwable) {
                // android.car 클래스가 없거나 Car 서비스가 응답하지 않는다. SDK 값만으로 동작한다.
            }
        }
    }

    /** 쓸 수 있는 속성의 현재 값. 읽기에 실패한 항목은 null. */
    fun read(): AaosSignals {
        val (propertyManager, usable) = synchronized(lock) { manager to available.toSet() }
        if (propertyManager == null || usable.isEmpty()) return AaosSignals.NONE
        return try {
            AaosSignals(
                chargeStateAvailable = Property.CHARGE_STATE in usable,
                chargeState = readInt(propertyManager, usable, Property.CHARGE_STATE),
                portConnected = readBoolean(propertyManager, usable, Property.PORT_CONNECTED),
                chargeTimeRemainingSec = readInt(propertyManager, usable, Property.CHARGE_TIME_REMAINING),
                gearInPark = readInt(propertyManager, usable, Property.GEAR)?.let(::aaosGearInPark),
                parkingBrakeOn = readBoolean(propertyManager, usable, Property.PARKING_BRAKE),
            )
        } catch (t: Throwable) {
            AaosSignals.NONE
        }
    }

    /** 콜백을 해제하고 Car 서비스 연결을 끊는다. 다시 쓰려면 [refresh]를 부른다. */
    fun disconnect() {
        synchronized(lock) {
            try {
                val registeredCallback = callback
                if (registeredCallback != null) manager?.unregisterCallback(registeredCallback)
            } catch (t: Throwable) {
                // 이미 끊긴 연결
            }
            try {
                car?.disconnect()
            } catch (t: Throwable) {
                // 이미 끊긴 연결
            }
            car = null
            manager = null
            callback = null
            available.clear()
            registered.clear()
            lastValues.clear()
            failures.clear()
        }
    }

    private fun connectLocked(): CarPropertyManager? {
        manager?.let { return it }
        val connected = Car.createCar(context) ?: return null
        val propertyManager = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (propertyManager == null) {
            connected.disconnect()
            return null
        }
        car = connected
        manager = propertyManager
        return propertyManager
    }

    private fun isUsable(propertyManager: CarPropertyManager, property: Property): Boolean = try {
        context.checkSelfPermission(property.permission) == PackageManager.PERMISSION_GRANTED &&
            propertyManager.getCarPropertyConfig(idOf(property)) != null
    } catch (t: Throwable) {
        false
    }

    @Suppress("DEPRECATION") // API 34 에뮬레이터에는 subscribePropertyEvents가 없다(docs/sdk-notes.md 13절)
    private fun registerLocked(propertyManager: CarPropertyManager) {
        val eventCallback = callback ?: object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                _changes.update { it + 1 }
            }

            override fun onErrorEvent(propId: Int, zone: Int) {
                _changes.update { it + 1 }
            }
        }.also { callback = it }

        for (property in available) {
            if (property in registered) continue
            try {
                if (propertyManager.registerCallback(eventCallback, idOf(property), CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                    registered += property
                }
            } catch (t: Throwable) {
                // 구독은 못 해도 주기적 재조회로 값은 읽는다
            }
        }
    }

    private fun readInt(propertyManager: CarPropertyManager, usable: Set<Property>, property: Property): Int? =
        readSteady(usable, property) { propertyManager.getIntProperty(idOf(property), GLOBAL_AREA_ID) }

    private fun readBoolean(propertyManager: CarPropertyManager, usable: Set<Property>, property: Property): Boolean? =
        readSteady(usable, property) { propertyManager.getBooleanProperty(idOf(property), GLOBAL_AREA_ID) }

    /**
     * 읽기가 한 번 실패하면 직전 값을 유지하고, [UNREADABLE_AFTER_FAILURES]번 연달아 실패하면 null로 바꾼다.
     * 일시적인 읽기 오류 한 번에 충전 상태 판정이 AAOS 값에서 SDK 추론으로 넘어갔다 돌아오는 것을 막는다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> readSteady(usable: Set<Property>, property: Property, read: () -> T): T? {
        if (property !in usable) return null
        return try {
            val value = read()
            synchronized(lock) {
                lastValues[property] = value
                failures[property] = 0
            }
            value
        } catch (t: Throwable) {
            synchronized(lock) {
                val failed = (failures[property] ?: 0) + 1
                failures[property] = failed
                if (failed < UNREADABLE_AFTER_FAILURES) lastValues[property] as T? else null
            }
        }
    }

    private fun idOf(property: Property): Int = when (property) {
        Property.GEAR -> VehiclePropertyIds.GEAR_SELECTION
        Property.PARKING_BRAKE -> VehiclePropertyIds.PARKING_BRAKE_ON
        Property.PORT_CONNECTED -> VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED
        Property.CHARGE_STATE -> VehiclePropertyIds.EV_CHARGE_STATE
        Property.CHARGE_TIME_REMAINING -> VehiclePropertyIds.EV_CHARGE_TIME_REMAINING
    }

    private companion object {
        /** 전역 속성의 areaId(docs/sdk-notes.md 13절). */
        const val GLOBAL_AREA_ID = 0
        const val UNREADABLE_AFTER_FAILURES = 2
    }
}
