package com.chargenap.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chargenap.app.R
import com.chargenap.app.session.ExtendDenyReason
import com.chargenap.app.session.WakeReason
import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.ChargingState

// 엔진·차량의 값을 사용자 문구(strings.xml)로 바꾸는 함수 모음. 여러 화면이 같이 쓴다.

@StringRes
fun chargingStateLabel(state: ChargingState): Int = when (state) {
    ChargingState.UNKNOWN -> R.string.charging_unknown
    ChargingState.NOT_CONNECTED -> R.string.charging_not_connected
    ChargingState.CONNECTED_IDLE -> R.string.charging_connected_idle
    ChargingState.CHARGING -> R.string.charging_charging
    ChargingState.COMPLETE -> R.string.charging_complete
}

@StringRes
fun startBlockReasonLabel(reason: StartBlockReason): Int = when (reason) {
    StartBlockReason.GEAR_NOT_PARK -> R.string.block_gear_not_park
    StartBlockReason.GEAR_UNKNOWN -> R.string.block_gear_unknown
    StartBlockReason.CHARGER_NOT_CONNECTED -> R.string.block_charger_not_connected
    StartBlockReason.CHARGE_COMPLETE -> R.string.block_charge_complete
    StartBlockReason.CHARGE_UNKNOWN -> R.string.block_charge_unknown
}

/** 기상·브리핑 화면의 사유 한 문장. */
@StringRes
fun wakeReasonLabel(reason: WakeReason): Int = when (reason) {
    WakeReason.TIMER -> R.string.wake_reason_timer
    WakeReason.CHARGE_NEARLY_DONE -> R.string.wake_reason_charge_nearly_done
    WakeReason.CHARGE_COMPLETE -> R.string.wake_reason_charge_complete
    WakeReason.CHARGING_INTERRUPTED -> R.string.wake_reason_charging_interrupted
    WakeReason.USER -> R.string.wake_reason_user
}

/** 리포트 목록에 쓰는 짧은 사유. */
@StringRes
fun wakeReasonShortLabel(reason: WakeReason): Int = when (reason) {
    WakeReason.TIMER -> R.string.report_reason_timer
    WakeReason.CHARGE_NEARLY_DONE -> R.string.report_reason_charge_nearly_done
    WakeReason.CHARGE_COMPLETE -> R.string.report_reason_charge_complete
    WakeReason.CHARGING_INTERRUPTED -> R.string.report_reason_charging_interrupted
    WakeReason.USER -> R.string.report_reason_user
}

@Composable
fun noticeText(notice: UiNotice): String = when (notice) {
    is UiNotice.StartBlocked -> stringResource(startBlockReasonLabel(notice.reason))
    is UiNotice.ExtendGranted -> stringResource(R.string.notice_extend_granted, notice.minutes)
    is UiNotice.ExtendDenied -> stringResource(
        when (notice.reason) {
            ExtendDenyReason.MAX_TOTAL -> R.string.notice_extend_denied_max_total
            ExtendDenyReason.CHARGE_ENDS_SOON -> R.string.notice_extend_denied_charge_ends_soon
            ExtendDenyReason.NOT_NAPPING -> R.string.notice_extend_denied_not_napping
        },
    )
}

/** 실패 항목의 종류. 엔진이 주는 "seat: …", "climate: …" 문자열의 접두어로 가른다. */
enum class FailureKind { SEAT, CLIMATE, OTHER }

/**
 * 실패 문자열 목록을 종류별로 한 번씩만 남긴다. 콜론 뒤의 원문(SDK 오류 문구)은 사용자에게 보여 주지 않는다.
 */
fun failureKinds(failures: List<String>): List<FailureKind> = failures.map { failure ->
    when (failure.substringBefore(':').trim()) {
        CabinApplyResult.ERROR_SEAT -> FailureKind.SEAT
        CabinApplyResult.ERROR_CLIMATE -> FailureKind.CLIMATE
        else -> FailureKind.OTHER
    }
}.distinct().sorted()

/** 남은 초를 "18:42" 형식으로 바꾼다. */
fun formatClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d", safe / 60, safe % 60)
}
