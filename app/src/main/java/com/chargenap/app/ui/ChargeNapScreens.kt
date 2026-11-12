package com.chargenap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.remember
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
import com.chargenap.app.ui.theme.NapMotion
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
 * 화면 전환 단위. 값이 바뀔 때만 화면 전체가 전환되고, 같은 값 안에서 바뀌는 것(안전 확인 → 준비 중,
 * 남은 시간 갱신)은 그 화면 안에서 처리한다.
 */
enum class NapScreen { HOME, SETTINGS, REPORT, SAFETY, SLEEP, WAKING, BRIEFING, ABORTED }

/** 엔진 상태와 이동 경로로 지금 보일 화면을 정한다. */
fun napScreen(session: NapSessionState, route: NapRoute): NapScreen = when (session) {
    NapSessionState.Idle, is NapSessionState.Offer -> when (route) {
        NapRoute.HOME -> NapScreen.HOME
        NapRoute.SETTINGS -> NapScreen.SETTINGS
        NapRoute.REPORT -> NapScreen.REPORT
    }
    is NapSessionState.SafetyCheck, is NapSessionState.Preparing -> NapScreen.SAFETY
    is NapSessionState.Napping -> NapScreen.SLEEP
    is NapSessionState.Waking -> NapScreen.WAKING
    is NapSessionState.Briefing -> NapScreen.BRIEFING
    is NapSessionState.Aborted -> NapScreen.ABORTED
}

/**
 * 화면 전환 길이(ms). 영상·테스트에서 프레임을 찍을 때 이 시간이 지나면 새 화면만 남는다.
 *
 * - 앞으로 가기(홈 → 안전 확인 → … → 브리핑 → 홈, 홈 → 설정·리포트): 오른쪽에서 살짝 밀려 들어오며 페이드
 * - 뒤로 가기(설정·리포트 → 홈): 왼쪽에서
 * - 수면 진입: 천천히 어두워지는 크로스페이드
 * - 기상: 밝아지는 크로스페이드
 * - 중단: 빠르고 단호하게
 */
object ScreenTransitionMillis {
    const val FORWARD = 520
    const val SLEEP = 950
    const val WAKE = 900
    const val ABORT = NapMotion.ABORT
}

/** 전환에 쓰는 한 화면 분량의 값. 나가는 화면은 마지막으로 보였던 값 그대로 그린다. */
private data class ScreenFrame(val screen: NapScreen, val state: NapUiState, val route: NapRoute)

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
 * 화면이 바뀌면 [ScreenTransitionMillis]의 길이로 전환된다. DEMO 배지와 조작 패널은 전환 밖에 있어 제자리에 있다.
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

    val screen = napScreen(session, route)

    ChargeNapTheme {
        // 수면에 들어가면 바탕이 천천히 검게, 깨어나면 다시 밝게 바뀐다. 중단은 바로 바뀐다.
        val background by animateColorAsState(
            targetValue = if (sleeping) NapColors.SleepBackground else NapColors.Background,
            animationSpec = tween(
                when {
                    sleeping -> ScreenTransitionMillis.SLEEP
                    screen == NapScreen.ABORTED -> ScreenTransitionMillis.ABORT
                    else -> ScreenTransitionMillis.WAKE
                },
            ),
            label = "canvasBackground",
        )
        DesignCanvas(modifier = modifier, background = background) {
            // 하단 안내가 떠 있는 동안에는 화면 내용을 그만큼 줄여 안내가 버튼이나 카드를 가리지 않게 한다.
            // 수면 화면은 가운데 숫자가 움직이지 않도록 그대로 두고 안내를 버튼 위에 띄운다.
            val noticeInset by animateDpAsState(
                targetValue = if (state.notice != null && !sleeping) NOTICE_LANE_HEIGHT else 0.dp,
                label = "noticeInset",
            )
            AnimatedContent(
                targetState = ScreenFrame(screen, state, route),
                modifier = Modifier.fillMaxSize().padding(bottom = noticeInset),
                contentKey = { it.screen },
                transitionSpec = { screenTransition() },
                label = "screen",
            ) { frame ->
                Screen(
                    state = frame.state,
                    route = frame.route,
                    actions = actions,
                    onNavigate = onNavigate,
                    sleepControlsInitiallyVisible = sleepControlsInitiallyVisible,
                    zone = zone,
                )
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

            // 안내는 아래에서 올라오고, 사라질 때 내려간다. 내려가는 동안에는 마지막 안내를 그대로 보여 준다.
            val shownNotice = rememberLastNonNull(state.notice)
            AnimatedVisibility(
                visible = state.notice != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 수면 화면에서는 [5분 더]/[지금 일어나기] 버튼 위에 띄운다.
                    .padding(bottom = if (sleeping) 156.dp else 20.dp),
                enter = slideInVertically(tween(NapMotion.MEDIUM, easing = NapMotion.Enter)) { it * 2 } +
                    fadeIn(tween(NapMotion.SHORT)),
                exit = slideOutVertically(tween(NapMotion.SHORT, easing = NapMotion.Exit)) { it * 2 } +
                    fadeOut(tween(NapMotion.SHORT)),
                label = "notice",
            ) {
                shownNotice?.let { notice ->
                    NoticeBar(
                        text = noticeText(notice),
                        onDismiss = actions::dismissNotice,
                        dim = sleeping,
                    )
                }
            }
        }
    }
}

