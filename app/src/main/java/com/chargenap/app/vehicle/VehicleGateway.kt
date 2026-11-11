package com.chargenap.app.vehicle

import kotlinx.coroutines.flow.StateFlow

/**
 * 차량 SDK를 감싸는 유일한 창구. 다른 계층은 이 인터페이스만 본다.
 *
 * 공통 규칙
 * - 값을 읽지 못하면 null(또는 [ChargingState.UNKNOWN])이다. 예외를 던지지 않는다.
 * - 캐빈 제어 함수는 실패를 [CabinApplyResult.errors]로 돌려준다.
 * - 캐빈 제어 함수가 취소되면(코루틴 취소) 진행 중이던 시트 이동을 멈춘다.
 */
interface VehicleGateway {
    val chargingState: StateFlow<ChargingState>
    val batteryPercent: StateFlow<Int?>
    val remainingChargeMinutes: StateFlow<Int?>   // SDK가 주지 않으면 항상 null
    val parkState: StateFlow<ParkState>
    val anyDoorOpen: StateFlow<Boolean?>
    val capabilities: StateFlow<VehicleCapabilities>

    /** 구독을 시작한다. 차량에 연결하지 못하면 예외를 던진다(호출자가 Fake로 대체). */
    suspend fun start()

    fun stop()

    /** [restoreCabin]이 정확히 되돌릴 수 있는 현재 시트·공조 값을 담는다. */
    suspend fun captureCabinSnapshot(): CabinSnapshot

    suspend fun applyNapPreset(preset: NapPreset): CabinApplyResult

    /** 기상용 서늘한 바람: 마지막 수면 프리셋 대비 온도 −2℃, 풍량 +2단계. 시트는 건드리지 않는다. */
    suspend fun applyWakePreset(): CabinApplyResult

    /** 스냅샷의 seat/climate가 null이면 그 항목은 건드리지 않는다. */
    suspend fun restoreCabin(snapshot: CabinSnapshot): CabinApplyResult

    suspend fun rangeRemainingKm(): Int?
}
