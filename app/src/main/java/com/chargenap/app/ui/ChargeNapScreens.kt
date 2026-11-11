package com.chargenap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargenap.app.session.CheckResult
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.Recommendation
import com.chargenap.app.session.SafetyReport
import com.chargenap.app.session.SessionMode
import com.chargenap.app.ui.aborted.AbortedHost
import com.chargenap.app.ui.components.DemoBadge
import com.chargenap.app.ui.components.DemoControlPanel
import com.chargenap.app.ui.components.NoticeBar
import com.chargenap.app.ui.home.HomeScreen
import com.chargenap.app.ui.report.ReportScreen
import com.chargenap.app.ui.safety.SafetyScreen
import com.chargenap.app.ui.safety.SafetyUiModel
import com.chargenap.app.ui.settings.SettingsScreen
import com.chargenap.app.ui.sleep.SleepHost
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.ui.wake.BriefingScreen
import com.chargenap.app.ui.wake.WakingScreen
import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.VehicleCapabilities
import java.time.ZoneId

/** 하단 안내가 떠 있는 동안 화면 내용이 비켜 주는 높이. */
private val NOTICE_LANE_HEIGHT = 64.dp

/** 세션이 없을 때(`Idle`/`Offer`) 볼 수 있는 화면. 세션이 진행 중이면 엔진 상태가 화면을 정한다. */
enum class NapRoute { HOME, SETTINGS, REPORT }

/**
 * 앱 화면 전체의 진입점. `MainActivity`의 `setContent` 안에서 이것 하나만 부르면 된다.
 *
 * 엔진 상태 → 화면 매핑, 홈↔설정↔리포트 이동, DEMO 배지, DEMO 조작 패널, 하단 안내를 모두 맡는다.
 * 테마와 설계 캔버스(가로 1280dp 기준 밀도)도 여기서 씌운다.
 *
 * @param onSleepDimChanged 수면 화면이 보이면 true, 그 밖에는 false로 불린다. 화면 밝기를 낮추는 데 쓴다
 */
@Composable
fun ChargeNapScreens(viewModel: NapViewModel, onSleepDimChanged: (Boolean) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(NapRoute.HOME) }
    var demoPanelExpanded by rememberSaveable { mutableStateOf(false) }

    // 세션이 시작되면(화면 버튼이든 음성이든) 설정·리포트에서 나온다. 세션이 끝나면 홈으로 돌아온다.
    LaunchedEffect(state.sessionActive) {
        if (state.sessionActive) route = NapRoute.HOME
    }
    BackHandler(enabled = route != NapRoute.HOME && !state.sessionActive) { route = NapRoute.HOME }

    val sleeping = state.session is NapSessionState.Napping
    val currentOnSleepDimChanged by rememberUpdatedState(onSleepDimChanged)
    LaunchedEffect(sleeping) { currentOnSleepDimChanged(sleeping) }
    DisposableEffect(Unit) {
        onDispose { currentOnSleepDimChanged(false) }
    }

    ChargeNapContent(
        state = state,
        actions = viewModel,
        route = route,
        onNavigate = { route = it },
        demoPanelExpanded = demoPanelExpanded,
        onToggleDemoPanel = { demoPanelExpanded = !demoPanelExpanded },
    )
}

/**
 * 상태 값만 받아 화면 전체를 그린다. 미리보기와 스냅샷 테스트가 ViewModel 없이 쓴다.
 *
 * @param route 세션이 진행 중이면 무시된다
 * @param sleepControlsInitiallyVisible 수면 화면의 [5분 더]/[지금 일어나기]가 보이는 상태로 시작한다(스냅샷용)
 * @param zone 리포트의 기록 시각을 표시할 시간대
 */
