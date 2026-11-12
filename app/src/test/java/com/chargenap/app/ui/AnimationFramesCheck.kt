package com.chargenap.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chargenap.app.data.weeklyReport
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import com.chargenap.app.ui.report.sampleRecords
import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.VehicleCapabilities
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 애니메이션 중간 프레임을 찍어 사람이 직접 보는 확인용 테스트. 평소 테스트에서는 건너뛴다.
 *   ./gradlew testDebugUnitTest --tests "com.chargenap.app.ui.AnimationFramesCheck" -Pchargenap.video=true
 * 결과: app/build/animation-frames/<장면>/t<경과ms>.png (1280×720)
 *
 * 화면 시계를 직접 돌린다(`autoAdvance = false`). 이렇게 하면 무한 애니메이션(충전 맥동, 호흡 원, 바람 곡선)도
 * 테스트 정책에 막히지 않고 움직인다. autoAdvance가 true인 동안에는 무한 애니메이션이 취소되며, 한 번 취소된
 * 화면에서는 다시 돌지 않으므로 setContent 전에 꺼 둔다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi", application = Application::class)
class AnimationFramesCheck {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)
    private val todayNoon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val outDir = File("build/animation-frames")

    private val allPass = SafetyReport(CheckResult.PASS, CheckResult.SKIPPED, CheckResult.PASS, CheckResult.PASS)
    private val vehicle = VehicleCapabilities(
        batteryPercent = true,
        chargingState = true,
        remainingChargeTime = false,
        seatRecline = true,
        climate = true,
        doorState = true,
        isDemo = true,
    )

    private var state by mutableStateOf(NapUiState())
    private var route by mutableStateOf(NapRoute.HOME)

    @Before
    fun setUp() {
        assumeTrue("애니메이션 프레임은 -Pchargenap.video=true 일 때만 찍는다", System.getProperty("chargenap.video") == "true")
        compose.mainClock.autoAdvance = false
    }

    private fun charging(session: NapSessionState, percent: Int = 53) = NapUiState(
        session = session,
        chargingState = ChargingState.CHARGING,
        batteryPercent = percent,
        remainingChargeMinutes = 27,
        startBlockers = allPass,
        gearInPark = true,
        anyDoorOpen = false,
        capabilities = vehicle,
        todayEpochDay = today.toEpochDay(),
        demoControlsAvailable = true,
    )

    private val rearSeat = NapSessionState.SafetyCheck(20, SessionMode.NAP, allPass, rearSeatQuestionPending = true, doorConfirmPending = false)
    private val preparing = NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true)
    private val napping = NapSessionState.Napping(
        startedAtMillis = todayNoon,
        wakeAtMillis = todayNoon + 20 * 60_000L,
        plannedMinutes = 20,
        mode = SessionMode.NAP,
        timerOnly = false,
        cabin = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()),
    )
    private val summary = SessionSummary(
        startedAtMillis = todayNoon,
        endedAtMillis = todayNoon + 18 * 60_000L,
        mode = SessionMode.NAP,
        plannedMinutes = 20,
        sleptMinutes = 18,
        batteryStartPercent = 53,
        batteryEndPercent = 71,
        rangeKm = 319,
        wakeReason = WakeReason.CHARGE_NEARLY_DONE,
        seatMoved = true,
        failures = listOf("seat: timeout"),
    )

    /** 처음 화면을 그리고 들어오는 애니메이션이 끝날 때까지 흘린다. */
    private fun show(initial: NapUiState, initialRoute: NapRoute = NapRoute.HOME, settleMillis: Long = 2_500) {
        state = initial
        route = initialRoute
        compose.setContent {
            ChargeNapContent(
                state = state,
                actions = NoopNapActions,
                route = route,
                onNavigate = { route = it },
                demoPanelExpanded = false,
                onToggleDemoPanel = {},
                zone = zone,
            )
        }
        advance(settleMillis)
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    /** 지금부터 33ms씩 흘리며 [marks] 시각(ms)을 지날 때마다 [scene] 폴더에 한 장씩 찍는다. */
    private fun record(scene: String, marks: List<Long>) {
        val dir = File(outDir, scene).apply {
            deleteRecursively()
            mkdirs()
        }
        Snapshot.sendApplyNotifications()
        var t = 0L
        for (mark in marks.sorted()) {
            while (t < mark) {
                advance(STEP_MILLIS)
                t += STEP_MILLIS
            }
            compose.onRoot().captureRoboImage(File(dir, "t%05d.png".format(t)).path)
        }
    }

    @Test
    fun home_ringFillsAndPulses() {
        show(charging(NapSessionState.Idle), settleMillis = 16)
        state = charging(NapSessionState.Offer(Recommendation.Nap(20, 27)))
        record("01_home_fill_pulse", listOf(0, 198, 495, 792, 1_089, 1_485, 2_013, 2_706))
    }

    @Test
    fun homeToSafety_slidesFromRight_rowsStagger() {
        show(charging(NapSessionState.Offer(Recommendation.Nap(20, 27))))
        state = charging(rearSeat)
        record("02_to_safety", listOf(0, 99, 198, 330, 528, 792, 1_089, 1_617))
    }

    @Test
    fun preparing_seatReclines_airFlows() {
        show(charging(rearSeat))
        state = charging(preparing)
        record("03_preparing_recline", listOf(0, 198, 396, 693, 990, 1_287, 1_584, 2_475))
    }

    @Test
    fun preparingToSleep_darkens_thenBreathes() {
        show(charging(preparing))
        state = charging(napping).copy(remainingSeconds = 20 * 60, breathingGuide = true)
        record("04_to_sleep_breathing", listOf(0, 198, 396, 594, 792, 990, 3_003, 4_983, 7_986, 9_999))
    }

    @Test
    fun sleep_digitsRoll_thenWaves() {
        show(charging(napping).copy(remainingSeconds = 18 * 60 + 2, breathingGuide = true))
        state = state.copy(remainingSeconds = 18 * 60 + 1)
        record("05_digit_roll", listOf(0, 99, 198, 297, 429, 693))
        state = state.copy(remainingSeconds = 17 * 60 + 59, breathingGuide = false)
        record("06_breath_to_wave", listOf(0, 198, 495, 792, 1_188, 1_584, 4_983, 9_009))
    }

    @Test
    fun sleepToWaking_brightens_dawnRises() {
        show(charging(napping).copy(remainingSeconds = 0))
        state = charging(NapSessionState.Waking(WakeReason.CHARGE_NEARLY_DONE), percent = 71)
        record("07_to_waking", listOf(0, 165, 330, 594, 891, 1_188, 1_584, 2_508))
    }

    @Test
    fun wakingToBriefing_tilesRiseAndCount() {
        show(charging(NapSessionState.Waking(WakeReason.CHARGE_NEARLY_DONE), percent = 71))
        state = charging(NapSessionState.Briefing(summary), percent = 71)
        record("08_briefing_count", listOf(0, 198, 396, 594, 792, 990, 1_287, 1_881))
    }

    @Test
    fun sleepToAborted_fastThenShake_thenRestored() {
        show(charging(napping).copy(remainingSeconds = 12 * 60))
        state = charging(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null)).copy(anyDoorOpen = true)
        record("09_to_aborted", listOf(0, 66, 132, 198, 297, 429, 561, 792))
        state = state.copy(
            session = NapSessionState.Aborted(
                AbortReason.DOOR_OPENED,
                restoring = false,
                restore = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()),
            ),
            anyDoorOpen = false,
        )
        record("10_aborted_restored", listOf(0, 132, 264, 429, 594, 990))
    }

    @Test
    fun homeToReport_barsGrow_listFades() {
        val records = sampleRecords(today.toEpochDay(), zone)
        show(
            charging(NapSessionState.Idle).copy(
                weeklyReport = weeklyReport(records, todayNoon + 3 * 60 * 60_000L, zone),
                recentRecords = records,
            ),
        )
        route = NapRoute.REPORT
        record("11_to_report", listOf(0, 198, 396, 594, 792, 990, 1_287, 1_683))
    }

    @Test
    fun notice_risesThenSinks() {
        show(charging(NapSessionState.Idle))
        state = state.copy(notice = UiNotice.ExtendGranted(5))
        record("12_notice_in", listOf(0, 99, 198, 330, 495))
        state = state.copy(notice = null)
        record("13_notice_out", listOf(0, 99, 198, 330))
    }

    private companion object {
        const val STEP_MILLIS = 33L
    }
}
