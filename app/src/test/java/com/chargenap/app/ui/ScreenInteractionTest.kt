package com.chargenap.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.chargenap.app.session.AbortReason
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.WakeReason
import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.VehicleCapabilities
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 음성 없이 터치만으로 모든 엔진 이벤트를 보낼 수 있는지 확인한다.
 * [ChargeNapContent]에 상태를 넣고, 화면의 버튼을 눌러 [NapActions]로 무엇이 전달되는지 본다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-land-mdpi", application = Application::class)
class ScreenInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : NapActions {
        override fun requestStart(minutes: Int?) { calls += "requestStart($minutes)" }
        override fun declineOffer() { calls += "declineOffer" }
        override fun cancel() { calls += "cancel" }
        override fun answerRearSeat(clear: Boolean) { calls += "answerRearSeat($clear)" }
        override fun confirmDoorsClosed() { calls += "confirmDoorsClosed" }
        override fun requestExtend() { calls += "requestExtend" }
        override fun requestWake() { calls += "requestWake" }
        override fun acknowledgeBriefing() { calls += "acknowledgeBriefing" }
        override fun acknowledgeAbort() { calls += "acknowledgeAbort" }
        override fun dismissNotice() { calls += "dismissNotice" }
        override fun setReclineLevel(level: Int) { calls += "setReclineLevel($level)" }
        override fun setTemperatureC(temperatureC: Float) { calls += "setTemperatureC($temperatureC)" }
        override fun setFanLevel(level: Int) { calls += "setFanLevel($level)" }
        override fun setTargetPercent(percent: Int) { calls += "setTargetPercent($percent)" }
        override fun setSeatControlEnabled(enabled: Boolean) { calls += "setSeatControlEnabled($enabled)" }
        override fun setVoiceEnabled(enabled: Boolean) { calls += "setVoiceEnabled($enabled)" }
        override fun setDemoMode(enabled: Boolean) { calls += "setDemoMode($enabled)" }
        override fun demoStartCharging() { calls += "demoStartCharging" }
        override fun demoInterruptCharging() { calls += "demoInterruptCharging" }
        override fun demoCompleteCharging() { calls += "demoCompleteCharging" }
        override fun demoSetDoorOpen(open: Boolean) { calls += "demoSetDoorOpen($open)" }
        override fun demoSetGearInPark(inPark: Boolean) { calls += "demoSetGearInPark($inPark)" }
    }

    private val allPass = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS)
    private val capabilities = VehicleCapabilities(
        batteryPercent = true,
        chargingState = true,
        remainingChargeTime = false,
        seatRecline = true,
        climate = true,
        doorState = true,
        isDemo = true,
    )

    private fun charging(session: NapSessionState) = NapUiState(
        session = session,
        chargingState = ChargingState.CHARGING,
        batteryPercent = 53,
        remainingChargeMinutes = 27,
        startBlockers = allPass,
        gearInPark = true,
        anyDoorOpen = false,
        capabilities = capabilities,
        demoControlsAvailable = true,
    )

    /** 이동과 패널 펼침 상태는 실제 루트([ChargeNapScreens])처럼 화면 쪽에서 들고 있는다. */
    private fun show(state: NapUiState, initialRoute: NapRoute = NapRoute.HOME) {
        compose.setContent {
            var route by remember { mutableStateOf(initialRoute) }
            var expanded by remember { mutableStateOf(false) }
            ChargeNapContent(
                state = state,
                actions = actions,
                route = route,
                onNavigate = { route = it },
                demoPanelExpanded = expanded,
                onToggleDemoPanel = { expanded = !expanded },
            )
        }
    }

    @Test
    fun offer_startAndDecline() {
        show(charging(NapSessionState.Offer(Recommendation.Nap(20, 27))))

        compose.onNodeWithText("20분 파워냅 어때요?").assertIsDisplayed()
        compose.onNodeWithText("DEMO").assertIsDisplayed()
        compose.onNodeWithText("시작").performClick()
        compose.onNodeWithText("괜찮아요").performClick()

        assertEquals(listOf("requestStart(20)", "declineOffer"), calls)
    }

    @Test
    fun offerChooseDuration_startsWithChosenMinutes() {
        show(charging(NapSessionState.Offer(Recommendation.ChooseDuration(listOf(15, 20)))))

        compose.onNodeWithText("15분").performClick()
        compose.onNodeWithText("20분").performClick()

        assertEquals(listOf("requestStart(15)", "requestStart(20)"), calls)
    }

    @Test
    fun idleCharging_pickDurationThenStart() {
        show(charging(NapSessionState.Idle))

        compose.onNodeWithText("20분 파워냅 시작").assertIsDisplayed()
        compose.onNodeWithText("15분").performClick()
        compose.onNodeWithText("15분 파워냅 시작").performClick()

        assertEquals(listOf("requestStart(15)"), calls)
    }

    @Test
    fun idleNotCharging_startDisabledWithReason() {
        show(
            charging(NapSessionState.Idle).copy(
                chargingState = ChargingState.NOT_CONNECTED,
                startBlockers = allPass.copy(charging = CheckResult.FAIL),
            ),
        )

        compose.onNodeWithText("충전기를 연결하면 시작할 수 있어요").assertIsDisplayed()
        compose.onNodeWithText("파워냅 시작").assertIsNotEnabled()
        compose.onNodeWithText("파워냅 시작").performClick()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun safetyCheck_rearSeatAnswersAndCancel() {
        show(
            charging(
                NapSessionState.SafetyCheck(20, SessionMode.NAP, allPass, rearSeatQuestionPending = true, doorConfirmPending = false),
            ),
        )

        compose.onNodeWithText("뒷좌석에 사람이나 짐이 있나요?").assertIsDisplayed()
        compose.onNodeWithText("없어요").performClick()
        compose.onNodeWithText("있어요").performClick()
        compose.onNodeWithText("취소").performClick()

        assertEquals(listOf("answerRearSeat(true)", "answerRearSeat(false)", "cancel"), calls)
    }

    @Test
    fun safetyCheck_doorConfirm() {
        show(
            charging(
                NapSessionState.SafetyCheck(
                    20,
                    SessionMode.NAP,
                    allPass.copy(doors = CheckResult.NEEDS_USER_CONFIRM, parkingBrake = CheckResult.SKIPPED),
                    rearSeatQuestionPending = false,
                    doorConfirmPending = true,
                ),
            ).copy(anyDoorOpen = null),
        )

        compose.onNodeWithText("확인할 수 없어 건너뜀").assertIsDisplayed()
        compose.onNodeWithText("닫혀 있어요").performClick()

        assertEquals(listOf("confirmDoorsClosed"), calls)
    }

    @Test
    fun preparing_canCancel() {
        show(charging(NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true)))

        compose.onNodeWithText("편안한 자리를 준비하고 있어요").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()

        assertEquals(listOf("cancel"), calls)
    }

    private fun napping() = charging(
        NapSessionState.Napping(
            startedAtMillis = 0,
            wakeAtMillis = 20 * 60_000L,
            plannedMinutes = 20,
            mode = SessionMode.NAP,
            timerOnly = true,
            cabin = CabinApplyResult.NOTHING,
        ),
    ).copy(remainingSeconds = 18 * 60 + 42)

    @Test
    fun sleep_longPressWakes() {
        show(napping())

        compose.onNodeWithText("18:42").assertIsDisplayed()
        compose.onNodeWithText("타이머로만 동작").assertIsDisplayed()
        compose.onNodeWithText("18:42").performTouchInput { longClick() }

        assertEquals(listOf("requestWake"), calls)
    }

    @Test
    fun sleep_tapShowsButtonsForThreeSeconds() {
        show(napping())
        compose.onNodeWithText("5분 더").assertDoesNotExist()

        compose.onNodeWithText("18:42").performClick()
        compose.onNodeWithText("5분 더").performClick()
        compose.onNodeWithText("지금 일어나기").performClick()
        assertEquals(listOf("requestExtend", "requestWake"), calls)

        compose.mainClock.advanceTimeBy(3_500)
        compose.onNodeWithText("5분 더").assertDoesNotExist()
    }

    @Test
    fun briefing_confirm() {
        val summary = SessionSummary(
            startedAtMillis = 0,
            endedAtMillis = 18 * 60_000L,
            mode = SessionMode.NAP,
            plannedMinutes = 20,
            sleptMinutes = 18,
            batteryStartPercent = 53,
            batteryEndPercent = 71,
            rangeKm = 319,
            wakeReason = WakeReason.TIMER,
            seatMoved = true,
            failures = listOf("seat: timeout"),
        )
        show(charging(NapSessionState.Briefing(summary)))

        compose.onNodeWithText("53 → 71").assertIsDisplayed()
        compose.onNodeWithText("319").assertIsDisplayed()
        compose.onNodeWithText("시트를 제대로 움직이지 못했어요. 등받이를 직접 확인해 주세요").assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()

        assertEquals(listOf("acknowledgeBriefing"), calls)
    }

    @Test
    fun aborted_doorWaiting_restoreNowThenShowsRestoring() {
        show(
            charging(NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null))
                .copy(anyDoorOpen = true),
        )

        compose.onNodeWithText("문을 닫으면 시트를 되돌려요").assertIsDisplayed()
        compose.onNodeWithText("지금 되돌리기").performClick()

        assertEquals(listOf("confirmDoorsClosed"), calls)
        compose.onNodeWithText("시트와 공조를 되돌리고 있어요").assertIsDisplayed()
        compose.onNodeWithText("지금 되돌리기").assertDoesNotExist()
    }

    @Test
    fun aborted_done_confirm() {
        show(
            charging(
                NapSessionState.Aborted(
                    AbortReason.GEAR_LEFT_PARK,
                    restoring = false,
                    restore = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()),
                ),
            ),
        )

        compose.onNodeWithText("기어가 바뀌어 휴식을 멈췄어요").assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()

        assertEquals(listOf("acknowledgeAbort"), calls)
    }

    @Test
    fun navigation_homeToSettingsAndReportAndBack() {
        show(charging(NapSessionState.Idle))

        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("수면 프리셋").assertIsDisplayed()
        compose.onNodeWithText("뒤로").performClick()
        compose.onNodeWithText("리포트").performClick()
        compose.onNodeWithText("아직 휴식 기록이 없어요").assertIsDisplayed()
        compose.onNodeWithText("뒤로").performClick()
        compose.onNodeWithText("20분 파워냅 시작").assertIsDisplayed()
    }

    @Test
    fun sessionActive_hidesNavigationAndIgnoresRoute() {
        show(
            charging(
                NapSessionState.SafetyCheck(20, SessionMode.NAP, allPass, rearSeatQuestionPending = true, doorConfirmPending = false),
            ),
            initialRoute = NapRoute.SETTINGS,
        )

        compose.onNodeWithText("안전 확인").assertIsDisplayed()
        compose.onNodeWithText("설정").assertDoesNotExist()
        compose.onNodeWithText("리포트").assertDoesNotExist()
    }

    @Test
    fun settings_steppersAndToggles() {
        show(charging(NapSessionState.Idle), initialRoute = NapRoute.SETTINGS)

        compose.onNodeWithContentDescription("등받이 눕힘 늘리기").performClick()
        compose.onNodeWithContentDescription("온도 줄이기").performClick()
        compose.onNodeWithContentDescription("풍량 늘리기").performClick()
        compose.onNodeWithContentDescription("목표 잔량 줄이기").performClick()
        compose.onNodeWithText("시트 자동 조절").performClick()
        compose.onNodeWithText("음성 안내").performClick()
        compose.onNodeWithText("데모 모드").performClick()

        assertEquals(
            listOf(
                "setReclineLevel(70)",
                "setTemperatureC(22.5)",
                "setFanLevel(3)",
                "setTargetPercent(75)",
                "setSeatControlEnabled(false)",
                "setVoiceEnabled(false)",
                "setDemoMode(true)",
            ),
            calls,
        )
    }

    @Test
    fun settings_unsupportedVehicle_showsMessageInsteadOfControls() {
        show(
            charging(NapSessionState.Idle).copy(capabilities = capabilities.copy(seatRecline = false, climate = false)),
            initialRoute = NapRoute.SETTINGS,
        )

        compose.onNodeWithContentDescription("등받이 눕힘 늘리기").assertDoesNotExist()
        compose.onNodeWithContentDescription("온도 줄이기").assertDoesNotExist()
        assertEquals(4, compose.onAllNodes(hasText("이 차량에서는 지원되지 않아요")).fetchSemanticsNodes().size)
    }

    @Test
    fun demoPanel_expandsAndSendsControls() {
        show(charging(NapSessionState.Idle).copy(anyDoorOpen = false, gearInPark = true))

        compose.onNodeWithText("DEMO 조작").performClick()
        compose.onNodeWithText("충전 시작").performClick()
        compose.onNodeWithText("충전 중단").performClick()
        compose.onNodeWithText("충전 완료").performClick()
        compose.onNodeWithText("도어 열기").performClick()
        compose.onNodeWithText("기어 D로").performClick()
        compose.onNodeWithText("접기").performClick()
        compose.onNodeWithText("충전 시작").assertDoesNotExist()

        assertEquals(
            listOf(
                "demoStartCharging",
                "demoInterruptCharging",
                "demoCompleteCharging",
                "demoSetDoorOpen(true)",
                "demoSetGearInPark(false)",
            ),
            calls,
        )
    }

    @Test
    fun demoPanel_hiddenWithoutDemoControls() {
        show(charging(NapSessionState.Idle).copy(demoControlsAvailable = false, capabilities = capabilities.copy(isDemo = false)))

        compose.onNodeWithText("DEMO 조작").assertDoesNotExist()
        compose.onNodeWithText("DEMO").assertDoesNotExist()
    }

    @Test
    fun notice_tapDismisses() {
        show(charging(NapSessionState.Idle).copy(notice = UiNotice.ExtendGranted(5)))

        compose.onNodeWithText("5분 더 쉬세요").performClick()

        assertEquals(listOf("dismissNotice"), calls)
    }
}
