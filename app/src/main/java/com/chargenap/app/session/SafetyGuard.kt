package com.chargenap.app.session

import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.NapPreset
import com.chargenap.app.vehicle.ParkState
import com.chargenap.app.vehicle.VehicleCapabilities

/** 세션의 시작·중단 조건과 시트 이동 허용 여부를 판정한다 (킥오프 4.4). 상태가 없는 순수 함수 모음. */
object SafetyGuard {

    /**
     * 시작 조건 점검.
     *
     * | 항목 | PASS | FAIL | 그 밖 |
     * | --- | --- | --- | --- |
     * | 기어 | P 확인(`true`) | P 아님 또는 못 읽음 → 시작 불가 | |
     * | 주차 브레이크 | 체결 | 풀림 → 대기 | 못 읽음 → SKIPPED |
     * | 충전 | 충전 중 또는 커넥터 연결 | 그 밖 전부 → 시작 불가 | |
     * | 도어 | 모두 닫힘 | 열림 → 대기 | 못 읽음 → NEEDS_USER_CONFIRM |
     */
    fun evaluateStart(park: ParkState, charging: ChargingState, anyDoorOpen: Boolean?): SafetyReport = SafetyReport(
        gear = if (park.gearInPark == true) CheckResult.PASS else CheckResult.FAIL,
        parkingBrake = when (park.parkingBrakeOn) {
            true -> CheckResult.PASS
            false -> CheckResult.FAIL
            null -> CheckResult.SKIPPED
        },
        charging = when (charging) {
            ChargingState.CHARGING, ChargingState.CONNECTED_IDLE -> CheckResult.PASS
            else -> CheckResult.FAIL
        },
        doors = when (anyDoorOpen) {
            false -> CheckResult.PASS
            true -> CheckResult.FAIL
            null -> CheckResult.NEEDS_USER_CONFIRM
        },
    )

    /**
     * 진행 중인 세션을 중단해야 하는 이유. 없으면 null.
     * 값이 null(읽기 실패)인 것은 "벗어남"이 아니므로 중단하지 않는다. 기어가 도어보다 먼저다.
     */
    fun abortReason(gearInPark: Boolean?, anyDoorOpen: Boolean?): AbortReason? = when {
        gearInPark == false -> AbortReason.GEAR_LEFT_PARK
        anyDoorOpen == true -> AbortReason.DOOR_OPENED
        else -> null
    }

    /** 이 세션에서 시트를 움직일 수 있는 조건인가(사용자 동의는 따로 본다). */
    fun seatControlAvailable(config: SessionConfig, capabilities: VehicleCapabilities, mode: SessionMode): Boolean =
        config.seatControlEnabled && capabilities.seatRecline && mode == SessionMode.NAP && config.preset.reclineLevel > 0

    /** 뒷좌석 질문이 필요한가. 시트를 움직일 일이 없으면 묻지 않는다. */
    fun shouldAskRearSeat(config: SessionConfig, capabilities: VehicleCapabilities, mode: SessionMode): Boolean =
        seatControlAvailable(config, capabilities, mode)

    /**
     * 게이트웨이에 넘길 수면 프리셋. [seatAllowed]가 false면 `reclineLevel = 0`(시트를 움직이지 않음)으로 바꾼다.
     * 리클라인은 0..100으로 자른다.
     */
    fun napPreset(preset: NapPreset, seatAllowed: Boolean): NapPreset =
        preset.copy(reclineLevel = if (seatAllowed) preset.reclineLevel.coerceIn(0, 100) else 0)
}
