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

/**
 * 사람이 보고 느끼는 구간의 길이(세션 시계 기준 밀리초).
 *
 * 데모 배속에서는 세션 시계가 빨리 흐르므로, 이 구간들이 눈에 보이도록 [scaledForDemo]로 늘려서 넘긴다.
 *
 * @param breathingGuideMillis 수면 시작 뒤 호흡 안내가 끝나고 백색소음으로 넘어가는 시점
 * @param wakeBreezeMillis 기상 바람을 틀고 시트를 되돌리기 전까지 기다리는 시간
 * @param abortLingerMillis 복구가 끝난 중단 화면을 자동으로 닫기까지의 시간
 */
data class SessionTimings(
    val breathingGuideMillis: Long = 2 * 60_000L,
    val wakeBreezeMillis: Long = 20_000L,
    val abortLingerMillis: Long = 15_000L,
) {
    companion object {
        /** [clockScale]배속 시계에서 기상 바람 3초, 중단 화면 15초가 실제 시간으로 보이게 한다. */
        fun scaledForDemo(clockScale: Double): SessionTimings = SessionTimings(
            wakeBreezeMillis = (3_000 * clockScale).toLong(),
            abortLingerMillis = (15_000 * clockScale).toLong(),
        )
    }
}
