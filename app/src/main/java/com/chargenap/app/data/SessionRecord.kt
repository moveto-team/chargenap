package com.chargenap.app.data

import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 저장하는 세션 기록 한 건. 필드는 [SessionSummary]와 같다. */
@Serializable
data class SessionRecord(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val mode: SessionMode,
    val plannedMinutes: Int,
    val sleptMinutes: Int,
    val batteryStartPercent: Int? = null,
    val batteryEndPercent: Int? = null,
    val rangeKm: Int? = null,
    val wakeReason: WakeReason,
    val seatMoved: Boolean = false,
    val failures: List<String> = emptyList(),
) {
    companion object {
        fun from(summary: SessionSummary): SessionRecord = SessionRecord(
            startedAtMillis = summary.startedAtMillis,
            endedAtMillis = summary.endedAtMillis,
            mode = summary.mode,
            plannedMinutes = summary.plannedMinutes,
            sleptMinutes = summary.sleptMinutes,
            batteryStartPercent = summary.batteryStartPercent,
            batteryEndPercent = summary.batteryEndPercent,
            rangeKm = summary.rangeKm,
            wakeReason = summary.wakeReason,
            seatMoved = summary.seatMoved,
            failures = summary.failures,
        )
    }
}

/** 세션 기록 목록 ↔ JSON 문자열. */
object SessionRecordCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SessionRecord.serializer())

    fun encode(records: List<SessionRecord>): String = json.encodeToString(serializer, records)

    /** 값이 없거나 깨진 JSON이면 빈 목록을 돌려준다. */
    fun decode(text: String?): List<SessionRecord> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }
}
