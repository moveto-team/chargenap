package com.chargenap.app.data

import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecordCodecTest {

    private val summary = SessionSummary(
        startedAtMillis = 1_780_000_000_000,
        endedAtMillis = 1_780_000_000_000 + 18 * 60_000L,
        mode = SessionMode.NAP,
        plannedMinutes = 20,
        sleptMinutes = 18,
        batteryStartPercent = 53,
        batteryEndPercent = 71,
        rangeKm = 319,
        wakeReason = WakeReason.CHARGE_NEARLY_DONE,
        seatMoved = true,
        failures = listOf("seat: 시간 초과", "climate: rejected"),
    )

    @Test
    fun from_copiesEveryField() {
        val record = SessionRecord.from(summary)

        assertEquals(summary.startedAtMillis, record.startedAtMillis)
        assertEquals(summary.endedAtMillis, record.endedAtMillis)
        assertEquals(summary.mode, record.mode)
        assertEquals(summary.plannedMinutes, record.plannedMinutes)
        assertEquals(summary.sleptMinutes, record.sleptMinutes)
        assertEquals(summary.batteryStartPercent, record.batteryStartPercent)
        assertEquals(summary.batteryEndPercent, record.batteryEndPercent)
        assertEquals(summary.rangeKm, record.rangeKm)
        assertEquals(summary.wakeReason, record.wakeReason)
        assertEquals(summary.seatMoved, record.seatMoved)
        assertEquals(summary.failures, record.failures)
    }

    @Test
    fun record_roundTrips() {
        val records = listOf(
            SessionRecord.from(summary),
            SessionRecord.from(
                summary.copy(
                    mode = SessionMode.BREATHING,
                    sleptMinutes = 0,
                    batteryStartPercent = null,
                    batteryEndPercent = null,
                    rangeKm = null,
                    wakeReason = WakeReason.CHARGING_INTERRUPTED,
                    seatMoved = false,
                    failures = emptyList(),
                ),
            ),
        )

        val decoded = SessionRecordCodec.decode(SessionRecordCodec.encode(records))

        assertEquals(records, decoded)
    }

    @Test
    fun decode_brokenJson_returnsEmptyList() {
        assertTrue(SessionRecordCodec.decode("{not json").isEmpty())
        assertTrue(SessionRecordCodec.decode("[{\"startedAtMillis\":").isEmpty())
        assertTrue(SessionRecordCodec.decode("\"문자열\"").isEmpty())
    }

    @Test
    fun decode_missingRequiredField_returnsEmptyList() {
        assertTrue(SessionRecordCodec.decode("[{\"startedAtMillis\":1}]").isEmpty())
    }

    @Test
    fun decode_unknownEnumValue_returnsEmptyList() {
        val text = SessionRecordCodec.encode(listOf(SessionRecord.from(summary))).replace("CHARGE_NEARLY_DONE", "MOON_PHASE")

        assertTrue(SessionRecordCodec.decode(text).isEmpty())
    }

    @Test
    fun decode_nullOrBlank_returnsEmptyList() {
        assertTrue(SessionRecordCodec.decode(null).isEmpty())
        assertTrue(SessionRecordCodec.decode("").isEmpty())
        assertTrue(SessionRecordCodec.decode("   ").isEmpty())
    }

    @Test
    fun decode_ignoresUnknownKeys() {
        val text = SessionRecordCodec.encode(listOf(SessionRecord.from(summary)))
            .replaceFirst("{", "{\"addedInLaterVersion\":true,")

        assertEquals(listOf(SessionRecord.from(summary)), SessionRecordCodec.decode(text))
    }
}
