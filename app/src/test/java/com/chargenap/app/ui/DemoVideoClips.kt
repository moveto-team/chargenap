package com.chargenap.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * 시연 영상용 앱 클립. 실제 화면 코드(ChargeNapContent)에 세션 상태를 시간 순서대로 넣으며 30fps로 연속 프레임을 찍는다.
 * 세션 엔진 대신 상태를 직접 넣는 이유: 영상 장면 길이에 맞춰 사건 시각을 고정해야 하기 때문(엔진 흐름 자체는 단위 테스트가 검증).
 *
 *   ./gradlew testDebugUnitTest --tests "com.chargenap.app.ui.DemoVideoClips" -Pchargenap.video=true
 *
 * 결과: app/build/demo-clips/<클립>/f0001.png… (1920×1080) + clip.json
 *   clip.json = {fps, frames, w, h, events: [{t(초), type, x?, y?(0..1, 터치 위치)}]}
 * 영상(chargenap-film)은 events로 내레이션·터치 효과·자막을 앱 동작에 맞춘다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-hdpi", application = Application::class)
class DemoVideoClips {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)
    private val todayNoon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val outDir = File("build/demo-clips")

    private val allPass = SafetyReport(CheckResult.PASS, CheckResult.SKIPPED, CheckResult.PASS, CheckResult.PASS)
    private val notCharging = SafetyReport(CheckResult.PASS, CheckResult.SKIPPED, CheckResult.FAIL, CheckResult.PASS)
    private val vehicle = VehicleCapabilities(
        batteryPercent = true,
        chargingState = true,
        remainingChargeTime = false,
        seatRecline = true,
        climate = true,
        doorState = true,
        isDemo = true,
    )
    private val cabinOk = CabinApplyResult(seatApplied = true, climateApplied = true, errors = emptyList())

    private var state by mutableStateOf(NapUiState())
    private var route by mutableStateOf(NapRoute.HOME)

    @Before
    fun setUp() {
        assumeTrue("영상 클립은 -Pchargenap.video=true 일 때만 찍는다", System.getProperty("chargenap.video") == "true")
        compose.mainClock.autoAdvance = false
    }

    private fun base(session: NapSessionState, percent: Int = 53, charging: Boolean = true) = NapUiState(
        session = session,
        chargingState = if (charging) ChargingState.CHARGING else ChargingState.CONNECTED_IDLE,
        batteryPercent = percent,
        remainingChargeMinutes = if (charging) 25 else null,
        startBlockers = if (charging) allPass else notCharging,
        gearInPark = true,
        anyDoorOpen = false,
        capabilities = vehicle,
        todayEpochDay = today.toEpochDay(),
        demoControlsAvailable = true,
    )

    private val offer = NapSessionState.Offer(Recommendation.Nap(20, 25))
    private val rearSeat = NapSessionState.SafetyCheck(20, SessionMode.NAP, allPass, rearSeatQuestionPending = true, doorConfirmPending = false)
    private val preparing = NapSessionState.Preparing(20, SessionMode.NAP, seatConsent = true)
    private val napping = NapSessionState.Napping(
        startedAtMillis = todayNoon,
        wakeAtMillis = todayNoon + 20 * 60_000L,
        plannedMinutes = 20,
        mode = SessionMode.NAP,
        timerOnly = false,
        cabin = cabinOk,
    )
    private val summary = SessionSummary(
        startedAtMillis = todayNoon,
        endedAtMillis = todayNoon + 20 * 60_000L,
        mode = SessionMode.NAP,
        plannedMinutes = 20,
        sleptMinutes = 20,
        batteryStartPercent = 55,
        batteryEndPercent = 76,
        rangeKm = 342,
        wakeReason = WakeReason.CHARGE_NEARLY_DONE,
        seatMoved = true,
        failures = emptyList(),
    )

    // ------------------------------------------------------------------ 녹화 도구

    private class Step(val atMs: Long, val event: String?, val tapText: String?, val action: () -> Unit)

    private inner class Script {
        val steps = mutableListOf<Step>()
        /** [atMs]에 [action]을 실행한다. [event]가 있으면 그 시각을 clip.json에 남긴다. */
        fun at(atMs: Long, event: String? = null, action: () -> Unit = {}) { steps += Step(atMs, event, null, action) }
        /** [atMs]에 [text] 버튼을 누른 것으로 기록하고(터치 위치), [reactMs] 뒤에 [action]을 실행한다. */
        fun tap(atMs: Long, text: String, reactMs: Long = 220, action: () -> Unit = {}) {
            steps += Step(atMs, "tap", text) {}
            steps += Step(atMs + reactMs, null, null, action)
        }
        /** [fromMs]부터 [toMs]까지 [everyMs]마다 [action]을 실행한다(카운트다운 등). */
        fun every(fromMs: Long, toMs: Long, everyMs: Long, action: () -> Unit) {
            var t = fromMs
            while (t <= toMs) { steps += Step(t, null, null, action); t += everyMs }
        }
    }

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

    /** 화면에서 [text]가 쓰인 노드의 중심(0..1). 버튼 글자를 누른 위치로 쓴다. */
    private fun centerOf(text: String): Pair<Float, Float> {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val nodes = compose.onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "'$text' 노드를 찾지 못했다" }
        val b = nodes.last().boundsInRoot
        return ((b.left + b.right) / 2f / root.width) to ((b.top + b.bottom) / 2f / root.height)
    }

    private fun record(name: String, durationMs: Long, script: Script.() -> Unit) {
        val s = Script().apply(script)
        val steps = s.steps.sortedBy { it.atMs }.toMutableList()
        val dir = File(outDir, name).apply {
            deleteRecursively()
            mkdirs()
        }
        val events = mutableListOf<String>()
        val frames = (durationMs * FPS / 1000).toInt()
        var clock = 0L
        for (i in 0 until frames) {
            val tMs = i * 1000L / FPS
            // 이 프레임 시각까지의 단계를 실행한다(시계도 그 시각으로 맞춘다)
            while (steps.isNotEmpty() && steps.first().atMs <= tMs) {
                val st = steps.removeAt(0)
                if (st.atMs > clock) { advance(st.atMs - clock); clock = st.atMs }
                if (st.tapText != null) {
                    val (x, y) = centerOf(st.tapText)
                    events += """{"t":%.3f,"type":"tap","x":%.4f,"y":%.4f}""".format(st.atMs / 1000.0, x, y)
                } else if (st.event != null) {
                    events += """{"t":%.3f,"type":"%s"}""".format(st.atMs / 1000.0, st.event)
                }
                st.action()
                Snapshot.sendApplyNotifications()
            }
            if (tMs > clock) { advance(tMs - clock); clock = tMs }
            compose.onRoot().captureRoboImage(File(dir, "f%04d.png".format(i + 1)).path)
        }
        val size = compose.onRoot().fetchSemanticsNode().size
        File(dir, "clip.json").writeText(
            """{"fps":$FPS,"frames":$frames,"w":${size.width},"h":${size.height},"events":[${events.joinToString(",")}]}""",
        )
    }

    // ------------------------------------------------------------------ 클립

    /**
     * 01 감지·제안: 충전기 연결 전 → 충전 시작(엔진은 남은 시간을 모를 때 "얼마나 쉴까요?"를 먼저 제안) →
     * 잔량 변화로 남은 시간 계산 → "20분 파워냅 어때요?"로 바뀜 → [시작]. 엔진의 evaluateIdle/evaluateOffer 흐름과 같다.
     */
    @Test
    fun home() {
        show(base(NapSessionState.Idle, percent = 53, charging = false))
        record("home", 12_000) {
            at(1_200, "charging") {
                state = base(NapSessionState.Offer(Recommendation.ChooseDuration(listOf(15, 20))), 53).copy(remainingChargeMinutes = null)
            }
            at(3_000) { state = state.copy(batteryPercent = 54) }
            at(4_800, "offer") { state = state.copy(batteryPercent = 55, remainingChargeMinutes = 25, session = offer) }
            tap(9_400, "시작") { state = state.copy(session = rearSeat) }
        }
    }

    /** 02 안전 확인: 제안 → 안전 확인 목록 → "뒷좌석에 사람이나 짐이 있나요?" → [없어요] */
    @Test
    fun safety() {
        show(base(offer, 55))
        record("safety", 12_000) {
            at(300, "checks") { state = state.copy(session = rearSeat) }
            tap(6_200, "없어요") { state = state.copy(session = preparing) }
        }
    }

    /** 03 캐빈 세팅: 준비 중(등받이 눕힘·바람) → 수면 화면으로 어두워짐 */
    @Test
    fun prepare() {
        show(base(rearSeat, 55))
        record("prepare", 12_000) {
            at(300, "preparing") { state = state.copy(session = preparing) }
            at(8_800, "napping") { state = state.copy(session = napping, remainingSeconds = 20 * 60, breathingGuide = true) }
        }
    }

    /** 04 수면·감시: 호흡 안내 → 카운트다운(빨리 감기) */
    @Test
    fun sleep() {
        show(base(preparing, 55))
        var remaining = 20 * 60
        record("sleep", 12_000) {
            at(200, "napping") { state = state.copy(session = napping, remainingSeconds = remaining, breathingGuide = true) }
            every(1_200, 4_200, 1_000) { remaining -= 1; state = state.copy(remainingSeconds = remaining) }
            at(5_000, "ff") { state = state.copy(breathingGuide = false) }
            // 빨리 감기: 0.6초마다 1분 30초씩. 숫자 구르기(600ms)가 끝난 뒤 다음 숫자가 와서 겹치지 않는다
            every(5_400, 11_400, 600) {
                remaining = (remaining - 90).coerceAtLeast(2 * 60 + 30)
                state = state.copy(remainingSeconds = remaining, batteryPercent = 55 + (20 * 60 - remaining) * 21 / (20 * 60))
            }
        }
    }

    /** 05 스마트 기상: 마지막 몇 초 → 일어날 시간(밝아짐) → 브리핑(잔량·거리 카운트) */
    @Test
    fun wake() {
        show(base(napping, 75).copy(remainingSeconds = 4, breathingGuide = false))
        var remaining = 4
        record("wake", 12_000) {
            every(600, 3_600, 1_000) { remaining -= 1; state = state.copy(remainingSeconds = remaining) }
            at(3_700, "waking") { state = state.copy(session = NapSessionState.Waking(WakeReason.CHARGE_NEARLY_DONE), batteryPercent = 76) }
            at(7_000, "briefing") { state = state.copy(session = NapSessionState.Briefing(summary)) }
        }
    }

    /** 안전 가드: 자는 중 문이 열림 → 즉시 중단 안내 → 문 닫힘 → 복구 중 → 복구 완료 */
    @Test
    fun abort() {
        show(base(napping, 62).copy(remainingSeconds = 12 * 60 + 5, breathingGuide = false))
        var remaining = 12 * 60 + 5
        record("abort", 11_000) {
            every(400, 1_400, 1_000) { remaining -= 1; state = state.copy(remainingSeconds = remaining) }
            at(1_800, "door_open") {
                state = state.copy(session = NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null), anyDoorOpen = true)
            }
            at(5_000, "door_closed") { state = state.copy(anyDoorOpen = false) }
            at(6_400, "restored") {
                state = state.copy(session = NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = false, restore = cabinOk))
            }
        }
    }

    /** 음성 조작: 자는 중 "5분 더" → 연장 안내 → "깨워 줘" → 일어나는 중 */
    @Test
    fun voice() {
        show(base(napping, 60).copy(remainingSeconds = 14 * 60 + 12, breathingGuide = false))
        var remaining = 14 * 60 + 12
        record("voice", 12_000) {
            every(500, 11_500, 1_000) { if (state.session is NapSessionState.Napping) { remaining -= 1; state = state.copy(remainingSeconds = remaining) } }
            at(3_000, "extend") { remaining += 5 * 60; state = state.copy(notice = UiNotice.ExtendGranted(5), remainingSeconds = remaining) }
            at(6_000) { state = state.copy(notice = null) }
            at(7_000, "wake_cmd") { state = state.copy(session = NapSessionState.Waking(WakeReason.USER)) }
        }
    }

    /** 휴식 리포트: 홈 → 리포트(막대가 자라고 숫자가 오름) */
    @Test
    fun report() {
        val records = sampleRecords(today.toEpochDay(), zone)
        show(
            base(NapSessionState.Idle, 80, charging = false).copy(
                weeklyReport = weeklyReport(records, todayNoon + 3 * 60 * 60_000L, zone),
                recentRecords = records,
            ),
        )
        record("report", 9_000) {
            tap(500, "리포트") { route = NapRoute.REPORT }
        }
    }

    private companion object {
        const val FPS = 30
    }
}
