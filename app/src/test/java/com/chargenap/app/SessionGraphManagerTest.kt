package com.chargenap.app

import com.chargenap.app.data.AppSettings
import com.chargenap.app.session.Clock
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.CabinSnapshot
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
import com.chargenap.app.vehicle.NapPreset
import com.chargenap.app.vehicle.ParkState
import com.chargenap.app.vehicle.VehicleCapabilities
import com.chargenap.app.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionGraphManagerTest {

    private val settings = MutableStateFlow(AppSettings())
    private val completed = mutableListOf<SessionSummary>()

    private fun TestScope.manager(
        realGatewayFactory: (CoroutineScope) -> VehicleGateway = { UnreachableGateway() },
        attachExtras: (SessionGraph) -> (() -> Unit) = { {} },
    ) = SessionGraphManager(
        parentScope = backgroundScope,
        settings = settings,
        realGatewayFactory = realGatewayFactory,
        onSessionCompleted = { completed += it },
        attachExtras = attachExtras,
        baseClock = Clock { testScheduler.currentTime },
        // 가상 시간이라 배속 없이 돌린다. 충전은 3초 뒤 자동으로 시작한다.
        demoScenario = FakeScenario(startPercent = 52, percentPerMinute = 1.0, reportsRemainingTime = true),
        demoTimeScale = 1.0,
    )

    @Test
    fun demoMode_buildsFakeGraphWithDemoBadge() = runTest {
        val manager = manager()
        assertNull(manager.graph.value)

        manager.rebuild(demoMode = true)

        val graph = manager.graph.value!!
        assertTrue(graph.isDemo)
        assertTrue(graph.gateway.capabilities.value.isDemo)
        assertNotNull(graph.demoControls)
        assertNull(graph.fallbackReason)
    }

    @Test
    fun pleosInitFails_fallsBackToDemo() = runTest {
        val manager = manager(realGatewayFactory = { UnreachableGateway("Vehicle Service 없음") })

        manager.rebuild(demoMode = false)

        val graph = manager.graph.value!!
        assertTrue(graph.isDemo)
        assertTrue(graph.gateway.capabilities.value.isDemo)
        assertEquals("Vehicle Service 없음", graph.fallbackReason)
    }

    @Test
    fun realGatewayConnects_isNotDemo() = runTest {
        val real = FakeVehicleGateway(FakeScenario(autoStartChargingAfterMillis = null), 1.0, backgroundScope)
        val notDemo = object : VehicleGateway by real {
            override val capabilities: StateFlow<VehicleCapabilities> =
                MutableStateFlow(real.capabilities.value.copy(isDemo = false))
        }
        val manager = manager(realGatewayFactory = { notDemo })

        manager.rebuild(demoMode = false)

        val graph = manager.graph.value!!
        assertFalse(graph.isDemo)
        assertNull(graph.demoControls)
        assertNull(graph.fallbackReason)
        assertFalse(graph.gateway.capabilities.value.isDemo)
    }

    @Test
    fun switchGraph_duringNap_restoresCabinFirst() = runTest {
        val manager = manager()
        manager.rebuild(demoMode = true)
        val first = manager.graph.value!!
        val fake = first.gateway as FakeVehicleGateway

        // 충전 시작 → 제안 → 수락 → 뒷좌석 비어 있음 → 시트가 눕는다
        advanceTimeBy(5_000)
        assertTrue(first.engine.state.value is NapSessionState.Offer)
        first.engine.requestStart(20)
        runCurrent()
        first.engine.answerRearSeat(true)
        advanceTimeBy(5_000)
        assertTrue(first.engine.state.value is NapSessionState.Napping)
        assertEquals(60, fake.seatReclineLevel.value)

        manager.rebuild(demoMode = true)

        assertEquals("그래프를 바꾸기 전에 시트를 되돌려야 한다", 0, fake.seatReclineLevel.value)
        assertTrue("restore" in fake.commandLog)
        assertEquals(NapSessionState.Idle, first.engine.state.value)
        val second = manager.graph.value!!
        assertNotEquals(first.id, second.id)
        assertEquals(NapSessionState.Idle, second.engine.state.value)
    }

    @Test
    fun rebuild_detachesExtrasOfOldGraph() = runTest {
        val events = mutableListOf<String>()
        val manager = manager(attachExtras = { graph ->
            events += "attach ${graph.id}"
            return@manager { events += "detach ${graph.id}" }
        })

        manager.rebuild(demoMode = true)
        manager.rebuild(demoMode = true)
        manager.close()

        assertEquals(listOf("attach 1", "detach 1", "attach 2", "detach 2"), events)
        assertNull(manager.graph.value)
    }

    @Test
    fun settingsChange_reachesEngineConfig() = runTest {
        val manager = manager()
        manager.rebuild(demoMode = true)
        val graph = manager.graph.value!!
        val fake = graph.gateway as FakeVehicleGateway

        settings.value = AppSettings(reclineLevel = 40, temperatureC = 24.5f, fanLevel = 1)
        advanceTimeBy(5_000)
        graph.engine.requestStart(20)
        runCurrent()
        graph.engine.answerRearSeat(true)
        advanceTimeBy(5_000)

        assertTrue("applyNap(40,24.5,1)" in fake.commandLog)
    }

    @Test
    fun completedSession_isReportedOnce() = runTest {
        val manager = manager()
        manager.rebuild(demoMode = true)
        val graph = manager.graph.value!!

        advanceTimeBy(5_000)
        graph.engine.requestStart(10)
        runCurrent()
        graph.engine.answerRearSeat(false)
        advanceTimeBy(11 * 60_000L)
        assertTrue(graph.engine.state.value is NapSessionState.Briefing)
        graph.engine.acknowledgeBriefing()
        runCurrent()

        assertEquals(1, completed.size)
        assertEquals(10, completed.single().plannedMinutes)
        assertFalse(completed.single().seatMoved)
    }

    /** `start()`가 실패하는 게이트웨이. 실제 차량 서비스가 없는 기기를 흉내 낸다. */
    private class UnreachableGateway(private val reason: String = "연결 실패") : VehicleGateway {
        override val chargingState = MutableStateFlow(ChargingState.UNKNOWN)
        override val batteryPercent = MutableStateFlow<Int?>(null)
        override val remainingChargeMinutes = MutableStateFlow<Int?>(null)
        override val parkState = MutableStateFlow(ParkState(null, null))
        override val anyDoorOpen = MutableStateFlow<Boolean?>(null)
        override val capabilities = MutableStateFlow(VehicleCapabilities.NONE)

        override suspend fun start(): Unit = throw IllegalStateException(reason)
        override fun stop() = Unit
        override suspend fun captureCabinSnapshot() = CabinSnapshot(null, null)
        override suspend fun applyNapPreset(preset: NapPreset) = CabinApplyResult.NOTHING
        override suspend fun applyWakePreset() = CabinApplyResult.NOTHING
        override suspend fun restoreCabin(snapshot: CabinSnapshot) = CabinApplyResult.NOTHING
        override suspend fun rangeRemainingKm(): Int? = null
    }
}
