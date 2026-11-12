package com.chargenap.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargenap.app.data.InMemoryPreferencesStore
import com.chargenap.app.data.SessionHistoryRepository
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.SettingsRepository
import com.chargenap.app.session.Clock
import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.SessionConfig
import com.chargenap.app.ui.theme.ChargeNapTheme
import com.chargenap.app.ui.theme.DesignCanvas
import com.chargenap.app.ui.theme.NapColors
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * 시연 영상용 프레임을 만든다. 실제 화면 코드(ChargeNapScreens)를 실제 ViewModel·세션 엔진과
 * 가상 차량(데모 모드)으로 돌리면서, 사용자가 누르는 순서대로 화면을 캡처한다.
 *
 * 평소 테스트에서는 건너뛴다. 실행:
 *   ./gradlew testDebugUnitTest --tests "com.chargenap.app.ui.DemoVideoFrames" -Pchargenap.video=true
 * 결과: app/build/demo-video/ (프레임 PNG, ffmpeg concat 목록 frames.txt, 장면 시작 시각 scenes.txt)
 * 영상 합성: scripts/make-demo-video.sh
 *
 * 시계가 둘이다.
 * - 엔진·가상 차량 시계([scheduler]): 충전, 수면 타이머, 기상. [advance]로 흘린다.
 * - 화면 시계(`compose.mainClock`): 애니메이션. `autoAdvance = false`로 두고 [settle]·[shot]으로 직접 흘린다.
 *   이렇게 해야 무한 애니메이션(충전 맥동, 호흡 원, 바람 곡선, 파도)도 움직인다. autoAdvance가 true인 동안에는
 *   Compose 테스트 정책이 무한 애니메이션을 취소하고, 한 번 취소된 화면에서는 다시 돌지 않는다.
 *
 * [shot]은 장면 앞부분을 [FRAME_MILLIS]마다 찍고 나머지는 마지막 프레임으로 채운다. 장면 길이는 [snap]과 같아서
 * 내레이션·자막 시각(scenes.txt)은 그대로다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi", application = Application::class)
