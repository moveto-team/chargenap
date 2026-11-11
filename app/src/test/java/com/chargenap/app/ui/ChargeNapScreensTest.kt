package com.chargenap.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.chargenap.app.data.InMemoryPreferencesStore
import com.chargenap.app.data.SessionHistoryRepository
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.SettingsRepository
import com.chargenap.app.session.Clock
import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.SessionConfig
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 진입점 [ChargeNapScreens]를 실제 ViewModel·엔진·가상 차량과 묶어, 화면 터치만으로 세션 한 바퀴가 도는지 확인한다.
 * 엔진과 가상 차량의 시간은 테스트 스케줄러로 직접 흘린다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-land-mdpi", application = Application::class)
class ChargeNapScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(dispatcher + Job())

    private lateinit var fake: FakeVehicleGateway
    private lateinit var engine: NapSessionEngine
    private lateinit var history: SessionHistoryRepository
    private lateinit var viewModel: NapViewModel
    private val dimChanges = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        fake = FakeVehicleGateway(
            FakeScenario(startPercent = 50, percentPerMinute = 1.0, autoStartChargingAfterMillis = null),
            timeScale = 1.0,
            scope = scope,
        )
        engine = NapSessionEngine(
            gateway = fake,
            clock = Clock { 1_800_000_000_000 + scheduler.currentTime },
            scope = scope,
            config = MutableStateFlow(SessionConfig()),
            tickMillis = 1_000,
        )
        history = SessionHistoryRepository(InMemoryPreferencesStore())
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
        compose.setContent {
            ChargeNapScreens(viewModel = viewModel, onSleepDimChanged = { dimChanges += it })
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun advance(millis: Long = 0) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        compose.waitForIdle()
    }

    @Test
    fun touchOnly_fullSession_fromDemoPanelToReport() {
        // 홈: 충전 전이라 시작할 수 없다. DEMO 배지가 보인다.
        compose.onNodeWithText("DEMO").assertIsDisplayed()
        compose.onNodeWithText("충전기를 연결하면 시작할 수 있어요").assertIsDisplayed()
        assertEquals(listOf(false), dimChanges)

        // DEMO 조작 패널로 충전을 시작하면 제안이 뜬다.
        fake.setRemainingChargeMinutes(27)
        compose.onNodeWithText("DEMO 조작").performClick()
        compose.onNodeWithText("충전 시작").performClick()
        advance()
        compose.onNodeWithText("접기").performClick()
        compose.onNodeWithText("20분 파워냅 어때요?").assertIsDisplayed()

        // 수락 → 뒷좌석 질문 → 준비 → 수면
        compose.onNodeWithText("시작").performClick()
        advance()
        compose.onNodeWithText("없어요").performClick()
        advance(1_000)
        assertTrue(engine.state.value is NapSessionState.Napping)
        compose.onNodeWithText("20:00").assertIsDisplayed()
        assertEquals(listOf(false, true), dimChanges)
        assertEquals(60, fake.seatReclineLevel.value)

        // 남은 시간이 엔진 시계를 따라 줄어든다.
        advance(61_000)
        compose.onNodeWithText("18:59").assertIsDisplayed()

        // 길게 눌러 기상 → 브리핑 → 확인
        compose.onNodeWithText("18:59").performTouchInput { longClick() }
        advance()
        compose.onNodeWithText("일어나는 중이에요").assertIsDisplayed()
        assertEquals(listOf(false, true, false), dimChanges)
        advance(22_000)
        compose.onNodeWithText("잘 쉬셨어요").assertIsDisplayed()
        assertEquals(0, fake.seatReclineLevel.value)
        compose.onNodeWithText("확인").performClick()
        advance()

        // 홈으로 돌아오고, 리포트에 방금 세션이 나온다.
        compose.onNodeWithText("리포트").performClick()
        compose.onNodeWithText("1분 파워냅").assertIsDisplayed()
        compose.onNodeWithText("최근 휴식").assertIsDisplayed()
    }

    @Test
    fun doorOpensDuringNap_showsAbortedAndRestores() {
        fake.setRemainingChargeMinutes(27)
        fake.startCharging()
        advance()
        compose.onNodeWithText("시작").performClick()
        advance()
        compose.onNodeWithText("없어요").performClick()
        advance(1_000)
        assertEquals(listOf(false, true), dimChanges)

        fake.setDoorOpen(true)
        advance()
        compose.onNodeWithText("문이 열려 휴식을 멈췄어요").assertIsDisplayed()
        compose.onNodeWithText("문을 닫으면 시트를 되돌려요").assertIsDisplayed()
        assertEquals(listOf(false, true, false), dimChanges)
        assertEquals(60, fake.seatReclineLevel.value)

        // 문을 닫으면 버튼을 누르지 않아도 복구한다.
        fake.setDoorOpen(false)
        advance(2_000)
        compose.onNodeWithText("시트와 공조를 원래대로 되돌렸어요").assertIsDisplayed()
        assertEquals(0, fake.seatReclineLevel.value)
        compose.onNodeWithText("확인").performClick()
        advance()
        compose.onNodeWithText("차지냅").assertIsDisplayed()
    }
}
