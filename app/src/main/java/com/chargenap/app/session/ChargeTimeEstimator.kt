package com.chargenap.app.session

import kotlin.math.ceil

/**
 * 남은 충전 시간 추정 (킥오프 4.3).
 *
 * 1. SDK가 남은 시간을 주면([setSdkRemaining]) 그 값을 쓴다.
 * 2. 없으면 `(시각, 잔량%)` 표본([addSample])으로 추정한다.
 *    - 잔량 값이 **바뀐 시점**만 표본으로 쓴다. 관측을 시작한 뒤 첫 변화는 기준점으로만 쓰고
 *      (그 전 구간은 길이를 모른다), 변화점 2개부터 분당 증가율을 지수이동평균으로 낸다.
 *    - 잔량이 내려가면 증가율을 버린다. 마지막 변화 뒤 예상 간격의 [STALL_FACTOR]배가 지나도록
 *      잔량이 그대로면 증가율 0으로 본다. 두 경우 모두 추정 불가(null)다.
 *
 * 스레드 안전하지 않다. 한 코루틴에서만 쓴다.
 */
class ChargeTimeEstimator(private val alpha: Double = DEFAULT_ALPHA) {

    private var lastPercent: Int? = null
    private var anchorMillis: Long? = null      // 가장 최근 변화점의 시각
    private var anchorPercent = 0               // 가장 최근 변화점의 잔량
    private var lastStepPercent = 1             // 가장 최근 변화의 크기(%)
    private var emaRate: Double? = null         // 분당 증가율(%)

    private var sdkMinutes: Int? = null
    private var sdkAnchorMillis = 0L            // SDK 값이 지금 값으로 바뀐 시각

    /** 새 충전이 시작될 때 부른다. 표본과 SDK 값을 모두 잊는다. */
    fun reset() {
        lastPercent = null
        anchorMillis = null
        anchorPercent = 0
        lastStepPercent = 1
        emaRate = null
        sdkMinutes = null
        sdkAnchorMillis = 0L
    }

    /** 잔량 관측값을 넣는다. 같은 값을 여러 번 넣어도 된다(바뀐 시점만 쓴다). */
    fun addSample(timeMillis: Long, percent: Int) {
        val previous = lastPercent
        lastPercent = percent
        if (previous == null || previous == percent) return

        val anchor = anchorMillis
        if (percent < previous) {
            emaRate = null
        } else if (anchor != null && timeMillis > anchor) {
            val step = percent - anchorPercent
            val instant = step / ((timeMillis - anchor) / MILLIS_PER_MINUTE)
            emaRate = emaRate?.let { alpha * instant + (1 - alpha) * it } ?: instant
            lastStepPercent = step.coerceAtLeast(1)
        }
        anchorMillis = timeMillis
        anchorPercent = percent
    }

    /** SDK가 주는 남은 충전 시간(분). 주지 않으면 null. 값이 바뀐 시각을 기준으로 완료 시각을 잡는다. */
    fun setSdkRemaining(timeMillis: Long, minutes: Int?) {
        val normalized = minutes?.coerceAtLeast(0)
        if (normalized == sdkMinutes) return
        sdkMinutes = normalized
        sdkAnchorMillis = timeMillis
    }

    /** 표본으로 구한 분당 증가율(%). 변화점이 2개 미만이거나 증가율이 0 이하(멈춤 포함)면 null. */
    fun ratePerMinute(nowMillis: Long): Double? {
        val rate = emaRate ?: return null
        val anchor = anchorMillis ?: return null
        if (rate.isNaN() || rate.isInfinite() || rate <= 0.0) return null
        val expectedStepMillis = lastStepPercent / rate * MILLIS_PER_MINUTE
        if (nowMillis - anchor > STALL_FACTOR * expectedStepMillis) return null
        return rate
    }

    /** 충전이 [targetPercent]에 닿을 것으로 보이는 시각. 추정 불가면 null. */
    fun chargeEndAtMillis(nowMillis: Long, targetPercent: Int): Long? {
        sdkMinutes?.let { return sdkAnchorMillis + it * 60_000L }
        val rate = ratePerMinute(nowMillis) ?: return null
        val anchor = anchorMillis ?: return null
        val percentLeft = (targetPercent - anchorPercent).coerceAtLeast(0)
        val minutesLeft = (percentLeft / rate).coerceAtMost(MAX_MINUTES)
        return anchor + (minutesLeft * MILLIS_PER_MINUTE).toLong()
    }

    /** 남은 충전 시간(분, 올림). SDK 값이 있으면 그 값 그대로. 추정 불가면 null. 음수는 나오지 않는다. */
    fun remainingMinutes(nowMillis: Long, targetPercent: Int): Int? {
        sdkMinutes?.let { return it }
        val endAt = chargeEndAtMillis(nowMillis, targetPercent) ?: return null
        return ceil((endAt - nowMillis) / MILLIS_PER_MINUTE).toInt().coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_ALPHA = 0.3

        /** 추천 세션 상한(분). */
        const val MAX_RECOMMENDED_MINUTES = 25

        /** 충전 완료 전에 남겨 두는 여유(분). */
        const val CHARGE_MARGIN_MINUTES = 5

        /** 호흡 모드 길이(분). */
        const val BREATHING_MINUTES = 5

        /** 추정 불가일 때 고르게 하는 길이(분). */
        val CHOOSE_OPTIONS: List<Int> = listOf(15, 20)

        private const val MIN_NAP_MINUTES = 10
        private const val STEP_MINUTES = 5
        private const val STALL_FACTOR = 3.0
        private const val MILLIS_PER_MINUTE = 60_000.0
        private const val MAX_MINUTES = 7 * 24 * 60.0

        /**
         * 추천 세션. `min(남은 분 − 5, 25)`를 5분 단위로 내린다.
         * - 추정 불가(null) → 15분과 20분 중 고르기
         * - 남은 시간 10분 미만 → 제안하지 않음(호흡 5분도 "완료 5분 전"에 맞지 않는다)
         * - 결과가 10분 미만 → 5분 호흡 모드
         */
        fun recommend(remainingMinutes: Int?): Recommendation {
            if (remainingMinutes == null) return Recommendation.ChooseDuration(CHOOSE_OPTIONS)
            if (remainingMinutes < MIN_NAP_MINUTES) return Recommendation.None
            val capped = minOf(remainingMinutes - CHARGE_MARGIN_MINUTES, MAX_RECOMMENDED_MINUTES)
            val minutes = capped / STEP_MINUTES * STEP_MINUTES
            return if (minutes < MIN_NAP_MINUTES) {
                Recommendation.Breathing(BREATHING_MINUTES, remainingMinutes)
            } else {
                Recommendation.Nap(minutes, remainingMinutes)
            }
        }
    }
}
