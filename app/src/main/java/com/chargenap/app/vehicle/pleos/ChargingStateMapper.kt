package com.chargenap.app.vehicle.pleos

import com.chargenap.app.vehicle.ChargingState

/**
 * AAOS `android.car.hardware.property.EvChargeState`의 값.
 * 근거: docs/sdk-notes.md 13절 표. 순수 함수와 단위 테스트가 android.car 클래스 없이 돌도록 숫자로 둔다.
 */
internal object AaosEvChargeState {
    const val UNKNOWN = 0
    const val CHARGING = 1
    const val FULLY_CHARGED = 2
    const val NOT_CHARGING = 3
    const val ERROR = 4
}

/**
 * 충전 상태 판정에 쓰는 원시 값. 읽지 못한 값은 null.
 *
 * @param aaosChargeState AAOS `EV_CHARGE_STATE`. 속성이 없거나 권한이 없으면 null
 * @param chargeSwitchOn SDK `getEvChargeSwitchState`
 * @param batteryPercent SDK 잔량(%)
 * @param aaosPortConnected AAOS `EV_CHARGE_PORT_CONNECTED`
 * @param sdkPortOpen SDK `isEvChargePortOpen`(커넥터 연결 신호가 없을 때의 대용)
 */
internal data class ChargingInputs(
    val aaosChargeState: Int? = null,
    val chargeSwitchOn: Boolean? = null,
    val batteryPercent: Int? = null,
    val aaosPortConnected: Boolean? = null,
    val sdkPortOpen: Boolean? = null,
)

/**
 * 커넥터가 꽂혀 있다고 볼지. AAOS 커넥터 연결 값이 있으면 그 값, 없으면 SDK 충전 포트 열림.
 * 둘 다 없으면 연결되지 않은 것으로 본다.
 */
internal fun isPluggedIn(aaosPortConnected: Boolean?, sdkPortOpen: Boolean?): Boolean =
    aaosPortConnected ?: sdkPortOpen ?: false

/**
 * 원시 값 → [ChargingState].
 *
 * 1. AAOS 충전 상태가 확정 값이면 그 값을 쓴다.
 * 2. 아니면(없음·UNKNOWN·ERROR) SDK 충전 스위치로 추론한다. 스위치도 읽지 못하면 UNKNOWN.
 */
internal fun mapChargingState(inputs: ChargingInputs): ChargingState {
    val plugged = isPluggedIn(inputs.aaosPortConnected, inputs.sdkPortOpen)
    when (inputs.aaosChargeState) {
        AaosEvChargeState.CHARGING -> return ChargingState.CHARGING
        AaosEvChargeState.FULLY_CHARGED -> return ChargingState.COMPLETE
        AaosEvChargeState.NOT_CHARGING ->
            return if (plugged) ChargingState.CONNECTED_IDLE else ChargingState.NOT_CONNECTED
        else -> Unit // null, UNKNOWN, ERROR, 정의되지 않은 값 → SDK 추론으로
    }
    return when (inputs.chargeSwitchOn) {
        true -> if ((inputs.batteryPercent ?: 0) >= FULL_PERCENT) ChargingState.COMPLETE else ChargingState.CHARGING
        false -> if (plugged) ChargingState.CONNECTED_IDLE else ChargingState.NOT_CONNECTED
        null -> ChargingState.UNKNOWN
    }
}

/** AAOS `EV_CHARGE_TIME_REMAINING`(초) → 분(올림). 읽지 못했거나 0 이하면 null. */
internal fun remainingChargeMinutes(seconds: Int?): Int? {
    if (seconds == null || seconds <= 0) return null
    return (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
}

private const val FULL_PERCENT = 100
private const val SECONDS_PER_MINUTE = 60
