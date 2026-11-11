package com.chargenap.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTimeEstimatorTest {

    private val sec = 1_000L
    private val min = 60_000L

    /** 0분 50%, 1분 51%(기준점), 2분 52% → 분당 1%. */
    private fun steadyOnePercentPerMinute() = ChargeTimeEstimator().apply {
        addSample(0, 50)
        addSample(1 * min, 51)
        addSample(2 * min, 52)
    }

    // ---- 남은 시간 추정 ----

    @Test
    fun sdkValue_wins() {
        val estimator = steadyOnePercentPerMinute()
        assertEquals(28, estimator.remainingMinutes(2 * min, 80))

        estimator.setSdkRemaining(2 * min, 12)
        assertEquals(12, estimator.remainingMinutes(2 * min, 80))
        assertEquals(14 * min, estimator.chargeEndAtMillis(2 * min, 80))

        // 같은 값이 다시 와도 기준 시각은 값이 바뀐 때 그대로다(완료 예상 시각이 뒤로 밀리지 않는다).
        estimator.setSdkRemaining(2 * min + 30 * sec, 12)
        assertEquals(12, estimator.remainingMinutes(2 * min + 30 * sec, 80))
        assertEquals(14 * min, estimator.chargeEndAtMillis(2 * min + 30 * sec, 80))

        // SDK 값이 사라지면 표본 추정으로 돌아간다.
        estimator.setSdkRemaining(3 * min, null)
        assertEquals(27, estimator.remainingMinutes(3 * min, 80))
    }

    @Test
    fun sdkValue_worksWithoutAnySample() {
        val estimator = ChargeTimeEstimator()
        estimator.setSdkRemaining(5 * sec, 33)
        assertEquals(33, estimator.remainingMinutes(5 * sec, 80))
        assertEquals(5 * sec + 33 * min, estimator.chargeEndAtMillis(5 * sec, 80))
    }

    @Test
    fun twoChangePoints_estimatesRate() {
        val estimator = ChargeTimeEstimator()
        estimator.addSample(0, 50)            // 관측 시작. 50%가 언제부터였는지 모른다
        estimator.addSample(30 * sec, 51)     // 첫 변화: 기준점으로만 쓴다
        assertNull(estimator.ratePerMinute(30 * sec))
        estimator.addSample(60 * sec, 51)     // 값이 그대로인 표본은 무시
        estimator.addSample(90 * sec, 52)     // 둘째 변화: 1분에 1%

        assertEquals(1.0, estimator.ratePerMinute(90 * sec)!!, 1e-9)
        assertEquals(28, estimator.remainingMinutes(90 * sec, 80))
        assertEquals(90 * sec + 28 * min, estimator.chargeEndAtMillis(90 * sec, 80))
        // 다음 변화 전까지는 시간이 흐른 만큼 줄어든다.
        assertEquals(27, estimator.remainingMinutes(150 * sec, 80))
        // 목표 잔량을 바꾸면 그만큼 달라진다.
        assertEquals(38, estimator.remainingMinutes(90 * sec, 90))
    }

    @Test
    fun laterChangePoints_areSmoothedWithEma() {
        val estimator = ChargeTimeEstimator()
        estimator.addSample(0, 50)
        estimator.addSample(30 * sec, 51)
        estimator.addSample(90 * sec, 52)     // 1%/분
        estimator.addSample(120 * sec, 53)    // 이번 구간은 2%/분 → 0.3 * 2 + 0.7 * 1
        assertEquals(1.3, estimator.ratePerMinute(120 * sec)!!, 1e-9)
    }

    @Test
    fun singleChangePoint_returnsNull() {
        val estimator = ChargeTimeEstimator()
        assertNull(estimator.remainingMinutes(0, 80))

        estimator.addSample(0, 50)
        assertNull(estimator.remainingMinutes(0, 80))

        estimator.addSample(1 * min, 51)
        assertNull(estimator.ratePerMinute(1 * min))
        assertNull(estimator.remainingMinutes(1 * min, 80))
        assertNull(estimator.chargeEndAtMillis(1 * min, 80))
    }

    @Test
    fun zeroRate_returnsNull() {
        // 한 번도 오르지 않는 경우
        val flat = ChargeTimeEstimator()
        flat.addSample(0, 50)
        flat.addSample(1 * min, 50)
        flat.addSample(10 * min, 50)
        assertNull(flat.ratePerMinute(10 * min))
        assertNull(flat.remainingMinutes(10 * min, 80))

        // 오르다가 멈춘 경우(충전기 출력 제한): 1%에 1분 걸리던 것이 3분 넘게 그대로면 추정 불가
        val stalled = steadyOnePercentPerMinute()
        stalled.addSample(4 * min, 52)
        assertEquals(26, stalled.remainingMinutes(4 * min, 80))
        stalled.addSample(6 * min, 52)
        assertNull(stalled.ratePerMinute(6 * min))
        assertNull(stalled.remainingMinutes(6 * min, 80))
        assertNull(stalled.chargeEndAtMillis(6 * min, 80))

        // 다시 오르면 느려진 속도를 반영해 추정을 재개한다: 0.3 * (1%/5분) + 0.7 * 1
        stalled.addSample(7 * min, 53)
        assertEquals(0.76, stalled.ratePerMinute(7 * min)!!, 1e-9)
        assertNotNull(stalled.remainingMinutes(7 * min, 80))
    }

    @Test
    fun estimator_decreasingPercent_returnsNull() {
        val estimator = steadyOnePercentPerMinute()
        assertEquals(28, estimator.remainingMinutes(2 * min, 80))

        estimator.addSample(3 * min, 51)      // 잔량이 내려감
        assertNull(estimator.ratePerMinute(3 * min))
        assertNull(estimator.remainingMinutes(3 * min, 80))
        assertNull(estimator.chargeEndAtMillis(3 * min, 80))

        estimator.addSample(4 * min, 50)      // 계속 내려가도 음수·무한대가 나오지 않는다
        assertNull(estimator.remainingMinutes(4 * min, 80))

        estimator.addSample(5 * min, 51)      // 다시 오르면 그 구간부터 새로 추정
        assertEquals(1.0, estimator.ratePerMinute(5 * min)!!, 1e-9)
        assertEquals(29, estimator.remainingMinutes(5 * min, 80))
    }

    @Test
    fun targetAlreadyReached_isZeroNotNegative() {
        val estimator = steadyOnePercentPerMinute()
        assertEquals(0, estimator.remainingMinutes(2 * min, 52))
        assertEquals(0, estimator.remainingMinutes(2 * min, 40))
        assertTrue(estimator.chargeEndAtMillis(2 * min, 40)!! <= 2 * min)
    }

    @Test
    fun reset_forgetsEverything() {
        val estimator = steadyOnePercentPerMinute()
        estimator.setSdkRemaining(2 * min, 10)
        estimator.reset()
        assertNull(estimator.remainingMinutes(2 * min, 80))
        estimator.addSample(3 * min, 53)      // reset 뒤 첫 표본은 다시 "관측 시작"이다
        estimator.addSample(4 * min, 54)
        assertNull(estimator.remainingMinutes(4 * min, 80))
    }

    // ---- 추천 세션 ----

    @Test
    fun recommend_14min_isBreathing5() {
        assertEquals(Recommendation.Breathing(5, 14), ChargeTimeEstimator.recommend(14))
        assertEquals(Recommendation.Breathing(5, 10), ChargeTimeEstimator.recommend(10))
    }

    @Test
    fun recommend_15min_isNap10() {
        assertEquals(Recommendation.Nap(10, 15), ChargeTimeEstimator.recommend(15))
        assertEquals(Recommendation.Nap(10, 19), ChargeTimeEstimator.recommend(19))
        assertEquals(Recommendation.Nap(15, 20), ChargeTimeEstimator.recommend(20))
    }

    @Test
    fun recommend_27min_isNap20() {
        assertEquals(Recommendation.Nap(20, 27), ChargeTimeEstimator.recommend(27))
        assertEquals(Recommendation.Nap(20, 29), ChargeTimeEstimator.recommend(29))
    }

    @Test
    fun recommend_45min_isNap25() {
        assertEquals(Recommendation.Nap(25, 45), ChargeTimeEstimator.recommend(45))
        assertEquals(Recommendation.Nap(25, 30), ChargeTimeEstimator.recommend(30))
        assertEquals(Recommendation.Nap(25, 600), ChargeTimeEstimator.recommend(600))
    }

    @Test
    fun recommend_under10min_returnsNone() {
        assertEquals(Recommendation.None, ChargeTimeEstimator.recommend(9))
        assertEquals(Recommendation.None, ChargeTimeEstimator.recommend(5))
        assertEquals(Recommendation.None, ChargeTimeEstimator.recommend(0))
        assertEquals(Recommendation.None, ChargeTimeEstimator.recommend(-3))
    }

    @Test
    fun recommend_null_isChoose15or20() {
        assertEquals(Recommendation.ChooseDuration(listOf(15, 20)), ChargeTimeEstimator.recommend(null))
    }
}
