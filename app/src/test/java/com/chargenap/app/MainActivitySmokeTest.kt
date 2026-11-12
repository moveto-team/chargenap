package com.chargenap.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.vehicle.ChargingState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 실제 [ChargeNapApplication]·[AppContainer]·[MainActivity]로 앱을 띄운다.
 *
 * 테스트 환경에는 Pleos Vehicle Service가 없으므로, 실제 게이트웨이 연결이 실패하고
 * 가상 차량으로 대체되어 DEMO 배지가 뜨는 경로(일반 기기에서의 첫 실행)를 확인한다.
 * 데모 모드는 60배속이라 실제 시간 몇 초 안에 충전이 시작되고 제안이 뜬다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-land-mdpi")
class MainActivitySmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container: AppContainer
        get() = (compose.activity.application as ChargeNapApplication).container

    @Test
    fun launch_withoutVehicleService_fallsBackToDemoAndOffersNap() {
        // 실제 차량 연결 실패 → 가상 차량 그래프
        compose.waitUntil(timeoutMillis = 30_000) { container.graph.value != null }
        val graph = container.graph.value!!
        assertTrue("Vehicle Service가 없으면 데모로 대체돼야 한다", graph.isDemo)
        assertNotNull("대체 사유가 남아야 한다", graph.fallbackReason)
        assertTrue(graph.gateway.capabilities.value.isDemo)

        // 화면에 DEMO 배지
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTextCompat("DEMO").isNotEmpty()
        }
        compose.onNodeWithText("DEMO").assertIsDisplayed()

        // 가상 충전이 시작되면 제안 상태가 된다
        // (60배속이라 제안은 실제 18초쯤만 떠 있다. 놓쳤으면 DEMO 조작과 같은 방법으로 새 충전을 시작한다)
        compose.waitUntil(timeoutMillis = 120_000) {
            val offered = graph.engine.state.value is NapSessionState.Offer
            if (!offered && graph.gateway.chargingState.value != ChargingState.CHARGING) {
                graph.demoControls!!.startCharging()
            }
            offered
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../docs/screenshots/00_app_launch_demo_fallback.png")

        // 설정에 저장된 값이 없으면 기본 설정으로 동작한다
        assertEquals(80, container.settings.value.targetPercent)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCompat(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes()