@Composable
fun ChargeNapContent(
    state: NapUiState,
    actions: NapActions,
    route: NapRoute,
    onNavigate: (NapRoute) -> Unit,
    demoPanelExpanded: Boolean,
    onToggleDemoPanel: () -> Unit,
    modifier: Modifier = Modifier,
    sleepControlsInitiallyVisible: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val session = state.session
    val sleeping = session is NapSessionState.Napping
    val onHome = !state.sessionActive && route == NapRoute.HOME
    val onSafety = session is NapSessionState.SafetyCheck || session is NapSessionState.Preparing
    // DEMO 조작 패널은 시연에서 상황을 바꿔 볼 화면(홈, 안전 확인, 수면)에만 둔다.
    val demoPanelVisible = state.demoControlsAvailable && (onHome || onSafety || sleeping)
    val showBadge = state.capabilities.isDemo

    ChargeNapTheme {
        DesignCanvas(
            modifier = modifier,
            background = if (sleeping) NapColors.SleepBackground else NapColors.Background,
        ) {
            // 하단 안내가 떠 있는 동안에는 화면 내용을 그만큼 줄여 안내가 버튼이나 카드를 가리지 않게 한다.
            // 수면 화면은 가운데 숫자가 움직이지 않도록 그대로 두고 안내를 버튼 위에 띄운다.
            val noticeInset by animateDpAsState(
                targetValue = if (state.notice != null && !sleeping) NOTICE_LANE_HEIGHT else 0.dp,
                label = "noticeInset",
            )
            Box(Modifier.fillMaxSize().padding(bottom = noticeInset)) {
                when (session) {
                    NapSessionState.Idle, is NapSessionState.Offer -> when (route) {
                        NapRoute.HOME -> HomeScreen(
                            state = state,
                            onStart = actions::requestStart,
                            onDecline = actions::declineOffer,
                            onOpenReport = { onNavigate(NapRoute.REPORT) },
                            onOpenSettings = { onNavigate(NapRoute.SETTINGS) },
                        )
                        NapRoute.SETTINGS -> SettingsScreen(
                            settings = state.settings,
                            capabilities = state.capabilities,
                            actions = actions,
                            onBack = { onNavigate(NapRoute.HOME) },
                        )
                        NapRoute.REPORT -> ReportScreen(
                            report = state.weeklyReport,
                            recentRecords = state.recentRecords,
                            todayEpochDay = state.todayEpochDay,
                            onBack = { onNavigate(NapRoute.HOME) },
                            zone = zone,
                        )
                    }
                    is NapSessionState.SafetyCheck -> SafetyScreen(
                        model = SafetyUiModel(
                            minutes = session.minutes,
                            mode = session.mode,
                            report = session.report,
                            rearSeatQuestionPending = session.rearSeatQuestionPending,
                            doorConfirmPending = session.doorConfirmPending,
                        ),
                        onAnswerRearSeat = actions::answerRearSeat,
                        onConfirmDoorsClosed = actions::confirmDoorsClosed,
                        onCancel = actions::cancel,
                    )
                    is NapSessionState.Preparing -> SafetyScreen(
                        model = SafetyUiModel(
                            minutes = session.minutes,
                            mode = session.mode,
                            report = state.startBlockers,
                            preparing = true,
                            seatMoving = session.seatConsent,
                        ),
                        onAnswerRearSeat = actions::answerRearSeat,
                        onConfirmDoorsClosed = actions::confirmDoorsClosed,
                        onCancel = actions::cancel,
                    )
                    is NapSessionState.Napping -> SleepHost(
                        remainingSeconds = state.remainingSeconds ?: 0,
                        timerOnly = session.timerOnly,
                        mode = session.mode,
                        onWake = actions::requestWake,
                        onExtend = actions::requestExtend,
                        controlsInitiallyVisible = sleepControlsInitiallyVisible,
                    )
                    is NapSessionState.Waking -> WakingScreen(session.reason)
                    is NapSessionState.Briefing -> BriefingScreen(session.summary, onConfirm = actions::acknowledgeBriefing)
                    is NapSessionState.Aborted -> AbortedHost(
                        state = session,
                        anyDoorOpen = state.anyDoorOpen,
                        onRestoreNow = actions::confirmDoorsClosed,
                        onConfirm = actions::acknowledgeAbort,
                    )
                }
            }

            // 머리줄 가운데: DEMO 배지와 DEMO 조작 패널. 화면들은 이 자리를 비워 둔다.
            if (demoPanelVisible && demoPanelExpanded) {
                DemoControlPanel(
                    expanded = true,
                    onToggle = onToggleDemoPanel,
                    doorOpen = state.anyDoorOpen == true,
                    gearInPark = state.gearInPark != false,
                    onStartCharging = actions::demoStartCharging,
                    onInterruptCharging = actions::demoInterruptCharging,
                    onCompleteCharging = actions::demoCompleteCharging,
                    onSetDoorOpen = actions::demoSetDoorOpen,
                    onSetGearInPark = actions::demoSetGearInPark,
                    modifier = Modifier.align(Alignment.TopCenter).padding(start = 24.dp, end = 24.dp, top = 8.dp),
                    dim = sleeping,
                    leading = {
                        // 수면 화면에서는 패널 전체가 흐려지므로 배지를 따로 어둡게 하지 않는다(겹쳐 어두워지면 읽을 수 없다).
                        if (showBadge) DemoBadge(Modifier.padding(horizontal = 6.dp))
                    },
                )
            } else if (showBadge || demoPanelVisible) {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).height(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBadge) DemoBadge(dim = sleeping)
                    if (demoPanelVisible) {
                        DemoControlPanel(
                            expanded = false,
                            onToggle = onToggleDemoPanel,
                            doorOpen = false,
                            gearInPark = true,
                            onStartCharging = {},
                            onInterruptCharging = {},
                            onCompleteCharging = {},
                            onSetDoorOpen = {},
                            onSetGearInPark = {},
                            dim = sleeping,
                        )
                    }
                }
            }

            state.notice?.let { notice ->
                NoticeBar(
                    text = noticeText(notice),
                    onDismiss = actions::dismissNotice,
                    // 수면 화면에서는 [5분 더]/[지금 일어나기] 버튼 위에 띄운다.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (sleeping) 156.dp else 20.dp),
                    dim = sleeping,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 미리보기

internal val previewDemoCapabilities = VehicleCapabilities(
    batteryPercent = true,
    chargingState = true,
    remainingChargeTime = false,
    seatRecline = true,
    climate = true,
    doorState = true,
    isDemo = true,
)

private val previewAllPass = SafetyReport(CheckResult.PASS, CheckResult.PASS, CheckResult.PASS, CheckResult.PASS)

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun RootHomeDemoPreview() {
    ChargeNapContent(
        state = NapUiState(
            session = NapSessionState.Offer(Recommendation.Nap(20, 27)),
            chargingState = ChargingState.CHARGING,
            batteryPercent = 53,
            remainingChargeMinutes = 27,
            startBlockers = previewAllPass,
            gearInPark = true,
            anyDoorOpen = false,
            capabilities = previewDemoCapabilities,
            demoControlsAvailable = true,
        ),
        actions = NoopNapActions,
        route = NapRoute.HOME,
        onNavigate = {},
        demoPanelExpanded = false,
        onToggleDemoPanel = {},
    )
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun RootSleepDemoPanelPreview() {
    ChargeNapContent(
        state = NapUiState(
            session = NapSessionState.Napping(
                startedAtMillis = 0,
                wakeAtMillis = 20 * 60_000L,
                plannedMinutes = 20,
                mode = SessionMode.NAP,
                timerOnly = false,
                cabin = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList()),
            ),
            chargingState = ChargingState.CHARGING,
            batteryPercent = 58,
            startBlockers = previewAllPass,
            gearInPark = true,
            anyDoorOpen = false,
            capabilities = previewDemoCapabilities,
            remainingSeconds = 14 * 60 + 8,
            demoControlsAvailable = true,
        ),
        actions = NoopNapActions,
        route = NapRoute.HOME,
        onNavigate = {},
        demoPanelExpanded = true,
        onToggleDemoPanel = {},
    )
}
