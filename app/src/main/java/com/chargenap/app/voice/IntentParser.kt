package com.chargenap.app.voice

/**
 * 규칙 기반 한국어 명령 해석기 (킥오프 4.5). 순수 Kotlin.
 *
 * STT 결과는 문장부호가 없거나 띄어쓰기가 틀릴 수 있으므로, 낱말 대조는 대부분 공백을 뺀 문자열에서 한다.
 *
 * 우선순위
 * 1. 뒷좌석 질문 맥락: 중단 명령("그만") → "있다/없다" → "응/아니"만으로 된 대답. 둘 다 들리거나 애매하면
 *    [VoiceIntent.Unknown](시트를 움직이지 않는 쪽).
 * 2. 어느 맥락에서나: 연장 → 기상 → 남은 시간 → 시작.
 * 3. 맥락별 예/아니오. [ParseContext.GENERAL]에서는 뜻이 없으므로 [VoiceIntent.Unknown].
 */
object IntentParser {

    fun parse(text: String, context: ParseContext): VoiceIntent {
        val spaced = normalize(text)
        if (spaced.isEmpty()) return VoiceIntent.Unknown
        val compact = spaced.replace(" ", "")
        val tokens = spaced.split(' ')
        val minutes = KoreanNumberParser.findMinutes(spaced)

        if (context == ParseContext.REAR_SEAT_QUESTION) {
            // 시트 이동 동의로 잘못 읽히면 안 되므로 중단 명령을 가장 먼저 본다.
            if (isWake(compact) && !isQuestion(compact)) return VoiceIntent.Wake
            rearSeatAnswer(compact, tokens)?.let { return it }
        }

        general(compact, minutes)?.let { return it }

        return when (context) {
            ParseContext.GENERAL, ParseContext.REAR_SEAT_QUESTION -> VoiceIntent.Unknown
            ParseContext.OFFER -> offerAnswer(compact, tokens, minutes)
            ParseContext.DOOR_QUESTION -> doorAnswer(compact, tokens)
        }
    }

