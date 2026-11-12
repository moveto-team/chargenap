package com.chargenap.app

import com.chargenap.app.data.AppSettings
import com.chargenap.app.data.toSessionConfig
import com.chargenap.app.session.Clock
import com.chargenap.app.session.NapSessionEngine
import com.chargenap.app.session.ScaledClock
import com.chargenap.app.session.SessionSummary
import com.chargenap.app.session.SessionTimings
import com.chargenap.app.session.NapSessionState
import com.chargenap.app.vehicle.DemoVehicleControls
import com.chargenap.app.vehicle.FakeScenario
import com.chargenap.app.vehicle.FakeVehicleGateway
import com.chargenap.app.vehicle.VehicleGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 한 번에 하나만 살아 있는 "차량 + 세션 엔진" 묶음.
 * 데모 모드를 켜고 끄면 통째로 새로 만든다.
 */
class SessionGraph internal constructor(
    val id: Int,
    val gateway: VehicleGateway,
    val engine: NapSessionEngine,
    /** 가상 차량일 때만 있다. 화면의 "DEMO 조작" 패널이 쓴다. */
    val demoControls: DemoVehicleControls?,
    val isDemo: Boolean,
    /** 실제 차량에 연결하지 못해 가상 차량으로 대체됐을 때의 사유. */
    val fallbackReason: String?,
    internal val scope: CoroutineScope,
)

/**
 * 기기 시각이 GNSS·NTP 동기화로 바뀌어도 흔들리지 않는 세션 시계.
 *
 * 단조 시계([System.nanoTime])로 흐르고, 만든 순간의 벽시계 시각에서 출발하므로
 * 세션 기록의 날짜도 맞는다. 수면 중 시각이 30분 뒤로 맞춰져도 기상이 늦어지지 않는다.
 */
class MonotonicWallClock(
    wallMillis: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : Clock {
    private val originWall = wallMillis()
    private val originNanos = monotonicNanos()

    override fun nowMillis(): Long = originWall + (monotonicNanos() - originNanos) / 1_000_000
}

/** 이 상태에서는 앱이 뒤로 가도 감시·타이머가 계속 돌아야 한다(포그라운드 서비스 유지). */
fun NapSessionState.needsForegroundService(): Boolean = when (this) {
    NapSessionState.Idle, is NapSessionState.Offer -> false
    is NapSessionState.SafetyCheck,
    is NapSessionState.Preparing,
    is NapSessionState.Napping,
    is NapSessionState.Waking,
    is NapSessionState.Briefing,
    is NapSessionState.Aborted,
    -> true
}

/**
 * [SessionGraph]를 만들고 교체한다.
 *
 * - 데모 모드가 아니면 실제 게이트웨이를 먼저 시도하고, 연결에 실패하면 가상 차량으로 대체한다.
 * - 교체할 때는 진행 중인 세션의 캐빈을 먼저 복구한다(시트가 누운 채로 남지 않게).
 *
 * @param parentScope 그래프 스코프의 부모. 디스패처도 여기서 물려받는다(테스트에서는 가상 시간)
 * @param realGatewayFactory 실제 차량 게이트웨이를 만든다. `start()`가 예외를 던지면 연결 실패로 본다
 * @param onSessionCompleted 브리핑까지 끝난 세션의 요약. 기록 저장에 쓴다
 * @param attachExtras 새 그래프에 붙일 부가 기능(음성 등). 돌려준 함수는 그래프를 버릴 때 불린다
 */
class SessionGraphManager(
    private val parentScope: CoroutineScope,
    private val settings: Flow<AppSettings>,
    private val realGatewayFactory: (CoroutineScope) -> VehicleGateway,
    private val onSessionCompleted: suspend (SessionSummary) -> Unit,
    private val attachExtras: (SessionGraph) -> (() -> Unit) = { {} },
    private val baseClock: Clock = MonotonicWallClock(),
    // 데모: 잔량 30%에서 시작해 80%까지 약 50분(세션 시계) 충전한다. 제안 카드가 실제 시간 약 80초 유지된다.
    private val demoScenario: FakeScenario = FakeScenario(startPercent = 30, restartWhenNearlyFull = true),
    private val demoTimeScale: Double = DEMO_TIME_SCALE,
) {
    private val _graph = MutableStateFlow<SessionGraph?>(null)
    val graph: StateFlow<SessionGraph?> = _graph.asStateFlow()

    private val mutex = Mutex()
    private var nextId = 1
    private var detachExtras: (() -> Unit)? = null

    /** 지금 그래프를 정리하고 새로 만든다. 만드는 동안 [graph]는 null이다. */
    suspend fun rebuild(demoMode: Boolean) = mutex.withLock {
        teardownLocked()
        val created = build(demoMode)
        detachExtras = attachExtras(created)
        _graph.value = created
    }

    /** 진행 중인 세션을 복구하고 모두 정리한다. */
    suspend fun close() = mutex.withLock { teardownLocked() }

    private suspend fun teardownLocked() {
        val old = _graph.value ?: return
        _graph.value = null
        detachExtras?.invoke()
        detachExtras = null
        // 순서가 중요하다: 엔진이 캐빈을 복구한 뒤에 게이트웨이를 끊는다.
        old.engine.shutdown()
        old.gateway.stop()
        old.scope.cancel()
    }

    private suspend fun build(demoMode: Boolean): SessionGraph {
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        var fallbackReason: String? = null

        var real: VehicleGateway? = null
        if (!demoMode) {
            try {
                real = realGatewayFactory(scope).also { it.start() }
            } catch (e: CancellationException) {
                scope.cancel()
                throw e
            } catch (e: Exception) {
                real = null
                fallbackReason = e.message ?: e.javaClass.simpleName
            }
        }

        val fake = if (real == null) {
            FakeVehicleGateway(demoScenario, timeScale = demoTimeScale, scope = scope).also { it.start() }
        } else {
            null
        }
        val isDemo = fake != null

        val config = settings.map { it.toSessionConfig() }
            .stateIn(scope, SharingStarted.Eagerly, settings.first().toSessionConfig())

        val engine = if (isDemo) {
            NapSessionEngine(
                gateway = fake!!,
                clock = ScaledClock(demoTimeScale, baseClock),
                scope = scope,
                config = config,
                tickMillis = DEMO_TICK_MILLIS,
                timings = SessionTimings.scaledForDemo(demoTimeScale),
            )
        } else {
            NapSessionEngine(gateway = real!!, clock = baseClock, scope = scope, config = config)
        }
        scope.launch { engine.completedSessions.collect { onSessionCompleted(it) } }
        engine.start()

        return SessionGraph(
            id = nextId++,
            gateway = fake ?: real!!,
            engine = engine,
            demoControls = fake,
            isDemo = isDemo,
            fallbackReason = fallbackReason,
            scope = scope,
        )
    }

    companion object {
        /**
         * 데모 모드 배속. 20분 세션이 40초에 재생된다.
         * 60배는 느린 에뮬레이터에서 화면을 조작할 틈이 없어 30배로 정했다.
         */
        const val DEMO_TIME_SCALE = 30.0
        private const val DEMO_TICK_MILLIS = 250L
    }
}
