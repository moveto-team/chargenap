package com.chargenap.app.ui

import android.app.Application
import com.chargenap.app.data.AppSettings
import com.chargenap.app.data.WeeklyReport
import com.chargenap.app.data.weeklyReport
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.ExtendDenyReason
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * 화면·상태별 스냅샷. Pleos Connect 화면과 같은 2,560×1,440px, 160dpi로 그려 `docs/screenshots/`에 PNG로 남긴다.
 *
 * ViewModel 없이 [ChargeNapContent]에 상태 값만 넣어 그린다. 기록은 `gradle.properties`의
 * `roborazzi.test.record=true`로 켜져 있다. 찍힌 PNG는 사람이 직접 열어 잘림·겹침을 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-land-mdpi", application = Application::class)
class ScreenSnapshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)
    private val todayNoon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val allPass = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS)
    private val realVehicle = VehicleCapabilities(
        batteryPercent = true,
        chargingState = true,
        remainingChargeTime = false,
        seatRecline = true,
        climate = true,
        doorState = true,
        isDemo = false,
    )
    private val demoVehicle = realVehicle.copy(isDemo = true)

    /** 충전 중 + 기어 P인 차량. [demo]면 가상 차량(DEMO 배지와 조작 패널이 붙는다). */
    private fun charging(session: NapSessionState, demo: Boolean, percent: Int = 53, remaining: Int? = 27) = NapUiState(
        session = session,
        chargingState = ChargingState.CHARGING,
        batteryPercent = percent,
        remainingChargeMinutes = remaining,
        startBlockers = allPass,
        gearInPark = true,
        anyDoorOpen = false,
        capabilities = if (demo) demoVehicle else realVehicle,
        todayEpochDay = today.toEpochDay(),
        demoControlsAvailable = demo,
    )

    private fun napping(timerOnly: Boolean = false) = NapSessionState.Napping(
        startedAtMillis = todayNoon,
        wakeAtMillis = todayNoon + 20 * 60_000L,
        plannedMinutes = 20,
        mode = SessionMode.NAP,
        timerOnly = timerOnly,
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
        failures = emptyList(),
    )

    /**
     * 화면을 그리고 들어오는 애니메이션(고리 채우기, 카드 떠오르기, 숫자 세기, 막대 자라기)이 끝난 최종 모습을 찍는다.
     *
     * `waitForIdle()`은 테스트 시계를 애니메이션이 끝날 때까지 흘린다. 무한 애니메이션(충전 맥동, 호흡 원, 바람 곡선,
     * 복구 중 호)은 Compose 테스트 정책상 테스트 시계에서 돌지 않으므로 첫 모습으로 멈춘 채 찍힌다.
     * 코루틴 `delay`(수면 화면 버튼이 3초 뒤 사라지는 것)는 흘리지 않는다.
     */
    private fun capture(
        name: String,
        state: NapUiState,
        route: NapRoute = NapRoute.HOME,
        demoPanelExpanded: Boolean = false,
        sleepControlsVisible: Boolean = false,
    ) {
        compose.setContent {
            ChargeNapContent(
                state = state,
                actions = NoopNapActions,
                route = route,
                onNavigate = {},
                demoPanelExpanded = demoPanelExpanded,
                onToggleDemoPanel = {},
                sleepControlsInitiallyVisible = sleepControlsVisible,
                zone = zone,
            )
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(filePath = "../docs/screenshots/$name.png")
    }

    // ---------------------------------------------------------------- 홈

    @Test
    fun home_notCharging_showsBlockReason() = capture(
        "01_home_not_charging",
        NapUiState(
            chargingState = ChargingState.NOT_CONNECTED,
            batteryPercent = 41,
            startBlockers = allPass.copy(charging = CheckResult.FAIL),
            gearInPark = true,
            anyDoorOpen = false,
            capabilities = realVehicle,
        ),
    )

    @Test
    fun home_offerNap20() = capture(
        "02_home_offer_nap",
        charging(NapSessionState.Offer(Recommendation.Nap(20, 27)), demo = true).copy(voiceAvailable = true),
    )

    @Test
    fun home_offerChooseDuration() = capture(
        "03_home_offer_choose",
        charging(NapSessionState.Offer(Recommendation.ChooseDuration(listOf(15, 20))), demo = true, remaining = null),
    )

    @Test
    fun home_offerBreathing() = capture(
        "04_home_offer_breathing",
        charging(NapSessionState.Offer(Recommendation.Breathing(5, 12)), demo = false, percent = 74, remaining = 12),
    )

    @Test
    fun home_idleCharging_pickDuration() = capture(
        "05_home_pick_duration",
        charging(NapSessionState.Idle, demo = false),
    )

    @Test
    fun home_gearNotPark_fallbackDemo() = capture(
        "06_home_gear_not_park_fallback",
        charging(NapSessionState.Idle, demo = true).copy(
            startBlockers = allPass.copy(gear = CheckResult.FAIL),
            gearInPark = false,
            fallbackReason = "차량 서비스 연결 실패",
        ),
    )

    @Test
    fun home_demoPanelExpanded() = capture(
        "07_home_demo_panel",
        charging(NapSessionState.Offer(Recommendation.Nap(20, 27)), demo = true),
        demoPanelExpanded = true,
    )

    @Test
    fun home_batteryUnknown_withNotice() = capture(
        "08_home_unknown_notice",
        NapUiState(
            chargingState = ChargingState.UNKNOWN,
            batteryPercent = null,
            capabilities = realVehicle.copy(batteryPercent = false, chargingState = false),
            notice = UiNotice.StartBlocked(StartBlockReason.CHARGE_UNKNOWN),
        ),
    )

    // ---------------------------------------------------------------- 안전 확인

    @Test
    fun safety_rearSeatQuestion() = capture(
        "10_safety_rear_seat",
        charging(
            NapSessionState.SafetyCheck(
                minutes = 20,
                mode = SessionMode.NAP,
                report = allPass.copy(parkingBrake = CheckResult.SKIPPED),
                rearSeatQuestionPending = true,
                doorConfirmPending = false,
            ),
            demo = true,
        ),
    )

    @Test
    fun safety_doorOpen_waiting() = capture(
        "11_safety_door_open",
        charging(
            NapSessionState.SafetyCheck(
                minutes = 20,
                mode = SessionMode.NAP,
                report = allPass.copy(doors = CheckResult.FAIL),
                rearSeatQuestionPending = false,
                doorConfirmPending = false,
            ),
            demo = false,
        ).copy(anyDoorOpen = true),
    )

    @Test
    fun safety_doorConfirm() = capture(
        "12_safety_door_confirm",
        charging(
            NapSessionState.SafetyCheck(
                minutes = 5,
                mode = SessionMode.BREATHING,
                report = allPass.copy(doors = CheckResult.NEEDS_USER_CONFIRM),
                rearSeatQuestionPending = false,
                doorConfirmPending = true,
            ),
            demo = false,
        ).copy(anyDoorOpen = null),
    )

    @Test
    fun preparing() = capture(
        "13_preparing",
        charging(NapSessionState.Preparing(minutes = 20, mode = SessionMode.NAP, seatConsent = true), demo = true),
    )

    // ---------------------------------------------------------------- 수면

    /** 수면 시작 1분 18초 뒤라 호흡 안내 구간이다(숫자 뒤 호흡 원, "들이마시고"). */
    @Test
    fun sleep() = capture(
        "20_sleep",
        charging(napping(), demo = false).copy(remainingSeconds = 18 * 60 + 42, breathingGuide = true),
    )

    @Test
    fun sleep_controlsVisible() = capture(
        "21_sleep_controls",
        charging(napping(timerOnly = true), demo = true, remaining = null).copy(remainingSeconds = 4 * 60 + 5),
        sleepControlsVisible = true,
    )

    @Test
    fun sleep_demoPanelAndNotice() = capture(
        "22_sleep_demo_panel_notice",
        charging(napping(), demo = true).copy(
            remainingSeconds = 12 * 60 + 30,
            notice = UiNotice.ExtendDenied(ExtendDenyReason.CHARGE_ENDS_SOON),
        ),
        demoPanelExpanded = true,
        sleepControlsVisible = true,
    )

    // ---------------------------------------------------------------- 기상·브리핑

    @Test
    fun waking() = capture(
        "30_waking",
        charging(NapSessionState.Waking(WakeReason.CHARGE_NEARLY_DONE), demo = true, percent = 71, remaining = 5),
    )

    @Test
    fun briefing_normal() = capture(
        "31_briefing",
        charging(NapSessionState.Briefing(summary), demo = true, percent = 71, remaining = 5),
    )

    @Test
    fun briefing_failuresAndChargingInterrupted() = capture(
        "32_briefing_failures",
        charging(
            NapSessionState.Briefing(
                summary.copy(
                    sleptMinutes = 7,
                    batteryEndPercent = 60,
                    rangeKm = null,
                    wakeReason = WakeReason.CHARGING_INTERRUPTED,
                    failures = listOf("seat: SDK timeout", "climate: rejected", "climate: restore failed"),
                ),
            ),
            demo = false,
            percent = 60,
            remaining = null,
        ).copy(chargingState = ChargingState.CONNECTED_IDLE),
    )

    // ---------------------------------------------------------------- 중단 안내

    @Test
    fun aborted_doorWaiting() = capture(
        "40_aborted_door_waiting",
        charging(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null), demo = true)
            .copy(anyDoorOpen = true),
    )

    @Test
    fun aborted_gearRestoring() = capture(
        "41_aborted_gear_restoring",
        charging(NapSessionState.Aborted(AbortReason.GEAR_LEFT_PARK, restoring = true, restore = null), demo = false)
            .copy(gearInPark = false, startBlockers = allPass.copy(gear = CheckResult.FAIL)),
    )

    @Test
    fun aborted_restored() = capture(
        "42_aborted_restored",
        charging(
            NapSessionState.Aborted(
                AbortReason.DOOR_OPENED,
                restoring = false,
                restore = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()),
            ),
            demo = true,
        ),
    )

    @Test
    fun aborted_restoreFailed() = capture(
        "43_aborted_restore_failed",
        charging(
            NapSessionState.Aborted(
                AbortReason.GEAR_LEFT_PARK,
                restoring = false,
                restore = CabinApplyResult(
                    seatApplied = false,
                    climateApplied = false,
                    errors = listOf("seat: timeout", "climate: rejected"),
                ),
            ),
            demo = false,
        ).copy(gearInPark = false, startBlockers = allPass.copy(gear = CheckResult.FAIL)),
    )

    // ---------------------------------------------------------------- 16:9가 아닌 화면

    @Test
    @Config(qualifiers = "w2880dp-h1080dp-land-mdpi")
    fun ultraWide_home() = capture(
        "90_ultrawide_home",
        charging(NapSessionState.Offer(Recommendation.Nap(20, 27)), demo = true),
    )

    @Test
    @Config(qualifiers = "w2880dp-h1080dp-land-mdpi")
    fun ultraWide_briefingFailures() = capture(
        "91_ultrawide_briefing",
        charging(
            NapSessionState.Briefing(summary.copy(rangeKm = null, failures = listOf("seat: timeout", "climate: rejected"))),
            demo = true,
        ),
    )

    @Test
    @Config(qualifiers = "w1600dp-h1200dp-land-mdpi")
    fun fourByThree_safety() = capture(
        "92_4x3_safety",
        charging(
            NapSessionState.SafetyCheck(
                minutes = 20,
                mode = SessionMode.NAP,
                report = allPass.copy(parkingBrake = CheckResult.FAIL),
                rearSeatQuestionPending = false,
                doorConfirmPending = false,
            ),
            demo = true,
        ),
    )

    @Test
    @Config(qualifiers = "w1600dp-h1200dp-land-mdpi")
    fun fourByThree_settings() = capture(
        "93_4x3_settings",
        charging(NapSessionState.Idle, demo = false),
        route = NapRoute.SETTINGS,
    )

    // ---------------------------------------------------------------- 리포트와 설정

    @Test
    fun report_withRecords() {
        val records = sampleRecords(today.toEpochDay(), zone)
        capture(
            "50_report",
            charging(NapSessionState.Idle, demo = true).copy(
                weeklyReport = weeklyReport(records, todayNoon + 3 * 60 * 60_000L, zone),
                recentRecords = records,
            ),
            route = NapRoute.REPORT,
        )
    }

    @Test
    fun report_empty() = capture(
        "51_report_empty",
        charging(NapSessionState.Idle, demo = false).copy(weeklyReport = WeeklyReport.EMPTY, recentRecords = emptyList()),
        route = NapRoute.REPORT,
    )

    @Test
    fun settings() = capture(
        "60_settings",
        charging(NapSessionState.Idle, demo = true).copy(settings = AppSettings(temperatureC = 23.5f, demoMode = true)),
        route = NapRoute.SETTINGS,
    )

    @Test
    fun settings_unsupportedVehicle() = capture(
        "61_settings_unsupported",
        charging(NapSessionState.Idle, demo = false).copy(
            capabilities = realVehicle.copy(seatRecline = false, climate = false),
            settings = AppSettings(targetPercent = 100, voiceEnabled = false),
        ),
        route = NapRoute.SETTINGS,
    )
}
