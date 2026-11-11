package com.chargenap.app.session

import com.chargenap.app.vehicle.NapPreset

/**
 * 세션 엔진이 쓰는 사용자 설정.
 *
 * @param preset 수면 프리셋. `reclineLevel`이 리클라인 상한(기본 60%)이다
 * @param targetPercent 충전 목표 잔량. 남은 충전 시간 추정에 쓴다
 * @param seatControlEnabled false면 시트를 절대 움직이지 않고 뒷좌석도 묻지 않는다
 */
data class SessionConfig(
    val preset: NapPreset = NapPreset(60, 23f, 2),
    val targetPercent: Int = 80,
    val seatControlEnabled: Boolean = true,
)