    /** 소문자로 바꾸고 문장부호를 공백으로 바꾼 뒤 공백을 하나로 줄인다. */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) sb.append(if (ch.isLetterOrDigit()) ch else ' ')
        return sb.toString().trim().replace(WHITESPACE, " ")
    }

    // ------------------------------------------------------------ 어느 맥락에서나 인식하는 명령

    private fun general(c: String, minutes: Int?): VoiceIntent? {
        val question = isQuestion(c)
        if (!question && isExtend(c, minutes)) return VoiceIntent.Extend(minutes ?: DEFAULT_EXTEND_MINUTES)
        if (isWake(c)) {
            return when {
                "언제" in c -> VoiceIntent.Status                                   // "언제 깨워 줄 거야?"
                minutes != null && LATER_CUES.any { it in c } -> VoiceIntent.Start(minutes)   // "20분 뒤에 깨워줘"
                else -> VoiceIntent.Wake
            }
        }
        if (question || STATUS_CUES.any { it in c }) return VoiceIntent.Status
        if (isStart(c)) return VoiceIntent.Start(minutes)
        return null
    }

    private fun isQuestion(c: String): Boolean = QUESTION_CUES.any { it in c }

    private fun isExtend(c: String, minutes: Int?): Boolean {
        if ("연장" in c) return true
        val more = c.lastIndexOf('더')
        if (more < 0 || "더이상" in c) return false
        // "5분 더", "오 분만 더 잘래"
        if (minutes != null && c.indexOf('분') in 0 until more) return true
        // "조금만 더", "더 잘래", "좀 더 쉴게요"
        return EXTEND_PATTERN.containsMatchIn(c)
    }

    private fun isWake(c: String): Boolean =
        WAKE_CUES.any { it in c } && REFUSAL_CUES.none { it in c }

    private fun isStart(c: String): Boolean =
        START_CUES.any { it in c } && REFUSAL_CUES.none { it in c } && START_NEGATIONS.none { it in c }

    // ------------------------------------------------------------ 뒷좌석 질문

    /**
     * "뒷좌석에 사람이나 짐이 있나요?"의 답. 단서가 전혀 없으면 null(다른 명령일 수 있다).
     *
     * 시트 이동을 허용하는 [VoiceIntent.RearSeatClear]는 "없다"만 분명히 들렸을 때만 돌려준다.
     */
    private fun rearSeatAnswer(compact: String, tokens: List<String>): VoiceIntent? {
        var t = compact.replace("비어있", "비어")
        // "상관없어", "알 수 없어"의 '없'은 뒷좌석이 비었다는 뜻이 아니다.
        var unsure = UNSURE_CUES.any { it in t }
        for (idiom in NOT_EMPTY_IDIOMS) {
            if (idiom in t) {
                unsure = true
                t = t.replace(idiom, "")
            }
        }
        val clear = REAR_CLEAR_CUES.any { it in t }
        val withoutNegated = REAR_CLEAR_CUES.fold(t) { acc, cue -> if (cue.startsWith("안")) acc.replace(cue, "") else acc }
        val occupied = REAR_OCCUPIED_CUES.any { it in withoutNegated }

        return when {
            clear && occupied -> VoiceIntent.Unknown
            occupied -> VoiceIntent.RearSeatOccupied
            clear -> if (unsure) VoiceIntent.Unknown else VoiceIntent.RearSeatClear
            unsure -> VoiceIntent.Unknown
            else -> {
                // "응", "아니요"처럼 대답 낱말만 있을 때만 받아들인다. 다른 말이 섞이면 넘긴다.
                val words = tokens.filterNot { it in FILLERS }
                when {
                    words.isEmpty() -> null
                    words.all { it in REAR_YES_WORDS } -> VoiceIntent.RearSeatOccupied
                    words.all { it in REAR_NO_WORDS } -> VoiceIntent.RearSeatClear
                    else -> null
                }
            }
        }
    }

    // ------------------------------------------------------------ 제안에 대한 대답

    private fun offerAnswer(c: String, tokens: List<String>, minutes: Int?): VoiceIntent {
        val answer = yesNo(c, tokens)
        // "15분만", "이십 분", "십오"처럼 시간만 말한 대답
        val requested = minutes ?: bareNumber(c)
        return when {
            requested != null -> if (answer == false) VoiceIntent.Unknown else VoiceIntent.Start(requested)
            answer == true -> VoiceIntent.Yes
            answer == false -> VoiceIntent.No
            else -> VoiceIntent.Unknown
        }
    }

    /** 문장 전체가 수일 때만. 한 글자짜리 한글 수("오", "이")는 감탄사·조사와 헷갈리므로 받지 않는다. */
    private fun bareNumber(c: String): Int? {
        val value = KoreanNumberParser.parse(c) ?: return null
        return if (c.length >= 2 || c[0].isDigit()) value else null
    }

    // ------------------------------------------------------------ 도어 질문

    private fun doorAnswer(c: String, tokens: List<String>): VoiceIntent {
        val notClosed = DOOR_NOT_CLOSED_CUES.any { it in c }
        val notOpen = DOOR_NOT_OPEN_CUES.any { it in c }
        val closed = (DOOR_CLOSED_CUES.any { it in c } && !notClosed) || notOpen
        val open = (DOOR_OPEN_CUES.any { it in c } && !notOpen) || notClosed
        return when {
            closed && open -> VoiceIntent.Unknown
            closed -> VoiceIntent.Yes
            open -> VoiceIntent.No
            else -> when (yesNo(c, tokens)) {
                true -> VoiceIntent.Yes
                false -> VoiceIntent.No
                null -> VoiceIntent.Unknown
            }
        }
    }

    // ------------------------------------------------------------ 예/아니오

    /** true = 예, false = 아니오, null = 알 수 없거나 둘 다 들림. */
    private fun yesNo(c: String, tokens: List<String>): Boolean? {
        val yes = tokens.any { it in YES_WORDS } || YES_STEMS.any { it in c }
        val hardNo = tokens.any { it in NO_WORDS } || NO_STEMS.any { it in c }
        val softNo = SOFT_NO_STEMS.any { it in c }
        return when {
            hardNo -> if (yes) null else false
            yes -> true            // "응, 괜찮네"의 '괜찮'은 거절이 아니다
            softNo -> false        // "괜찮아", "나중에"
            else -> null
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val DEFAULT_EXTEND_MINUTES = 5

    private val EXTEND_PATTERN = Regex("더(자|잘|재|쉬|쉴|줘|주|요|$)")
    private val WAKE_CUES = listOf("깨워", "그만", "일어날", "일어나", "취소", "멈춰", "중지", "스톱", "끝내", "종료")
    private val LATER_CUES = listOf("뒤", "후에", "있다", "이따")
    private val QUESTION_CUES = listOf("몇분", "얼마", "언제")
    private val STATUS_CUES = listOf("남았", "남은시간")
    private val START_CUES = listOf(
        "재워", "잘래", "잘게", "자고싶", "자야", "잠좀", "눈좀붙", "눈붙",
        "쉴래", "쉴게", "쉬고싶", "쉬자", "쉬어갈", "파워냅", "낮잠", "시작", "호흡",
    )
    /** "안 잘래", "나중에 잘게"처럼 지금 시작하자는 말이 아닌 것. */
    private val START_NEGATIONS = listOf("안잘", "안자", "안쉴", "안쉬", "말고", "나중", "다음에", "이따")

    /** "일어나기 싫어", "깨우지 마", "시작하지 마"처럼 명령을 뒤집는 말. */
    private val REFUSAL_CUES = listOf("싫", "지마", "지말")

    private val FILLERS = setOf("음", "어", "아", "그", "저", "뭐", "요")

    private val REAR_CLEAR_CUES = listOf("없", "비어", "비었", "안탔", "안타고", "안실")
    private val REAR_OCCUPIED_CUES = listOf("있", "탔", "타고", "실었", "실려", "앉아")
    private val NOT_EMPTY_IDIOMS = listOf("상관없", "관계없", "문제없", "필요없", "소용없", "수없")
    /** 확신이 없는 말과 이중 부정("없지 않아"). 이런 말에 섞인 "없다"는 시트 이동 동의로 보지 않는다. */
    private val UNSURE_CUES = listOf(
        "모르", "글쎄", "같기도", "것같", "거같", "아마", "일걸", "을걸", "없나", "있나", "지않", "진않", "지는않",
    )
    private val REAR_YES_WORDS = setOf("응", "응응", "엉", "웅", "네", "네네", "넵", "넹", "예", "옙", "맞아", "맞아요", "그래", "그래요")
    private val REAR_NO_WORDS = setOf("아니", "아니아니", "아니요", "아니오", "아뇨", "아냐", "아니야", "아니에요", "아닙니다")

    private val YES_WORDS = setOf("응", "응응", "엉", "웅", "네", "네네", "넵", "넹", "예", "옙", "콜")
    private val YES_STEMS = listOf(
        "좋아", "좋지", "좋죠", "좋네", "좋습", "그래", "그럼", "그러자", "그러죠", "오케",
        "맞아", "맞습", "물론", "당연", "부탁", "알겠", "알았", "확인",
    )
    private val NO_WORDS = setOf("노")
    private val NO_STEMS = listOf(
        "아니", "아뇨", "아냐", "싫", "됐어", "됐습", "됬어", "안해", "안할", "안잘", "안자",
        "별로", "필요없", "하지마", "하지말", "안돼", "말자",
    )
    private val SOFT_NO_STEMS = listOf("괜찮", "나중", "다음에", "이따")

    private val DOOR_CLOSED_CUES = listOf("닫혀", "닫혔", "닫았", "닫힘", "닫음", "닫긴")
    private val DOOR_OPEN_CUES = listOf("열려", "열렸", "열어", "열림", "열린")
    private val DOOR_NOT_CLOSED_CUES = listOf("안닫", "못닫", "닫지않", "닫지못", "닫혀있지않")
    private val DOOR_NOT_OPEN_CUES = listOf("안열", "열려있지않", "열지않")
}
