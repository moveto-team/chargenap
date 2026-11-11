package com.chargenap.app.voice

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.chargenap.app.R
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.ExtendDenyReason
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason

/** 상황별 음성 안내 문장. 말할 것이 없으면 null을 돌려준다. */
interface VoicePhrases {
    /** 제안 멘트. [targetPercent]는 충전 목표 잔량. [Recommendation.None]이면 null. */
    fun offer(recommendation: Recommendation, targetPercent: Int): String?

    fun rearSeatQuestion(): String

    /** 뒷좌석 질문의 답을 알아듣지 못했을 때 한 번 더 묻는 말. */
    fun rearSeatRetry(): String

    /** 답이 없어 시트를 그대로 두기로 했을 때. */
    fun rearSeatNoAnswer(): String

    fun doorQuestion(): String

    fun closeDoors(): String

    /** 도어가 열려 있거나 주차 브레이크가 풀려 있어 기다리는 중일 때의 안내. 기다릴 것이 없으면 null. */
    fun safetyWait(report: SafetyReport): String?

    /** 시작이 막힌 이유 한 문장. */
    fun startBlocked(report: SafetyReport): String

    /** @param seatMoves true면 이번 세션에서 시트를 눕힌다 */
    fun preparing(seatMoves: Boolean): String

    /** 수면 직전 호흡 안내. 한 문장씩 간격을 두고 말한다. */
    fun breathingGuide(): List<String>

    /** 수면 중 "몇 분 남았어?"의 답. */
    fun remaining(minutes: Int): String

    fun extendGranted(minutes: Int): String

    fun extendDenied(reason: ExtendDenyReason): String

    fun waking(reason: WakeReason): String

    /** 값이 null인 항목은 뺀다. */
    fun briefing(summary: SessionSummary): String

    /** @param seatMoved true면 시트를 눕혔던 세션이라 시트 복귀를 안내한다 */
    fun aborted(reason: AbortReason, seatMoved: Boolean): String
}

/** 문자열 리소스를 읽는 창구. 단위 테스트에서 XML을 직접 읽는 구현으로 바꾼다. */
internal interface VoiceStrings {
    fun text(@StringRes id: Int, vararg args: Any): String
    fun list(@ArrayRes id: Int): List<String>
}

private class AndroidVoiceStrings(private val context: Context) : VoiceStrings {
    override fun text(id: Int, vararg args: Any): String = context.getString(id, *args)
    override fun list(id: Int): List<String> = context.resources.getStringArray(id).toList()
}

/** `res/values/strings_voice.xml`로 문장을 만든다. */
class ResourceVoicePhrases internal constructor(private val strings: VoiceStrings) : VoicePhrases {

    constructor(context: Context) : this(AndroidVoiceStrings(context.applicationContext))

    override fun offer(recommendation: Recommendation, targetPercent: Int): String? = when (recommendation) {
        is Recommendation.Nap -> strings.text(
            R.string.voice_offer_nap, targetPercent, recommendation.remainingChargeMinutes, recommendation.minutes,
        )
        is Recommendation.Breathing -> strings.text(
            R.string.voice_offer_breathing, recommendation.remainingChargeMinutes, recommendation.minutes,
        )
        is Recommendation.ChooseDuration -> {
            val options = recommendation.options
            if (options.size >= 2) strings.text(R.string.voice_offer_choose, options[0], options[1]) else null
        }
        Recommendation.None -> null
    }

    override fun rearSeatQuestion(): String = strings.text(R.string.voice_rear_seat_question)

    override fun rearSeatRetry(): String = strings.text(R.string.voice_rear_seat_retry)

    override fun rearSeatNoAnswer(): String = strings.text(R.string.voice_rear_seat_no_answer)

    override fun doorQuestion(): String = strings.text(R.string.voice_door_question)

    override fun closeDoors(): String = strings.text(R.string.voice_wait_door)

    override fun safetyWait(report: SafetyReport): String? {
        val door = report.doors == CheckResult.FAIL
        val brake = report.parkingBrake == CheckResult.FAIL
        return when {
            door && brake -> strings.text(R.string.voice_wait_door_and_parking_brake)
            door -> strings.text(R.string.voice_wait_door)
            brake -> strings.text(R.string.voice_wait_parking_brake)
            else -> null
        }
    }

    override fun startBlocked(report: SafetyReport): String = when {
        report.gear == CheckResult.FAIL -> strings.text(R.string.voice_start_blocked_gear)
        report.charging == CheckResult.FAIL -> strings.text(R.string.voice_start_blocked_charging)
        else -> strings.text(R.string.voice_start_blocked)
    }

    override fun preparing(seatMoves: Boolean): String =
        strings.text(if (seatMoves) R.string.voice_preparing_seat else R.string.voice_preparing_no_seat)

    override fun breathingGuide(): List<String> = strings.list(R.array.voice_breathing_guide)

    override fun remaining(minutes: Int): String =
        if (minutes > 0) strings.text(R.string.voice_remaining_minutes, minutes)
        else strings.text(R.string.voice_remaining_soon)

    override fun extendGranted(minutes: Int): String = strings.text(R.string.voice_extend_granted, minutes)

    override fun extendDenied(reason: ExtendDenyReason): String = strings.text(
        when (reason) {
            ExtendDenyReason.CHARGE_ENDS_SOON -> R.string.voice_extend_denied_charge_ends_soon
            ExtendDenyReason.MAX_TOTAL -> R.string.voice_extend_denied_max_total
            ExtendDenyReason.NOT_NAPPING -> R.string.voice_extend_denied_not_napping
        },
    )

    override fun waking(reason: WakeReason): String = strings.text(
        when (reason) {
            WakeReason.TIMER -> R.string.voice_waking_timer
            WakeReason.CHARGE_NEARLY_DONE -> R.string.voice_waking_charge_nearly_done
            WakeReason.CHARGE_COMPLETE -> R.string.voice_waking_charge_complete
            WakeReason.CHARGING_INTERRUPTED -> R.string.voice_waking_charging_interrupted
            WakeReason.USER -> R.string.voice_waking_user
        },
    )

    override fun briefing(summary: SessionSummary): String {
        val parts = buildList {
            val battery = summary.batteryEndPercent
            val range = summary.rangeKm
            when {
                battery != null && range != null ->
                    add(strings.text(R.string.voice_briefing_battery_and_range, battery, range))
                battery != null -> add(strings.text(R.string.voice_briefing_battery, battery))
                range != null -> add(strings.text(R.string.voice_briefing_range, range))
            }
            if (summary.sleptMinutes > 0) add(strings.text(R.string.voice_briefing_rested, summary.sleptMinutes))
            if (summary.wakeReason == WakeReason.CHARGING_INTERRUPTED) {
                add(strings.text(R.string.voice_briefing_charging_interrupted))
            }
            if (summary.failures.isNotEmpty()) add(strings.text(R.string.voice_briefing_failures))
        }
        return if (parts.isEmpty()) strings.text(R.string.voice_briefing_done) else parts.joinToString(" ")
    }

    override fun aborted(reason: AbortReason, seatMoved: Boolean): String = strings.text(
        when (reason) {
            AbortReason.GEAR_LEFT_PARK ->
                if (seatMoved) R.string.voice_aborted_gear_seat else R.string.voice_aborted_gear
            AbortReason.DOOR_OPENED ->
                if (seatMoved) R.string.voice_aborted_door_seat else R.string.voice_aborted_door
        },
    )
}
