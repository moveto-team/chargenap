package com.chargenap.app.vehicle

enum class ChargingState { UNKNOWN, NOT_CONNECTED, CONNECTED_IDLE, CHARGING, COMPLETE }

/** 값을 읽지 못하면 null. */
data class ParkState(val gearInPark: Boolean?, val parkingBrakeOn: Boolean?)

data class VehicleCapabilities(
    val batteryPercent: Boolean,
    val chargingState: Boolean,
    val remainingChargeTime: Boolean,
    val seatRecline: Boolean,
    val climate: Boolean,
    val doorState: Boolean,
    val isDemo: Boolean,          // Fake 구현이면 true → 화면에 DEMO 배지
) {
    companion object {
        val NONE = VehicleCapabilities(
            batteryPercent = false,
            chargingState = false,
            remainingChargeTime = false,
            seatRecline = false,
            climate = false,
            doorState = false,
            isDemo = false,
        )
    }
}

/** 구현체가 채우는 불투명 값. 값이 null인 항목은 복구할 때 건드리지 않는다. */
data class CabinSnapshot(val seat: Any?, val climate: Any?)

/**
 * 수면용 캐빈 프리셋.
 *
 * @param reclineLevel 0..100. 차량이 허용하는 운전석 등받이 가동 범위의 %.
 *   **0은 "시트를 움직이지 않음"**이다(seatApplied = false, 오류 아님).
 * @param temperatureC 섭씨. 게이트웨이가 차량이 지원하는 범위로 맞춘다.
 * @param fanLevel 1..5 추상 단계. 게이트웨이가 차량 풍량 범위에 비례해 바꾼다.
 */
data class NapPreset(val reclineLevel: Int, val temperatureC: Float, val fanLevel: Int)

/**
 * 캐빈 제어 결과. 실패해도 예외를 던지지 않고 여기에 담는다.
 *
 * @param seatApplied 시트를 실제로 움직였으면 true
 * @param climateApplied 공조 값을 실제로 바꿨으면 true
 * @param errors "seat: 사유" 또는 "climate: 사유" 형식. 접두어는 [ERROR_SEAT], [ERROR_CLIMATE]
 */
data class CabinApplyResult(val seatApplied: Boolean, val climateApplied: Boolean, val errors: List<String>) {
    val seatFailed: Boolean get() = errors.any { it.startsWith(ERROR_SEAT) }
    val climateFailed: Boolean get() = errors.any { it.startsWith(ERROR_CLIMATE) }

    companion object {
        const val ERROR_SEAT = "seat"
        const val ERROR_CLIMATE = "climate"
        val NOTHING = CabinApplyResult(seatApplied = false, climateApplied = false, errors = emptyList())
    }
}
