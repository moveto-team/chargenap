package com.chargenap.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoreanNumberParserTest {

    private val sinoUnits = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val nativeUnits = listOf("", "하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val nativeTens = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순")

    /** 1~60의 한자어 수 표기. 10은 "십", 20은 "이십". */
    private fun sino(n: Int): String {
        val tens = n / 10
        val units = n % 10
        val head = when (tens) {
            0 -> ""
            1 -> "십"
            else -> sinoUnits[tens] + "십"
        }
        return head + sinoUnits[units]
    }

    private fun native(n: Int): String = nativeTens[n / 10] + nativeUnits[n % 10]

    // ---------------------------------------------------------------- parse

    @Test
    fun parse_sinoKorean_1to60() {
        for (n in 1..60) assertEquals("한자어 ${sino(n)}", n, KoreanNumberParser.parse(sino(n)))
    }

    @Test
    fun parse_nativeKorean_1to60() {
        for (n in 1..60) assertEquals("고유어 ${native(n)}", n, KoreanNumberParser.parse(native(n)))
    }

    @Test
    fun parse_namedExamples() {
        val cases = mapOf(
            "오" to 5, "다섯" to 5, "십" to 10, "열" to 10, "십오" to 15, "열다섯" to 15,
            "이십" to 20, "스물" to 20, "이십오" to 25, "삼십" to 30,
            "스무" to 20, "스물다섯" to 25, "서른" to 30, "육십" to 60, "예순" to 60,
            "한" to 1, "두" to 2, "세" to 3, "네" to 4, "열두" to 12, "일십" to 10,
        )
        for ((text, expected) in cases) assertEquals(text, expected, KoreanNumberParser.parse(text))
    }

    @Test
    fun parse_arabicDigits() {
        assertEquals(5, KoreanNumberParser.parse("5"))
        assertEquals(20, KoreanNumberParser.parse("20"))
        assertEquals(20, KoreanNumberParser.parse(" 20 "))
        assertEquals(60, KoreanNumberParser.parse("60"))
        assertEquals(5, KoreanNumberParser.parse("05"))
    }

    @Test
    fun parse_notANumber_returnsNull() {
        for (text in listOf("", "   ", "재워줘", "분", "이십분", "십십", "오오", "이삼", "0", "100", "20a", "스물십", "몇")) {
            assertNull("\"$text\"", KoreanNumberParser.parse(text))
        }
    }

    // ---------------------------------------------------------------- findMinutes

    @Test
    fun findMinutes_sinoAndNative_1to60_withAndWithoutSpace() {
        for (n in 1..60) {
            assertEquals(n, KoreanNumberParser.findMinutes("${sino(n)}분"))
            assertEquals(n, KoreanNumberParser.findMinutes("${sino(n)} 분만 재워줘"))
            assertEquals(n, KoreanNumberParser.findMinutes("$n 분"))
            assertEquals(n, KoreanNumberParser.findMinutes("${n}분만"))
        }
        // 고유어 수는 "분" 앞에서 관형형으로도 쓴다.
        for (n in 5..60) {
            assertEquals(native(n), n, KoreanNumberParser.findMinutes("${native(n)} 분"))
        }
    }

    @Test
    fun findMinutes_namedExamples() {
        val cases = mapOf(
            "20분" to 20,
            "20 분" to 20,
            "이십 분" to 20,
            "이십분" to 20,
            "십오 분" to 15,
            "삼십 분" to 30,
            "오 분" to 5,
            "다섯 분" to 5,
            "20분만 재워줘" to 20,
            "5분 더" to 5,
            "딱 십 분만" to 10,
            "스무 분만 잘게" to 20,
            "열다섯 분" to 15,
            "한 이십오 분 정도" to 25,
            "이십 오 분" to 25,
            "스물 다섯 분" to 25,
            "두 분" to 2,
        )
        for ((text, expected) in cases) assertEquals(text, expected, KoreanNumberParser.findMinutes(text))
    }

    @Test
    fun findMinutes_particleBeforeNumber_readsOnlyTheNumber() {
        // "시간이 오 분"의 '이'는 조사다. 이십오가 아니라 오.
        assertEquals(5, KoreanNumberParser.findMinutes("시간이 오 분 남았어"))
        assertEquals(5, KoreanNumberParser.findMinutes("시간이오분"))
    }

    @Test
    fun findMinutes_skipsMinuteSyllableWithoutNumber_andFindsLaterOne() {
        assertEquals(10, KoreanNumberParser.findMinutes("기분이 좋아 십 분만"))
        assertEquals(20, KoreanNumberParser.findMinutes("충분해 20분"))
    }

    @Test
    fun findMinutes_none_returnsNull() {
        for (text in listOf("", "재워줘", "몇 분 남았어", "분", "기분 좋아", "충분해", "20", "이십", "100분", "0분")) {
            assertNull("\"$text\"", KoreanNumberParser.findMinutes(text))
        }
    }
}