/** 한 화면. 전환 중에는 나가는 화면과 들어오는 화면이 각자의 값으로 동시에 그려진다. */
@Composable
private fun Screen(
    state: NapUiState,
    route: NapRoute,
    actions: NapActions,
    onNavigate: (NapRoute) -> Unit,
    sleepControlsInitiallyVisible: Boolean,
    zone: ZoneId,
) {
    when (val session = state.session) {
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
        // 안전 확인과 준비 중은 같은 자리에서 한 번만 부른다. 따로 부르면 준비 중으로 바뀔 때 화면이 새로 만들어져
        // 점검 줄이 처음부터 다시 나타난다. 같은 화면 안에서 오른쪽 카드만 바뀌어야 한다.
        is NapSessionState.SafetyCheck, is NapSessionState.Preparing -> SafetyScreen(
            model = safetyModel(session, state.startBlockers),
            onAnswerRearSeat = actions::answerRearSeat,
            onConfirmDoorsClosed = actions::confirmDoorsClosed,
            onCancel = actions::cancel,
        )
        is NapSessionState.Napping -> SleepHost(
            remainingSeconds = state.remainingSeconds ?: 0,
            timerOnly = session.timerOnly,
            mode = session.mode,
            breathingGuide = state.breathingGuide,
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

/** 안전 확인·준비 중 화면의 값. 준비 중에는 엔진의 현재 점검 결과([startBlockers])를 보여 준다. */
private fun safetyModel(session: NapSessionState, startBlockers: SafetyReport): SafetyUiModel = when (session) {
    is NapSessionState.Preparing -> SafetyUiModel(
        minutes = session.minutes,
        mode = session.mode,
        report = startBlockers,
        preparing = true,
        seatMoving = session.seatConsent,
    )
    is NapSessionState.SafetyCheck -> SafetyUiModel(
        minutes = session.minutes,
        mode = session.mode,
        report = session.report,
        rearSeatQuestionPending = session.rearSeatQuestionPending,
        doorConfirmPending = session.doorConfirmPending,
    )
    else -> error("안전 확인 화면이 아닌 상태: $session")
}

/** 화면 쌍에 맞는 전환. 길이는 [ScreenTransitionMillis]. */
private fun AnimatedContentTransitionScope<ScreenFrame>.screenTransition(): ContentTransform {
    val from = initialState.screen
    val to = targetState.screen
    val transform = when {
        // 중단: 짧고 단호하게. 미끄러지지 않고 제자리에서 바로 바뀐다.
        to == NapScreen.ABORTED -> (
            fadeIn(tween(ScreenTransitionMillis.ABORT, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(ScreenTransitionMillis.ABORT, easing = NapMotion.Enter), initialScale = 0.98f)
            ) togetherWith fadeOut(tween(ScreenTransitionMillis.ABORT / 2))
        // 수면 진입: 지금 화면이 먼저 어둠 속으로 사라지고(0~450ms), 검은 수면 화면이 천천히 떠오른다(400~950ms).
        to == NapScreen.SLEEP -> fadeIn(
            tween(ScreenTransitionMillis.SLEEP - 400, delayMillis = 400, easing = LinearOutSlowInEasing),
        ) togetherWith fadeOut(tween(450, easing = FastOutLinearInEasing))
        // 기상: 수면 화면은 빨리 걷히고 새 화면이 천천히 밝아진다.
        from == NapScreen.SLEEP -> fadeIn(
            tween(ScreenTransitionMillis.WAKE, easing = LinearOutSlowInEasing),
        ) togetherWith fadeOut(tween(350, easing = FastOutLinearInEasing))
        // 뒤로(설정·리포트 → 홈): 왼쪽에서
        to == NapScreen.HOME && (from == NapScreen.SETTINGS || from == NapScreen.REPORT) -> slide(forward = false)
        // 앞으로: 오른쪽에서
        else -> slide(forward = true)
    }
    return transform using SizeTransform(clip = false)
}

/** 오른쪽(앞으로) 또는 왼쪽(뒤로)에서 화면 폭의 1/10만큼 밀려 들어오며 페이드. */
private fun slide(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    val duration = ScreenTransitionMillis.FORWARD
    return (
        slideInHorizontally(tween(duration, easing = NapMotion.Enter)) { sign * it / 10 } +
            fadeIn(tween(duration - 160, delayMillis = 160))
        ) togetherWith (
        slideOutHorizontally(tween(duration, easing = NapMotion.Enter)) { -sign * it / 24 } +
            fadeOut(tween(220, easing = FastOutLinearInEasing))
        )
}

/** null이 아닌 마지막 값. 사라지는 애니메이션 동안 내용을 유지하는 데 쓴다(그리기용이라 상태로 두지 않는다). */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
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