class DemoVideoFrames {

    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler) + Job())

    private lateinit var fake: FakeVehicleGateway
    private lateinit var engine: NapSessionEngine
    private lateinit var viewModel: NapViewModel

    private data class CardText(val title: String, val subtitle: String, val footnote: String? = null)

    private val card = mutableStateOf<CardText?>(null)

    private val outDir = File("build/demo-video")
    private val frames = StringBuilder("ffconcat version 1.0\n")
    private val scenes = StringBuilder()
    private var frameNo = 0
    private var elapsed = 0.0
    private var lastFrame: String? = null

    @Before
    fun setUp() {
        assumeTrue("영상 프레임은 -Pchargenap.video=true 일 때만 만든다", System.getProperty("chargenap.video") == "true")
        outDir.deleteRecursively()
        outDir.mkdirs()

        fake = FakeVehicleGateway(
            FakeScenario(startPercent = 53, percentPerMinute = 1.0, autoStartChargingAfterMillis = null),
            timeScale = 1.0,
            scope = scope,
        )
        engine = NapSessionEngine(
            gateway = fake,
            clock = Clock { 1_799_998_200_000 + scheduler.currentTime },   // 2027-01 낮 시각대
            scope = scope,
            config = MutableStateFlow(SessionConfig()),
            tickMillis = 1_000,
        )
        val history = SessionHistoryRepository(InMemoryPreferencesStore())
        // 리포트가 비어 보이지 않도록 지난 며칠의 기록을 넣는다(데모 데이터).
        runBlocking {
            listOf(18, 20, 15, 25).forEachIndexed { i, minutes ->
                val day = 86_400_000L * (4 - i)
                history.add(
                    SessionRecord(
                        startedAtMillis = 1_799_998_200_000 - day,
                        endedAtMillis = 1_799_998_200_000 - day + minutes * 60_000L,
                        mode = com.chargenap.app.session.SessionMode.NAP,
                        plannedMinutes = minutes,
                        sleptMinutes = minutes,
                        batteryStartPercent = 41 + i * 3,
                        batteryEndPercent = 70 + i,
                        rangeKm = 320 + i * 10,
                        wakeReason = com.chargenap.app.session.WakeReason.TIMER,
                        seatMoved = true,
                        failures = emptyList(),
                    ),
                )
            }
        }
        scope.launch { engine.completedSessions.collect { history.add(SessionRecord.from(it)) } }
        runBlocking { fake.start() }
        engine.start()
        viewModel = NapViewModel(
            engine = engine,
            gateway = fake,
            demoControls = fake,
            settingsRepository = SettingsRepository(InMemoryPreferencesStore()),
            historyRepository = history,
            coroutineContext = scope.coroutineContext,
        )
        scheduler.runCurrent()
        // 화면 시계는 끝까지 직접 돌린다(위 설명). setContent 전에 꺼야 첫 화면의 무한 애니메이션도 산다.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val current by card
            val c = current
            if (c != null) TitleCard(c) else ChargeNapScreens(viewModel = viewModel, onSleepDimChanged = {})
        }
        settle()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------- 도우미

    /** 화면 시계를 [millis]만큼 흘린다. 화면 전환·등장 애니메이션은 [SETTLE_MILLIS]면 모두 끝난다. */
    private fun settle(millis: Long = SETTLE_MILLIS) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    /**
     * 엔진·가상 차량 시계를 [millis]만큼 흘린다. 이어서 화면 시계를 [settleMillis]만큼 흘려 화면이 따라오게 한다.
     * 바로 [shot]으로 움직임을 찍을 때는 [settleMillis]를 0으로 둔다.
     */
    private fun advance(millis: Long = 0, settleMillis: Long = SETTLE_MILLIS) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        if (settleMillis > 0) settle(settleMillis)
    }

    private fun scene(id: String) {
        scenes.append(id).append(' ').append("%.3f".format(Locale.US, elapsed)).append('\n')
    }

    /** 지금 화면을 [seconds]초짜리 프레임으로 저장한다. 길이는 소수 셋째 자리로 맞춰 frames.txt와 scenes.txt가 어긋나지 않게 한다. */
    private fun capture(seconds: Double) {
        compose.waitForIdle()
        val name = "f%04d.png".format(++frameNo)
        compose.onRoot().captureRoboImage(File(outDir, name).path)
        val duration = "%.3f".format(Locale.US, seconds)
        frames.append("file '").append(name).append("'\nduration ").append(duration).append('\n')
        elapsed += duration.toDouble()
        lastFrame = name
    }

    /** 지금 화면을 [seconds]초 동안 보여 줄 정지 프레임으로 저장한다. */
    private fun snap(seconds: Double) = capture(seconds)

    /**
     * 움직이는 장면. 처음 [animateMillis] 동안은 화면 시계를 [FRAME_MILLIS]씩 흘리며 매번 찍고(약 31fps),
     * 남은 시간은 마지막 프레임을 그대로 둔다. 장면 전체 길이는 [seconds]다.
     */
    private fun shot(seconds: Double, animateMillis: Long) {
        val limit = minOf(animateMillis, (seconds * 1000).toLong())
        var used = 0L
        while (used + 2 * FRAME_MILLIS <= limit) {
            settle(FRAME_MILLIS)
            capture(FRAME_MILLIS / 1000.0)
            used += FRAME_MILLIS
        }
        settle(FRAME_MILLIS)
        capture(seconds - used / 1000.0)
    }

    private fun showCard(title: String, subtitle: String, footnote: String? = null) {
        card.value = CardText(title, subtitle, footnote)
        settle(FRAME_MILLIS)
    }

    /** 앱 화면으로 돌아온다. 홈이 새로 나타나므로 바로 [shot]으로 찍을 때는 [settleMillis]를 0으로 둔다. */
    private fun showApp(settleMillis: Long = SETTLE_MILLIS) {
        card.value = null
        Snapshot.sendApplyNotifications()
        if (settleMillis > 0) settle(settleMillis)
    }

    private fun tap(text: String, settleMillis: Long = SETTLE_MILLIS) {
        compose.onNodeWithText(text).performClick()
        advance(settleMillis = settleMillis)
    }

    // ---------------------------------------------------------------- 대본

    @Test
    fun renderDemoVideoFrames() {
        // S01 제목
        scene("01")
        showCard("차지냅 ChargeNap", "차가 충전되는 동안, 나도 충전", "팀 Moveto · Pleos Connect 차량용 파워냅 앱")
        snap(6.5)

        // S02 문제
        scene("02")
        showCard(
            "급속 충전 1회 평균 47.2분",
            "차 안에서 휴대폰만 보던 그 시간을,\n충전이 끝나기 전에 개운하게 깨는 휴식으로",
            "서울시 급속 충전기 1회당 이용 시간 (한국ITS학회논문지, 2024)",
        )
        snap(10.5)

        // S03 충전 시작 → 남은 시간 추정 → 제안
        scene("03")
        showApp(settleMillis = 0)
        shot(1.6, 1_300)                            // 홈 등장: 잔량 고리가 차오르고 카드가 떠오른다
        fake.startCharging()
        advance(settleMillis = 0)
        shot(1.6, 900)                              // "얼마나 쉴까요?" — 충전 속도를 재는 중(교차 전환, 고리 끝 맥동)
        advance(60_000, settleMillis = 0)
        shot(1.2, 1_100)                            // 잔량 54% — 고리가 1%만큼 자란다
        advance(60_000, settleMillis = 0)
        shot(4.6, 1_800)                            // 55%: "80%까지 약 25분 남았어요. 20분 파워냅 어때요?"

        // S04 수락 → 안전 확인(뒷좌석 질문)
        scene("04")
        tap("시작", settleMillis = 0)
        shot(7.0, 1_700)                            // 오른쪽에서 밀려 들어오고 점검 줄이 차례로 체크된다
        tap("없어요", settleMillis = 0)
        shot(3.5, 3_500)                            // 준비 중: 등받이가 천천히 젖혀지고 바람이 흐른다

        // S05 캐빈 세팅 → 수면
        scene("05")
        advance(1_000, settleMillis = 0)
        shot(8.0, 6_000)                            // 화면이 어두워지고 호흡 원이 커진다(들숨 4초 → 날숨)

        // S06 수면 중(빨리 감기)
        scene("06")
        repeat(19) {
            advance(60_000)
            snap(0.31)
        }

        // S08 기상 → 브리핑
        scene("08")
        advance(60_000, settleMillis = 0)
        shot(3.0, 2_000)                            // 일어날 시간이에요: 밝아지며 새벽빛, 서늘한 바람
        advance(22_000, settleMillis = 0)
        shot(6.5, 1_700)                            // 잘 쉬셨어요: 타일이 떠오르며 잔량·주행 가능 거리·쉰 시간을 센다

        // S09 휴식 리포트
        scene("09")
        tap("확인")
        tap("리포트", settleMillis = 0)
        shot(7.5, 1_700)                            // 막대가 왼쪽부터 자라고 숫자를 센다
        tap("뒤로")

        // S07 안전: 수면 중 도어 열림 → 중단 → 닫으면 복구
        scene("07")
        fake.interruptCharging()
        advance(2_000)
        fake.setBatteryPercent(45)
        fake.startCharging()
        advance(3 * 60_000)
        tap("시작")
        tap("없어요")
        advance(1_000)
        advance(3 * 60_000)
        snap(1.5)                                   // 수면 중
        fake.setDoorOpen(true)
        advance(settleMillis = 0)
        shot(4.5, 1_000)                            // 문이 열려 휴식을 멈췄어요(빠르게 바뀌고 경고가 흔들린다)
        fake.setDoorOpen(false)
        advance(3_000, settleMillis = 0)
        shot(4.0, 1_000)                            // 시트와 공조를 원래대로 되돌렸어요(체크로 바뀐다)

        // S11 마무리
        scene("10")
        showCard("차지냅 ChargeNap", "차가 충전되는 동안, 나도 충전", "팀 Moveto · github.com/moveto-team/chargenap")
        snap(7.5)

        // concat 형식은 마지막 파일을 한 번 더 적어야 마지막 duration이 반영된다.
        frames.append("file '").append(lastFrame).append("'\n")
        File(outDir, "frames.txt").writeText(frames.toString())
        File(outDir, "scenes.txt").writeText(scenes.toString())
        File(outDir, "total.txt").writeText("%.3f\n".format(Locale.US, elapsed))
    }

    @Composable
    private fun TitleCard(c: CardText) {
        ChargeNapTheme {
            DesignCanvas {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 120.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(c.title, color = NapColors.TextPrimary, fontSize = 68.sp, fontWeight = FontWeight.Bold, lineHeight = 84.sp)
                    Spacer(Modifier.height(28.dp))
                    Text(c.subtitle, color = NapColors.Accent, fontSize = 36.sp, lineHeight = 52.sp)
                    if (c.footnote != null) {
                        Spacer(Modifier.height(56.dp))
                        Text(c.footnote, color = NapColors.TextTertiary, fontSize = 22.sp)
                    }
                }
            }
        }
    }

    private companion object {
        /** 영상 한 프레임의 화면 시계 길이. Compose 프레임(16ms) 두 개, 약 31fps. 소수 셋째 자리로 딱 떨어진다. */
        const val FRAME_MILLIS = 32L

        /** 화면 전환·등장 애니메이션이 모두 끝나는 시간(가장 긴 것: 기상 새벽빛 1.5초 + 지연). */
        const val SETTLE_MILLIS = 1_800L
    }
}
