package com.chargenap.app.voice

/**
 * 지금 앱이 어떤 대답을 기다리고 있는가. 같은 말도 맥락에 따라 뜻이 달라진다.
 *
 * - [GENERAL]: 질문하지 않은 상태. "응"/"아니" 같은 대답은 뜻이 없다.
 * - [OFFER]: 예/아니오를 기다리는 중(파워냅 제안, 브리핑 확인).
 * - [REAR_SEAT_QUESTION]: "뒷좌석에 사람이나 짐이 있나요?"의 답을 기다리는 중.
 * - [DOOR_QUESTION]: "문이 모두 닫혀 있나요?"의 답을 기다리는 중.
 */
enum class ParseContext { GENERAL, OFFER, REAR_SEAT_QUESTION, DOOR_QUESTION }

sealed interface VoiceIntent {
    /** "20분만 재워줘", "재워줘". [minutes]는 말한 그대로다(범위를 자르는 것은 세션 엔진). 숫자가 없으면 null. */
    data class Start(val minutes: Int?) : VoiceIntent

    /** "5분 더". 숫자가 없으면 5. */
    data class Extend(val minutes: Int) : VoiceIntent

    /** "깨워줘", "그만", "일어날래". */
    data object Wake : VoiceIntent

    /** "몇 분 남았어?" */
    data object Status : VoiceIntent

    data object Yes : VoiceIntent

    data object No : VoiceIntent

    /** 뒷좌석 질문에 "없어요". 시트를 움직여도 된다. */
    data object RearSeatClear : VoiceIntent

    /** 뒷좌석 질문에 "있어요". 시트를 움직이지 않는다. */
    data object RearSeatOccupied : VoiceIntent

    /** 알아듣지 못했거나 애매하다. 안전한 쪽(아무것도 하지 않음)으로 다룬다. */
    data object Unknown : VoiceIntent
}
