package com.chargenap.app.voice

/**
 * 한국어 수 표현(1~99)을 정수로 바꾼다. 순수 Kotlin.
 *
 * - 아라비아 숫자: "20"
 * - 한자어 수: "오", "십", "십오", "이십", "이십오", "삼십"
 * - 고유어 수: "다섯", "열", "열다섯", "스물", "스무", "스물다섯", "서른"
 */
object KoreanNumberParser {

    private val sinoDigits = mapOf(
        '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5,
        '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )
    private const val SINO_TEN = '십'

    private val nativeTens = listOf(
        "열" to 10, "스물" to 20, "서른" to 30, "마흔" to 40, "쉰" to 50,
        "예순" to 60, "일흔" to 70, "여든" to 80, "아흔" to 90,
    )
    private val nativeUnits = listOf(
        "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "석" to 3,
        "넷" to 4, "네" to 4, "넉" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
    )

    /** 고유어 수 낱말 전체(1~99). 긴 것부터 맞춰 보도록 길이 내림차순이다. */
    private val nativeWords: List<Pair<String, Int>> = buildList {
        addAll(nativeUnits)
        addAll(nativeTens)
        add("스무" to 20)
        for ((ten, tenValue) in nativeTens) {
            for ((unit, unitValue) in nativeUnits) add(ten + unit to tenValue + unitValue)
        }
    }.sortedByDescending { it.first.length }

    private val nativeMap: Map<String, Int> = nativeWords.toMap()

    private const val MAX_VALUE = 99

    /** [text] 전체가 수 표현이면 그 값을, 아니면 null을 돌려준다. 앞뒤 공백은 무시한다. */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.all { it in '0'..'9' }) return parseDigits(t)
        return parseSino(t) ?: nativeMap[t]
    }

    /**
     * 문장에서 "N분"의 N을 찾는다. 숫자와 "분" 사이의 공백은 있어도 되고 없어도 된다.
     * 예: "20분만", "20 분", "이십 분", "십오분", "다섯 분". 없으면 null.
     */
    fun findMinutes(text: String): Int? {
        var index = text.indexOf(MINUTE_UNIT)
        while (index >= 0) {
            numberEndingAt(text, index)?.let { return it }
            index = text.indexOf(MINUTE_UNIT, index + 1)
        }
        return null
    }

    /** [end] 바로 앞(공백은 건너뛴다)에서 끝나는 수 표현을 찾는다. */
    private fun numberEndingAt(text: String, end: Int): Int? {
        var stop = end
        while (stop > 0 && text[stop - 1].isWhitespace()) stop--
        if (stop == 0) return null
        val head = text.substring(0, stop)

        val digits = head.takeLastWhile { it in '0'..'9' }
        if (digits.isNotEmpty()) return parseDigits(digits)

        val sinoRun = head.takeLastWhile(::isSinoChar)
        if (sinoRun.isNotEmpty()) {
            // "이십 오 분"처럼 십 단위와 일 단위가 띄어져 있으면 붙여 읽는다.
            if (sinoRun.length == 1 && sinoRun[0] in sinoDigits) {
                val tensRun = head.dropLast(1).trimEnd().takeLastWhile(::isSinoChar)
                if (tensRun.endsWith(SINO_TEN)) {
                    longestSinoSuffix(tensRun + sinoRun)?.takeIf { it > 10 }?.let { return it }
                }
            }
            longestSinoSuffix(sinoRun)?.let { return it }
        }

        val (word, value) = nativeWords.firstOrNull { head.endsWith(it.first) } ?: return null
        if (value < 10) {
            // "스물 다섯 분"
            val rest = head.dropLast(word.length).trimEnd()
            nativeTens.firstOrNull { rest.endsWith(it.first) }?.let { return it.second + value }
        }
        return value
    }

    private fun isSinoChar(c: Char): Boolean = c == SINO_TEN || c in sinoDigits

    /** "시간이 오 분"처럼 조사 '이'가 붙어 있을 수 있으므로, 수로 읽히는 가장 긴 꼬리를 쓴다. */
    private fun longestSinoSuffix(run: String): Int? {
        for (start in run.indices) {
            parseSino(run.substring(start))?.let { return it }
        }
        return null
    }

    private fun parseDigits(digits: String): Int? =
        if (digits.length > 2) null else digits.toIntOrNull()?.takeIf { it in 1..MAX_VALUE }

    /** 한자어 수: `[일~구]`, `[일~구]?십[일~구]?`. */
    private fun parseSino(text: String): Int? {
        val tenIndex = text.indexOf(SINO_TEN)
        if (tenIndex < 0) {
            return if (text.length == 1) sinoDigits[text[0]] else null
        }
        val before = text.substring(0, tenIndex)
        val after = text.substring(tenIndex + 1)
        if (before.length > 1 || after.length > 1) return null
        val tens = if (before.isEmpty()) 1 else sinoDigits[before[0]] ?: return null
        val units = if (after.isEmpty()) 0 else sinoDigits[after[0]] ?: return null
        return tens * 10 + units
    }

    private const val MINUTE_UNIT = "분"
}
