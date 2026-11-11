package com.chargenap.app.voice

import com.chargenap.app.R
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.ExtendDenyReason
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `strings_voice.xml`의 실제 문장으로 [ResourceVoicePhrases]를 검증한다.
 *
 * 안드로이드 런타임 없이 XML을 직접 읽는다. 리소스 ID와 이름은 `R` 클래스에서 찾으므로, 코드가 없는 리소스를
 * 가리키면 컴파일이 안 되고, XML에 없는 이름을 쓰면 여기서 실패한다.
 */
class ResourceVoicePhrasesTest {

    private class XmlVoiceStrings : VoiceStrings {
        val strings = mutableMapOf<String, String>()
        val arrays = mutableMapOf<String, List<String>>()
        private val stringNames = namesById(R.string::class.java)
        private val arrayNames = namesById(R.array::class.java)

        init {
            val file = File("src/main/res/values/strings_voice.xml")
            check(file.isFile) { "문구 파일이 없다: ${file.absolutePath}" }
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val children = root.childNodes
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> strings[name] = element.textContent
                    "string-array" -> {
                        val items = element.getElementsByTagName("item")
                        arrays[name] = (0 until items.length).map { items.item(it).textContent }
                    }
                    else -> error("예상하지 못한 리소스 종류: ${element.tagName}")
                }
            }
        }

        override fun text(id: Int, vararg args: Any): String {
            val name = stringNames[id] ?: error("R.string에 없는 ID: $id")
            val template = strings[name] ?: error("strings_voice.xml에 없는 문자열: $name")
            return if (args.isEmpty()) template else String.format(Locale.KOREA, template, *args)
        }

        override fun list(id: Int): List<String> {
            val name = arrayNames[id] ?: error("R.array에 없는 ID: $id")
            return arrays[name] ?: error("strings_voice.xml에 없는 배열: $name")
        }

        private fun namesById(type: Class<*>): Map<Int, String> =
            type.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }
    }

    private val xml = XmlVoiceStrings()
    private val phrases = ResourceVoicePhrases(xml)

    private val allPass = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS)

    private fun summary(
        battery: Int? = 80,
        rangeKm: Int? = 360,
        slept: Int = 20,
        wakeReason: WakeReason = WakeReason.TIMER,
        failures: List<String> = emptyList(),
    ) = SessionSummary(
        startedAtMillis = 0,
        endedAtMillis = slept * 60_000L,
        mode = SessionMode.NAP,
        plannedMinutes = 20,
        sleptMinutes = slept,
        batteryStartPercent = 60,
        batteryEndPercent = battery,
        rangeKm = rangeKm,
        wakeReason = wakeReason,
        seatMoved = true,
        failures = failures,
    )

    // ---------------------------------------------------------------- 제안

    @Test
    fun offer_nap() {
        assertEquals(
            "80퍼센트까지 약 27분 남았어요. 20분 파워냅 어때요?",
            phrases.offer(Recommendation.Nap(minutes = 20, remainingChargeMinutes = 27), targetPercent = 80),
        )
    }

    @Test
    fun offer_chooseDuration() {
        assertEquals(
            "충전하는 동안 쉬어 갈까요? 15분이나 20분 중에 골라 주세요.",
            phrases.offer(Recommendation.ChooseDuration(listOf(15, 20)), targetPercent = 80),
        )
    }

    @Test
    fun offer_breathing_mentionsFiveMinutes_notPowerNap() {
        val text = phrases.offer(Recommendation.Breathing(minutes = 5, remainingChargeMinutes = 14), targetPercent = 80)!!
        assertTrue(text, "5분" in text)
        assertTrue(text, "14분" in text)
        assertFalse(text, "파워냅" in text)
    }

    @Test
    fun offer_none_saysNothing() {
        assertNull(phrases.offer(Recommendation.None, targetPercent = 80))
        assertNull(phrases.offer(Recommendation.ChooseDuration(listOf(15)), targetPercent = 80))
    }

    // ---------------------------------------------------------------- 안전 확인

    @Test
    fun safetyQuestions() {
        assertEquals("뒷좌석에 사람이나 짐이 있나요?", phrases.rearSeatQuestion())
        assertEquals("문이 모두 닫혀 있나요?", phrases.doorQuestion())
        assertEquals("문을 닫아 주세요.", phrases.closeDoors())
        assertTrue("있어요" in phrases.rearSeatRetry() && "없어요" in phrases.rearSeatRetry())
        assertTrue("시트" in phrases.rearSeatNoAnswer())
    }

    @Test
    fun safetyWait_onlyWhenSomethingToFix() {
        assertNull(phrases.safetyWait(allPass))
        assertNull(phrases.safetyWait(allPass.copy(doors = CheckResult.NEEDS_USER_CONFIRM, parkingBrake = CheckResult.SKIPPED)))
        assertEquals("문을 닫아 주세요.", phrases.safetyWait(allPass.copy(doors = CheckResult.FAIL)))
        assertEquals("주차 브레이크를 걸어 주세요.", phrases.safetyWait(allPass.copy(parkingBrake = CheckResult.FAIL)))
        val both = phrases.safetyWait(allPass.copy(doors = CheckResult.FAIL, parkingBrake = CheckResult.FAIL))!!
        assertTrue("문" in both && "주차 브레이크" in both)
    }

    @Test
    fun startBlocked_namesTheReason() {
        assertTrue("기어" in phrases.startBlocked(allPass.copy(gear = CheckResult.FAIL)))
        assertTrue("충전" in phrases.startBlocked(allPass.copy(charging = CheckResult.FAIL)))
        // 기어가 먼저다.
        assertTrue("기어" in phrases.startBlocked(allPass.copy(gear = CheckResult.FAIL, charging = CheckResult.FAIL)))
        assertTrue(phrases.startBlocked(allPass).isNotBlank())
    }

    // ---------------------------------------------------------------- 준비, 수면

    @Test
    fun preparing() {
        assertEquals("시트를 눕힐게요. 편하게 기대세요.", phrases.preparing(seatMoves = true))
        assertEquals("편안하게 쉴 수 있게 준비할게요.", phrases.preparing(seatMoves = false))
    }

    @Test
    fun breathingGuide_shortSentences() {
        val lines = phrases.breathingGuide()
        assertTrue("여러 문장이어야 한다", lines.size >= 4)
        for (line in lines) {
            assertTrue("짧은 문장이어야 한다: $line", line.length in 5..30)
            assertFalse("수면 직전에는 느낌표를 쓰지 않는다: $line", '!' in line)
        }
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun remaining() {
        assertEquals("17분 남았어요.", phrases.remaining(17))
        assertFalse("0분이라고 말하지 않는다", "0분" in phrases.remaining(0))
    }

    @Test
    fun extend() {
        assertEquals("5분 더 쉬세요.", phrases.extendGranted(5))
        val denied = ExtendDenyReason.entries.map { phrases.extendDenied(it) }
        assertEquals("사유마다 문장이 다르다", denied.size, denied.toSet().size)
        assertTrue("충전" in phrases.extendDenied(ExtendDenyReason.CHARGE_ENDS_SOON))
        assertTrue("30분" in phrases.extendDenied(ExtendDenyReason.MAX_TOTAL))
    }

    // ---------------------------------------------------------------- 기상, 브리핑, 중단

    @Test
    fun waking_everyReasonHasItsOwnSentence() {
        val texts = WakeReason.entries.map { phrases.waking(it) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue("충전" in phrases.waking(WakeReason.CHARGE_COMPLETE))
        assertTrue("충전" in phrases.waking(WakeReason.CHARGE_NEARLY_DONE))
        assertTrue("충전" in phrases.waking(WakeReason.CHARGING_INTERRUPTED))
        assertTrue("일어날 시간" in phrases.waking(WakeReason.TIMER))
    }

    @Test
    fun briefing_full() {
        assertEquals("배터리 80퍼센트, 주행 가능 거리 360킬로미터예요. 20분 쉬셨어요.", phrases.briefing(summary()))
    }

    @Test
    fun briefing_omitsUnknownValues() {
        assertEquals("배터리는 80퍼센트예요. 20분 쉬셨어요.", phrases.briefing(summary(rangeKm = null)))
        assertEquals("주행 가능 거리는 360킬로미터예요. 20분 쉬셨어요.", phrases.briefing(summary(battery = null)))
        assertEquals("20분 쉬셨어요.", phrases.briefing(summary(battery = null, rangeKm = null)))
        val nothing = phrases.briefing(summary(battery = null, rangeKm = null, slept = 0))
        assertTrue(nothing.isNotBlank())
        assertFalse("null" in nothing)
        assertFalse("0분" in phrases.briefing(summary(slept = 0)))
    }

    @Test
    fun briefing_appendsInterruptionAndFailures() {
        val interrupted = phrases.briefing(summary(wakeReason = WakeReason.CHARGING_INTERRUPTED))
        assertTrue(interrupted.startsWith("배터리 80퍼센트, 주행 가능 거리 360킬로미터예요. 20분 쉬셨어요. "))
        assertTrue(interrupted, "충전이 중간에 멈췄어요." in interrupted)

        val failed = phrases.briefing(summary(failures = listOf("seat: 가상 시트 오류")))
        assertTrue(failed.startsWith("배터리 80퍼센트, 주행 가능 거리 360킬로미터예요. 20분 쉬셨어요. "))
        assertTrue(failed, "적용되지 않았어요" in failed)
        assertFalse("내부 오류 문자열을 읽지 않는다", "seat:" in failed)

        val both = phrases.briefing(
            summary(wakeReason = WakeReason.CHARGING_INTERRUPTED, failures = listOf("climate: 오류")),
        )
        assertTrue("충전이 중간에 멈췄어요." in both && "적용되지 않았어요" in both)
    }

    @Test
    fun aborted_byReasonAndSeat() {
        val texts = listOf(
            phrases.aborted(AbortReason.GEAR_LEFT_PARK, seatMoved = true),
            phrases.aborted(AbortReason.GEAR_LEFT_PARK, seatMoved = false),
            phrases.aborted(AbortReason.DOOR_OPENED, seatMoved = true),
            phrases.aborted(AbortReason.DOOR_OPENED, seatMoved = false),
        )
        assertEquals(4, texts.toSet().size)
        assertTrue("기어" in texts[0] && "시트" in texts[0])
        assertTrue("기어" in texts[1] && "시트" !in texts[1])
        assertTrue("문" in texts[2] && "시트" in texts[2])
        assertTrue("문" in texts[3] && "시트" !in texts[3])
    }

    // ---------------------------------------------------------------- 리소스 파일 규칙

    @Test
    fun resources_followNamingAndTtsRules() {
        val names = xml.strings.keys + xml.arrays.keys
        assertTrue(names.isNotEmpty())
        for (name in names) assertTrue("voice_ 접두어: $name", name.startsWith("voice_"))

        val sentences = xml.strings.values + xml.arrays.values.flatten()
        for (sentence in sentences) {
            assertTrue("빈 문장", sentence.isNotBlank())
            val withoutArguments = sentence.replace(Regex("%\\d+\\\$d"), "")
            assertFalse("TTS가 읽기 어려운 기호: $sentence", withoutArguments.any { it in "%℃~/()" })
            assertFalse("단위는 한글로 쓴다: $sentence", sentence.contains("km", ignoreCase = true))
            assertFalse("이스케이프는 이 테스트의 XML 읽기가 처리하지 못한다: $sentence", '\\' in sentence)
            assertTrue("존댓말로 끝난다: $sentence", sentence.trimEnd('.', '?').endsWith("요"))
        }
    }
}
