package com.chargenap.app

import android.content.Context
import com.chargenap.app.data.AppSettings
import com.chargenap.app.data.SessionHistoryRepository
import com.chargenap.app.data.SessionRecord
import com.chargenap.app.data.SettingsRepository
import com.chargenap.app.vehicle.PleosVehicleGateway
import com.chargenap.app.voice.AmbientSound
import com.chargenap.app.voice.AmbientSoundPlayer
import com.chargenap.app.voice.NoopVoiceController
import com.chargenap.app.voice.PleosVoiceController
import com.chargenap.app.voice.ResourceVoicePhrases
import com.chargenap.app.voice.VoiceController
import com.chargenap.app.voice.VoicePhrases
import com.chargenap.app.voice.VoiceSessionBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 수동 DI 컨테이너. 앱이 살아 있는 동안 하나만 있다.
 *
 * 실제 차량(Pleos) 게이트웨이를 먼저 시도하고, 연결하지 못하거나 설정에서 데모 모드를 켜면
 * 가상 차량으로 동작한다. 가상 차량일 때는 화면에 DEMO 배지가 뜬다.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(appContext)
    val historyRepository = SessionHistoryRepository(appContext)

    /** 가장 최근 설정. 저장소를 다 읽기 전에는 기본값이다. */
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    private val voiceController: VoiceController = PleosVoiceController(appContext, appScope)
    private val silentVoice: VoiceController = NoopVoiceController()
    private val phrases: VoicePhrases = ResourceVoicePhrases(appContext)
    private val ambient: AmbientSound = AmbientSoundPlayer()

    /** 음성을 쓸 수 있고 설정에서도 켜져 있는가. 홈 화면의 음성 힌트에 쓴다. */
    val voiceAvailable: StateFlow<Boolean> =
        combine(voiceController.available, settings) { available, current -> available && current.voiceEnabled }
            .stateIn(appScope, SharingStarted.Eagerly, false)

    private val graphs = SessionGraphManager(
        parentScope = appScope,
        settings = settingsRepository.settings,
        realGatewayFactory = { scope -> PleosVehicleGateway(appContext, scope) },
        onSessionCompleted = { summary -> historyRepository.add(SessionRecord.from(summary)) },
        attachExtras = ::attachVoice,
    )

    /** 차량에 연결하는 동안(또는 교체하는 동안)은 null. */
    val graph: StateFlow<SessionGraph?> = graphs.graph

    private var started = false

    /** 앱 시작 때 한 번 부른다. 음성을 초기화하고 데모 모드 설정에 맞춰 그래프를 만든다. */
    fun start() {
        if (started) return
        started = true
        appScope.launch { voiceController.initialize() }
        appScope.launch {
            settingsRepository.settings.map { it.demoMode }.distinctUntilChanged().collect { demoMode ->
                graphs.rebuild(demoMode)
            }
        }
    }

    /** 실행 중 권한(android.car.permission.CAR_ENERGY)이 부여된 뒤 부른다. */
    fun onVehiclePermissionsChanged() {
        (graph.value?.gateway as? PleosVehicleGateway)?.refreshAaosAvailability()
    }

    /**
     * 그래프에 음성 안내·명령과 백색소음을 붙인다.
     * 설정에서 음성을 끄면 말하거나 듣지 않지만 백색소음은 계속 제어한다.
     */
    private fun attachVoice(graph: SessionGraph): () -> Unit {
        val lock = Any()
        var binder: VoiceSessionBinder? = null
        var detached = false

        val job: Job = graph.scope.launch {
            settingsRepository.settings.map { it.voiceEnabled }.distinctUntilChanged().collect { enabled ->
                synchronized(lock) {
                    binder?.stop()
                    binder = null
                    if (detached) return@synchronized
                    binder = VoiceSessionBinder(
                        engine = graph.engine,
                        gateway = graph.gateway,
                        voice = if (enabled) voiceController else silentVoice,
                        phrases = phrases,
                        ambient = ambient,
                        scope = graph.scope,
                        targetPercent = { settings.value.targetPercent },
                    ).also { it.start() }
                }
            }
        }
        return {
            synchronized(lock) {
                detached = true
                binder?.stop()
                binder = null
            }
            job.cancel()
        }
    }
}
