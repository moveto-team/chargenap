package com.chargenap.app.voice

import com.chargenap.app.voice.ParseContext.DOOR_QUESTION
import com.chargenap.app.voice.ParseContext.GENERAL
import com.chargenap.app.voice.ParseContext.OFFER
import com.chargenap.app.voice.ParseContext.REAR_SEAT_QUESTION
import com.chargenap.app.voice.VoiceIntent.Extend
import com.chargenap.app.voice.VoiceIntent.No
import com.chargenap.app.voice.VoiceIntent.RearSeatClear
import com.chargenap.app.voice.VoiceIntent.RearSeatOccupied
import com.chargenap.app.voice.VoiceIntent.Start
import com.chargenap.app.voice.VoiceIntent.Status
import com.chargenap.app.voice.VoiceIntent.Unknown
import com.chargenap.app.voice.VoiceIntent.Wake
import com.chargenap.app.voice.VoiceIntent.Yes
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentParserTest {

    private val allContexts = ParseContext.entries

    private fun check(expected: VoiceIntent, context: ParseContext, vararg texts: String) {
        for (text in texts) {
            assertEquals("\"$text\" ($context)", expected, IntentParser.parse(text, context))
        }
    }

    private fun checkEverywhere(expected: VoiceIntent, vararg texts: String) {
        for (context in allContexts) check(expected, context, *texts)
    }

    // ---------------------------------------------------------------- 킥오프 4.5의 예문

    @Test
    fun kickoffExamples_start() {
        check(Start(20), GENERAL, "20분만 재워줘")
        check(Start(null), GENERAL, "재워줘", "파워냅 시작")
    }

    @Test
    fun kickoffExamples_extend() {
        check(Extend(5), GENERAL, "5분 더")
    }

    @Test
    fun kickoffExamples_wake() {
        check(Wake, GENERAL, "깨워줘", "그만", "일어날래")
    }

    @Test
    fun kickoffExamples_status() {
        check(Status, GENERAL, "몇 분 남았어?")
    }

    @Test
    fun kickoffExamples_yesNo() {
        check(Yes, OFFER, "응", "좋아")
        check(No, OFFER, "아니", "됐어")
    }

    @Test
    fun kickoffExamples_rearSeat() {
        check(RearSeatClear, REAR_SEAT_QUESTION, "없어요")
        check(RearSeatOccupied, REAR_SEAT_QUESTION, "있어요")
    }

    // ---------------------------------------------------------------- 뒷좌석 질문 맥락

    @Test
    fun rearSeat_clearAnswers() {
        check(
            RearSeatClear, REAR_SEAT_QUESTION,
            "없어요", "없어", "아니", "아니요", "비어 있어",
            "없어요.", "아니요, 없어요", "아무도 없어요", "아무것도 없습니다", "비어있어요", "비었어", "아뇨",
            "아무도 안 탔어", "네 없어요", "응 없어",
        )
    }

    @Test
    fun rearSeat_occupiedAnswers() {
        check(
            RearSeatOccupied, REAR_SEAT_QUESTION,
            "있어요", "있어", "응", "네", "사람 있어", "짐 있어",
            "네, 있어요", "아니 있어", "애가 타고 있어요", "가방 실었어", "뒤에 짐이 좀 있습니다", "예",
        )
    }

    @Test
    fun rearSeat_ambiguous_isUnknown() {
        check(
            Unknown, REAR_SEAT_QUESTION,
            "잘 모르겠어", "없는 것 같기도 하고 있는 것 같기도",
            "글쎄", "글쎄요 없을걸요", "없는 것 같아", "아마 없을 거야", "없지 않아", "없지는 않아요",
            "있는지 없는지 모르겠어", "상관없어", "알 수 없어", "없나", "", "   ", "뭐라고", "오늘 날씨 어때",
        )
    }

    /** 확실하지 않아도 "있다"는 쪽은 시트를 움직이지 않으므로 그대로 받아들인다. */
    @Test
    fun rearSeat_uncertainButOccupied_staysOnSafeSide() {
        for (text in listOf("있는 것 같아", "아마 있을걸")) {
            val intent = IntentParser.parse(text, REAR_SEAT_QUESTION)
            assertEquals("\"$text\"", true, intent == RearSeatOccupied || intent == Unknown)
        }
    }

    /** "없어요"가 시트 이동 동의 말고 다른 뜻으로 새지 않는다. */
    @Test
    fun rearSeatWords_outsideRearContext_meanNothing() {
        for (context in listOf(GENERAL, OFFER, DOOR_QUESTION)) {
            check(Unknown, context, "없어요", "있어요", "없어", "있어", "비어 있어")
        }
    }

    @Test
    fun rearSeat_neverYieldsYesOrNo() {
        for (text in listOf("응", "네", "아니", "아니요", "좋아", "됐어", "괜찮아", "그래")) {
            val intent = IntentParser.parse(text, REAR_SEAT_QUESTION)
            assertEquals("\"$text\"", false, intent == Yes || intent == No)
        }
        // 제안에 대한 대답처럼 들리는 말은 뒷좌석 답이 아니다.
        check(Unknown, REAR_SEAT_QUESTION, "좋아", "됐어", "괜찮아")
    }

    @Test
    fun rearSeat_stopCommandWins() {
        check(Wake, REAR_SEAT_QUESTION, "그만", "취소", "깨워줘", "그만할래", "아니 그만")
    }

    // ---------------------------------------------------------------- 제안 맥락

    @Test
    fun offer_yes() {
        check(Yes, OFFER, "응", "좋아", "그래", "네", "네.", "좋아요", "그래요", "응 좋아", "네 좋습니다", "오케이", "예", "확인")
    }

    @Test
    fun offer_no() {
        check(No, OFFER, "아니", "됐어", "괜찮아", "나중에", "아니요", "아니 됐어", "괜찮아요", "싫어", "안 할래", "다음에 할게", "아니 안 잘래", "나중에 잘게")
    }

    @Test
    fun offer_bareNumber_startsWithThatLength() {
        check(Start(15), OFFER, "15분만", "15분", "십오 분", "십오분만", "열다섯 분", "15", "십오", "응 15분", "15분으로 할게")
        check(Start(20), OFFER, "20분", "이십 분만", "스무 분", "좋아 20분")
    }

    @Test
    fun offer_ambiguous_isUnknown() {
        check(Unknown, OFFER, "", "글쎄", "오늘 날씨 어때", "응 아니", "오", "이", "음")
    }

    // ---------------------------------------------------------------- 도어 질문 맥락

    @Test
    fun door_yes() {
        check(Yes, DOOR_QUESTION, "응", "네", "닫혀 있어", "닫혀있어요", "다 닫았어", "네 닫혔어요", "닫았어요")
    }

    @Test
    fun door_no() {
        check(No, DOOR_QUESTION, "아니", "열려 있어", "아니요", "열려있어요", "안 닫혔어", "아직 안 닫았어", "문 열렸어")
    }

    @Test
    fun door_ambiguous_isUnknown() {
        check(Unknown, DOOR_QUESTION, "", "글쎄", "모르겠어", "열려 있다가 닫혔다가", "오늘 날씨 어때")
    }

    // ---------------------------------------------------------------- 일반 맥락

    @Test
    fun general_bareYesNo_isUnknown() {
        check(Unknown, GENERAL, "응", "아니", "네", "좋아", "됐어", "그래", "괜찮아", "나중에", "아니요", "15분", "십오")
    }

    @Test
    fun emptyOrUnrelated_isUnknown_inEveryContext() {
        checkEverywhere(Unknown, "", " ", "?!", "...", "오늘 날씨 어때", "음악 틀어줘", "배고파", "안녕하세요", "창문 내려줘")
    }

    // ---------------------------------------------------------------- 어느 맥락에서나 인식하는 명령

    @Test
    fun wake_inEveryContext() {
        checkEverywhere(Wake, "깨워줘", "그만", "일어날래", "깨워 주세요", "그만할래", "이제 일어날게", "취소", "그만해 줘")
    }

    @Test
    fun start_inEveryContext() {
        checkEverywhere(Start(20), "20분만 재워줘", "이십 분만 재워 줘", "20분 재워주세요")
        checkEverywhere(Start(null), "재워줘", "파워냅 시작", "재워 주세요")
    }

    @Test
    fun extend_inEveryContext() {
        checkEverywhere(Extend(5), "5분 더", "오 분만 더", "5분 더 잘래")
        checkEverywhere(Extend(10), "10분 더", "십 분 더 재워줘")
    }

    @Test
    fun status_inEveryContext() {
        checkEverywhere(Status, "몇 분 남았어?", "몇분 남았어", "얼마나 남았어", "시간 얼마나 남았어요", "언제 일어나", "남은 시간 알려줘")
    }

    // ---------------------------------------------------------------- Start

    @Test
    fun start_variousPhrasings() {
        check(Start(20), GENERAL, "20분만 재워줘요", "20분만 재워 주세요", "20분만 재워줄래", "20분만 재워 줄래요", "20분 잘래", "20 분만 잘게", "20분 파워냅 시작")
        check(Start(null), GENERAL, "재워줘요", "재워 주세요", "재워줄래", "좀 재워줘", "파워냅 시작해 줘", "파워냅 시작해주세요", "낮잠 잘래", "잠깐 눈 좀 붙일게", "쉬고 싶어")
        check(Start(10), GENERAL, "십 분만 재워줘")
        check(Start(25), GENERAL, "이십오 분 재워줘")
    }

    @Test
    fun start_minutesPassedThroughUnclamped() {
        check(Start(3), GENERAL, "3분만 재워줘")
        check(Start(45), GENERAL, "45분 재워줘")
        check(Start(60), GENERAL, "육십 분 재워줘")
    }

    @Test
    fun start_wakeMeLater_isStart() {
        check(Start(20), GENERAL, "20분 뒤에 깨워줘", "이십 분 후에 깨워 줘", "20분 있다가 깨워줘")
    }

    @Test
    fun start_negated_isNotStart() {
        check(Unknown, GENERAL, "안 잘래", "재우지 마", "자기 싫어", "시작하지 마")
    }

    // ---------------------------------------------------------------- 숫자 표현

    @Test
    fun numbers_allSpellingsOfMinutes() {
        val cases = mapOf(
            "20분" to 20, "20 분" to 20, "이십 분" to 20, "이십분" to 20,
            "십오 분" to 15, "삼십 분" to 30, "오 분" to 5, "다섯 분" to 5,
        )
        for ((minutes, expected) in cases) {
            check(Start(expected), GENERAL, "$minutes 재워줘", "${minutes}만 재워줘")
            check(Extend(expected), GENERAL, "$minutes 더", "${minutes}만 더")
        }
    }

    // ---------------------------------------------------------------- Extend

    @Test
    fun extend_withoutNumber_defaultsToFive() {
        check(Extend(5), GENERAL, "조금만 더", "더 잘래", "좀 더 잘게요", "연장", "연장해 줘", "조금만 더 재워줘")
    }

    @Test
    fun extend_variousPhrasings() {
        check(Extend(5), GENERAL, "5분만 더", "5분 더요", "오분 더", "다섯 분만 더 잘게", "5분 더 재워 주세요", "5분 연장")
        check(Extend(10), GENERAL, "10분만 더 잘래", "십분 더")
    }

    // ---------------------------------------------------------------- Wake

    @Test
    fun wake_variousPhrasings() {
        check(Wake, GENERAL, "깨워줘요", "깨워 주세요", "깨워줄래", "그만!", "이제 그만", "그만 잘래", "일어날래요", "일어날게", "일어나야 해", "중지", "멈춰 줘", "파워냅 종료")
    }

    @Test
    fun wake_refusal_isNotWake() {
        check(Unknown, GENERAL, "일어나기 싫어", "깨우지 마")
    }

    // ---------------------------------------------------------------- Status

    @Test
    fun status_variousPhrasings() {
        check(Status, GENERAL, "몇 분 남았어", "몇분남았어", "몇 분 남았어요?", "얼마나 남았어?", "남은 시간", "언제 깨워 줄 거야", "몇 분 더 잘 수 있어?")
    }

    // ---------------------------------------------------------------- 견고함

    @Test
    fun robustToSpacingAndPunctuation() {
        check(Start(20), GENERAL, "20분만재워줘", " 20분만  재워줘 ", "20분만, 재워줘!", "20분만 재워줘.", "이십분만재워줘", "20 분 만 재워 줘")
        check(Extend(5), GENERAL, "5분더", "5 분 더.", "오분더")
        check(Wake, GENERAL, "깨워 줘", "깨워줘!!", "그만.", "일어 날래")
        check(Status, GENERAL, "몇분남았어?", "몇 분 남았어 ?")
        check(RearSeatClear, REAR_SEAT_QUESTION, "없어요!", " 없어요 ", "없 어요", "없어요...")
        check(RearSeatOccupied, REAR_SEAT_QUESTION, "있어요!", "있 어요", "있어요...")
    }
}
