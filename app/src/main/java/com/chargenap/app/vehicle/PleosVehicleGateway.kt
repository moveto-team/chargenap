package com.chargenap.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.content.pm.PackageManager
import com.chargenap.app.vehicle.pleos.AaosSignals
import com.chargenap.app.vehicle.pleos.CarPropertyAdapter
import com.chargenap.app.vehicle.pleos.ChargingInputs
import com.chargenap.app.vehicle.pleos.ClimateController
import com.chargenap.app.vehicle.pleos.ClimateOutcome
import com.chargenap.app.vehicle.pleos.ClimateSnapshot
import com.chargenap.app.vehicle.pleos.LatestValueGuard
import com.chargenap.app.vehicle.pleos.PleosCapabilities
import com.chargenap.app.vehicle.pleos.PleosClimatePort
import com.chargenap.app.vehicle.pleos.PleosSeatControl
import com.chargenap.app.vehicle.pleos.PleosSeatSnapshot
import com.chargenap.app.vehicle.pleos.SdkCalls
import com.chargenap.app.vehicle.pleos.SdkOutcome
import com.chargenap.app.vehicle.pleos.SeatMoveResult
import com.chargenap.app.vehicle.pleos.anyDoorOpen
import com.chargenap.app.vehicle.pleos.isConnectionFailure
import com.chargenap.app.vehicle.pleos.isDoorOpen
import com.chargenap.app.vehicle.pleos.mapChargingState
import com.chargenap.app.vehicle.pleos.probeCapabilities
import com.chargenap.app.vehicle.pleos.rangeKmFromMeters
import com.chargenap.app.vehicle.pleos.readable
import com.chargenap.app.vehicle.pleos.remainingChargeMinutes
import com.chargenap.app.vehicle.pleos.valueOrNull
import com.chargenap.app.vehicle.pleos.wakeFanLevel
import com.chargenap.app.vehicle.pleos.wakeTemperature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Pleos Vehicle Service에 연결하지 못했다. 호출자(AppContainer)가 잡아 가상 차량으로 대체한다. */
class PleosUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pleos Connect Vehicle SDK 2.3.2로 구현한 [VehicleGateway]. 호출하는 SDK API는 전부 docs/sdk-notes.md 기준이다.
 *
 * SDK가 주지 않는 값과 대체 수단
 * - 충전 상태: SDK에 없다. AAOS `EV_CHARGE_STATE`를 읽을 수 있으면 그 값, 아니면 충전 스위치·잔량·충전 포트로 추론한다.
 * - 남은 충전 시간: SDK에 없다. AAOS `EV_CHARGE_TIME_REMAINING`을 읽을 수 있을 때만 값이 나온다.
 * - 주차 브레이크: SDK에 없다. AAOS `PARKING_BRAKE_ON`을 읽을 수 있을 때만 값이 나온다.
 * - 시트: 절대 위치 지정이 없다. 방향 이동 명령을 보내고 위치를 지켜보다가 멈춘다.
 *
 * 값은 리스너로 받되, 리스너가 조용한 환경에 대비해 [POLL_INTERVAL_MS]마다 다시 조회한다
 * (충전 스위치는 리스너가 아예 없어 조회로만 갱신된다).
 *
 * @param scope 구독·폴링이 도는 스코프. 이 스코프가 취소되면 [stop]과 같은 정리를 한다.
 */
class PleosVehicleGateway(
    context: Context,
    private val scope: CoroutineScope,
) : VehicleGateway {

    private val appContext: Context = context.applicationContext ?: context

    private val _chargingState = MutableStateFlow(ChargingState.UNKNOWN)
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    private val _remainingChargeMinutes = MutableStateFlow<Int?>(null)
    private val _parkState = MutableStateFlow(UNKNOWN_PARK_STATE)
    private val _anyDoorOpen = MutableStateFlow<Boolean?>(null)
    private val _capabilities = MutableStateFlow(VehicleCapabilities.NONE)

    override val chargingState: StateFlow<ChargingState> = _chargingState.asStateFlow()
    override val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()
    override val remainingChargeMinutes: StateFlow<Int?> = _remainingChargeMinutes.asStateFlow()
    override val parkState: StateFlow<ParkState> = _parkState.asStateFlow()
    override val anyDoorOpen: StateFlow<Boolean?> = _anyDoorOpen.asStateFlow()
    override val capabilities: StateFlow<VehicleCapabilities> = _capabilities.asStateFlow()

    /** start()끼리 겹치지 않게 한다. */
    private val startMutex = Mutex()

    /** 스냅샷·프리셋·복구를 한 번에 하나씩만 실행한다(복구가 진행 중인 적용과 섞이지 않게). */
    private val cabinMutex = Mutex()

    /** [session], 세션의 원시 값, 공개 Flow 갱신을 지킨다. 이 락을 쥔 채로 SDK를 부르지 않는다. */
    private val stateLock = Any()
    private var session: Session? = null

    @Volatile
    private var lastNapPreset: NapPreset? = null

    // ---- 연결과 해제 ----

    /**
     * Vehicle Service에 연결하고 capability 조회, 구독, 폴링을 시작한다.
     *
     * @throws PleosUnavailableException 서비스 패키지가 없거나, [CONNECT_TIMEOUT_MS] 안에 응답이 없거나,
     *   권한이 없을 때. 이때 구독·폴링은 하나도 남지 않는다.
     */
    override suspend fun start() {
        startMutex.withLock {
            if (currentSession() != null) return
            requireVehicleService()
            val created = openVehicle()
            synchronized(stateLock) { session = created }
            created.job.invokeOnCompletion { close(created) } // scope가 취소돼도 리스너와 바인딩을 남기지 않는다
            try {
                val batteryReadable = connect(created)
                val capabilities = probeCapabilities(created.vehicle, created.sdk, batteryReadable)
                created.attach(capabilities)
                withContext(Dispatchers.IO) { created.aaos.refresh() }
                subscribe(created, capabilities)
                refreshAll(created, capabilities) // 리스너는 초기값을 보장하지 않는다(sdk-notes 9.3)
                created.launchBackgroundJobs(capabilities)
                created.ensureOpen()
            } catch (e: CancellationException) {
                close(created)
                throw e
            } catch (e: PleosUnavailableException) {
                close(created)
                throw e
            } catch (t: Throwable) {
                close(created)
                throw PleosUnavailableException("차량 연결 실패: ${describe(t)}", t)
            }
        }
    }

    /** 폴링 중단, 리스너 해제, AAOS 콜백 해제. SDK 해제는 진행 중인 콜백이 끝난 뒤 백그라운드에서 한다. */
    override fun stop() {
        currentSession()?.let { close(it) }
        lastNapPreset = null
    }

    /**
     * AAOS 보강 신호를 쓸 수 있는지 다시 확인한다. `android.car.permission.CAR_ENERGY`(dangerous)가
     * 실행 중에 부여된 뒤 부르면 충전 상태·남은 충전 시간이 반영된다. 시작 전이면 아무 일도 하지 않는다.
     */
    fun refreshAaosAvailability() {
        val current = currentSession() ?: return
        current.scope.launch { current.refreshAaos(reconnect = true) }
    }

    private fun currentSession(): Session? = synchronized(stateLock) { session }

    /** 공식 샘플과 같은 방식으로 서비스 패키지가 있는지 본다(sdk-notes 2절). */
    private fun requireVehicleService() {
        try {
            appContext.packageManager.getPackageInfo(VEHICLE_SERVICE_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw PleosUnavailableException("Vehicle Service($VEHICLE_SERVICE_PACKAGE)가 설치돼 있지 않음", e)
        } catch (t: Throwable) {
            throw PleosUnavailableException("Vehicle Service 확인 실패: ${describe(t)}", t)
        }
    }

    /** stop() 뒤 다시 start() 할 수 있게 매번 새 Vehicle 인스턴스를 만든다(release 뒤 재사용 가능 여부가 문서에 없다). */
    private fun openVehicle(): Session {
        val vehicle = try {
            Vehicle(appContext)
        } catch (t: Throwable) {
            throw PleosUnavailableException("Vehicle SDK 생성 실패: ${describe(t)}", t)
        }
        try {
            vehicle.initialize()
        } catch (t: Throwable) {
            try {
                vehicle.release()
            } catch (ignored: Throwable) {
                // 초기화도 못 한 인스턴스다
            }
            throw PleosUnavailableException("Vehicle SDK 초기화 실패: ${describe(t)}", t)
        }
        return Session(vehicle)
    }

    /**
     * `initialize()`는 성공·실패를 알려 주지 않는다. 첫 capability 호출이 돌아오는지로 연결을 판정한다.
     * @return 잔량을 읽을 수 있는지
     */
    private suspend fun connect(target: Session): Boolean {
        val evBattery = target.vehicle.getEvBattery()
        val first = target.sdk.request<CapabilityConfig>(CONNECT_TIMEOUT_MS) { complete, fail ->
            evBattery.checkEvBatteryStateOfChargeCapabilities(onComplete = complete, onFailed = fail)
        }
        target.ensureOpen()
        return when (first) {
            is SdkOutcome.Success -> first.value.accessType.readable
            SdkOutcome.Timeout ->
                throw PleosUnavailableException("Vehicle Service가 ${CONNECT_TIMEOUT_MS / 1000}초 안에 응답하지 않음")
            is SdkOutcome.Failure -> when {
                first.permissionDenied -> throw PleosUnavailableException("Vehicle SDK 권한이 없음: ${first.message}")
                isConnectionFailure(first.message) ->
                    throw PleosUnavailableException("Vehicle Service에 연결하지 못함: ${first.message}")
                else -> false // 서비스는 응답했다. 이 차량이 잔량을 주지 않을 뿐이다
            }
        }
    }

    private fun close(target: Session) {
        val unregisters = synchronized(stateLock) {
            if (target.closed) return
            target.closed = true
            if (session === target) {
                session = null
                resetOutputsLocked()
            }
            target.takeUnregisters()
        }
        target.job.cancel()
        target.seat?.stopIfMoving()
        for (unregister in unregisters) {
            try {
                unregister()
            } catch (t: Throwable) {
                // 해제 실패는 무시한다. 아래 release()가 리스너를 모두 비운다
            }
        }
        releaseWhenIdle(target)
    }

    /**
     * "콜백이 끝나기 전에 release()를 부르지 말 것"(sdk-notes 2절)을 지키려고, 아직 오지 않은 콜백과
     * 진행 중인 캐빈 제어가 끝나기를 잠깐 기다린 뒤 해제한다. 서비스가 죽어 콜백이 영영 오지 않을 수 있어 상한을 둔다.
     */
    private fun releaseWhenIdle(target: Session) {
        val release = Thread {
            val deadline = System.nanoTime() + RELEASE_WAIT_MS * 1_000_000
            while ((target.sdk.awaitingCallbacks > 0 || cabinMutex.isLocked) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(RELEASE_CHECK_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
            target.aaos.disconnect()
            try {
                target.vehicle.release()
            } catch (t: Throwable) {
                // 이미 끊긴 바인딩
            }
        }
        release.name = "pleos-vehicle-release"
        release.isDaemon = true
        release.start()
    }

    // ---- 구독과 조회 ----

    /**
     * 리스너를 등록한다. 리스너의 `onFailed`로는 값을 지우지 않는다. "읽지 못함"은 주기적 조회가 판정한다
     * ([Session.refresh]). 일시적인 오류 한 번에 충전 상태가 UNKNOWN으로 튀면 세션이 잘못 깨울 수 있어서다.
     */
    @Suppress("DEPRECATION") // 충전 포트·기어는 숨김+Deprecated API지만 호출할 수 있다(sdk-notes 3.3, 4.1)
    private fun subscribe(target: Session, capabilities: PleosCapabilities) {
        val evBattery = target.vehicle.getEvBattery()
        val gearApi = target.vehicle.getGear()
        val door = target.vehicle.getDoor()

        if (capabilities.batteryReadable) {
            val listener = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) =
                    target.onEvent(KEY_BATTERY) { it.copy(batteryPercent = value) }

                override fun onFailed(e: Exception) = Unit
            }
            target.subscribe(
                register = { evBattery.registerEvBatteryStateOfCharge(listener) },
                unregister = { evBattery.unregisterEvBatteryStateOfCharge(listener) },
            )
        }

        if (capabilities.chargePortReadable) {
            val listener = object : EvChargePortOpenListener {
                override fun onEvChargePortOpenUpdated(isOpened: Boolean?) =
                    target.onEvent(KEY_CHARGE_PORT) { it.copy(chargePortOpen = isOpened) }

                override fun onFailed(e: Exception) = Unit
            }
            target.subscribe(
                register = { evBattery.registerEvChargePortOpen(listener) },
                unregister = { evBattery.unregisterEvChargePortOpen(listener) },
            )
        }

        // 기어는 capability 조회 API가 없다. 등록·조회가 실패하면 값이 null로 남고 AAOS 기어로 대체된다
        val gearListener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = target.onEvent(KEY_GEAR) { it.copy(gearInPark = gear.inPark()) }

            override fun onFailed(e: Exception) = Unit
        }
        target.subscribe(
            register = { gearApi.registerGearSelection(gearListener) },
            unregister = { gearApi.unregisterGearSelection(gearListener) },
        )

        // 리스너는 어느 영역의 값인지 알려 주지 않으므로 영역마다 따로 만든다(sdk-notes 7절)
        capabilities.doors.forEachIndexed { index, doorTarget ->
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) =
                    target.onEvent(doorKey(index)) { it.withDoor(index, isDoorOpen(pos, doorTarget.closedPosition)) }

                override fun onFailed(e: Exception) = Unit
            }
            target.subscribe(
                register = { door.registerDoorPosition(doorTarget.area, listener) },
                unregister = { door.unregisterDoorPosition(doorTarget.area, listener) },
            )
        }
    }

    /** 모든 값을 한 번씩 조회한다. 서로 기다리지 않게 동시에 보낸다. 실패 처리는 [Session.refresh] 참고. */
    @Suppress("DEPRECATION") // 위 subscribe와 같은 이유
    private suspend fun refreshAll(target: Session, capabilities: PleosCapabilities): Unit = coroutineScope {
        val sdk = target.sdk
        val evBattery = target.vehicle.getEvBattery()
        val gearApi = target.vehicle.getGear()
        val door = target.vehicle.getDoor()

        if (capabilities.batteryReadable) {
            launch {
                target.refresh<Int?>(
                    KEY_BATTERY,
                    { sdk.request { complete, fail -> evBattery.getEvBatteryStateOfCharge(complete, fail) } },
                ) { signals, value -> signals.copy(batteryPercent = value) }
            }
        }
        if (capabilities.chargeSwitchReadable) {
            launch {
                target.refresh<Boolean?>(
                    KEY_CHARGE_SWITCH,
                    { sdk.request { complete, fail -> evBattery.getEvChargeSwitchState(complete, fail) } },
                ) { signals, value -> signals.copy(chargeSwitchOn = value) }
            }
        }
        if (capabilities.chargePortReadable) {
            launch {
                target.refresh<Boolean?>(
                    KEY_CHARGE_PORT,
                    { sdk.request { complete, fail -> evBattery.isEvChargePortOpen(complete, fail) } },
                ) { signals, value -> signals.copy(chargePortOpen = value) }
            }
        }
        launch {
            target.refresh<VehicleGear?>(
                KEY_GEAR,
                { sdk.request { complete, fail -> gearApi.getCurrentGearSelectionState(complete, fail) } },
            ) { signals, value -> signals.copy(gearInPark = value.inPark()) }
        }
        capabilities.doors.forEachIndexed { index, doorTarget ->
            launch {
                target.refresh<Int?>(
                    doorKey(index),
                    { sdk.request { complete, fail -> door.getCurrentDoorPosition(doorTarget.area, complete, fail) } },
                ) { signals, value -> signals.withDoor(index, isDoorOpen(value, doorTarget.closedPosition)) }
            }
        }
        launch { target.refreshAaos() }
        Unit
    }

    // ---- 캐빈 제어 ----

    override suspend fun captureCabinSnapshot(): CabinSnapshot = cabinMutex.withLock {
        val current = currentSession() ?: return CabinSnapshot(seat = null, climate = null)
        CabinSnapshot(
            seat = orNull { current.seat?.snapshot() },
            climate = orNull { current.climate?.snapshot() },
        )
    }

    /** 공조를 먼저 맞추고 시트를 눕힌다. 미지원 항목은 조용히 건너뛴다(오류 아님, applied = false). */
    override suspend fun applyNapPreset(preset: NapPreset): CabinApplyResult = cabinMutex.withLock {
        val current = currentSession() ?: return CabinApplyResult.NOTHING
        lastNapPreset = preset
        val errors = mutableListOf<String>()

        val climate = current.climate
        val climateApplied = climate != null &&
            runClimate(errors) { climate.apply(preset.temperatureC, preset.fanLevel) }

        val seat = current.seat
        val seatApplied = seat != null && preset.reclineLevel > 0 &&
            runSeat(errors) { seat.recline(preset.reclineLevel.coerceAtMost(MAX_RECLINE_LEVEL)) }

        CabinApplyResult(seatApplied = seatApplied, climateApplied = climateApplied, errors = errors)
    }

    override suspend fun applyWakePreset(): CabinApplyResult = cabinMutex.withLock {
        val climate = currentSession()?.climate ?: return CabinApplyResult.NOTHING
        val errors = mutableListOf<String>()
        val nap = lastNapPreset
        val applied = runClimate(errors) {
            if (nap != null) {
                climate.apply(wakeTemperature(nap.temperatureC), wakeFanLevel(nap.fanLevel))
            } else {
                climate.applyWakeFromCurrent()
            }
        }
        CabinApplyResult(seatApplied = false, climateApplied = applied, errors = errors)
    }

    /** 시트를 먼저 세우고(안전) 공조를 되돌린다. */
    override suspend fun restoreCabin(snapshot: CabinSnapshot): CabinApplyResult = cabinMutex.withLock {
        val current = currentSession()
        val errors = mutableListOf<String>()
        var seatApplied = false
        var climateApplied = false

        when (val seatSnapshot = snapshot.seat) {
            null -> Unit
            is PleosSeatSnapshot -> {
                val seat = current?.seat
                if (seat == null) {
                    errors += "${CabinApplyResult.ERROR_SEAT}: $CONTROL_UNAVAILABLE"
                } else {
                    seatApplied = runSeat(errors) { seat.restore(seatSnapshot) }
                }
            }
            else -> errors += "${CabinApplyResult.ERROR_SEAT}: $FOREIGN_SNAPSHOT"
        }

        when (val climateSnapshot = snapshot.climate) {
            null -> Unit
            is ClimateSnapshot -> {
                val climate = current?.climate
                if (climate == null) {
                    errors += "${CabinApplyResult.ERROR_CLIMATE}: $CONTROL_UNAVAILABLE"
                } else {
                    climateApplied = runClimate(errors) { climate.restore(climateSnapshot) }
                }
            }
            else -> errors += "${CabinApplyResult.ERROR_CLIMATE}: $FOREIGN_SNAPSHOT"
        }

        CabinApplyResult(seatApplied = seatApplied, climateApplied = climateApplied, errors = errors)
    }

    @Suppress("DEPRECATION") // 숨김+Deprecated API지만 호출할 수 있다(sdk-notes 4.3)
    override suspend fun rangeRemainingKm(): Int? {
        val current = currentSession() ?: return null
        val odometer = current.vehicle.getOdometer()
        val meters = current.sdk.request<Float?> { complete, fail ->
            odometer.getRangeRemainingDistance(complete, fail)
        }.valueOrNull()
        return rangeKmFromMeters(meters)
    }

    /** 세션 엔진의 안전 가드와 별개로, 도어가 열렸거나 기어가 P가 아니면 시트를 눕히지 않는다. 값을 모르면 막지 않는다. */
    private fun reclineBlockedReason(): String? = when {
        _anyDoorOpen.value == true -> "도어가 열려 있어 시트를 눕히지 않음"
        _parkState.value.gearInPark == false -> "기어가 P가 아니어서 시트를 눕히지 않음"
        else -> null
    }

    private suspend fun runSeat(errors: MutableList<String>, block: suspend () -> SeatMoveResult): Boolean = try {
        val result = block()
        result.errors.mapTo(errors) { "${CabinApplyResult.ERROR_SEAT}: $it" }
        result.moved
    } catch (e: CancellationException) {
        throw e // 시트 정지는 runSeatMove의 finally가 이미 보냈다
    } catch (t: Throwable) {
        errors += "${CabinApplyResult.ERROR_SEAT}: ${describe(t)}"
        false
    }

    private suspend fun runClimate(errors: MutableList<String>, block: suspend () -> ClimateOutcome): Boolean = try {
        val outcome = block()
        outcome.errors.mapTo(errors) { "${CabinApplyResult.ERROR_CLIMATE}: $it" }
        outcome.applied
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        errors += "${CabinApplyResult.ERROR_CLIMATE}: ${describe(t)}"
        false
    }

    private suspend fun <T> orNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }

    // ---- 공개 Flow 갱신 ----

    /** [stateLock]을 쥐고 부른다. */
    private fun publishLocked(target: Session) {
        val signals = target.signals
        val aaos = signals.aaos
        _batteryPercent.value = signals.batteryPercent
        _chargingState.value = mapChargingState(
            ChargingInputs(
                aaosChargeState = aaos.chargeState,
                chargeSwitchOn = signals.chargeSwitchOn,
                batteryPercent = signals.batteryPercent,
                aaosPortConnected = aaos.portConnected,
                sdkPortOpen = signals.chargePortOpen,
            ),
        )
        _remainingChargeMinutes.value = remainingChargeMinutes(aaos.chargeTimeRemainingSec)
        // 기어는 SDK 값이 우선이고, SDK가 못 읽을 때만 AAOS 값을 쓴다
        _parkState.value = ParkState(
            gearInPark = signals.gearInPark ?: aaos.gearInPark,
            parkingBrakeOn = aaos.parkingBrakeOn,
        )
        _anyDoorOpen.value = anyDoorOpen(signals.doorOpen.values)

        val capabilities = target.capabilities
        _capabilities.value = if (capabilities == null) {
            VehicleCapabilities.NONE
        } else {
            VehicleCapabilities(
                batteryPercent = capabilities.batteryReadable,
                chargingState = capabilities.chargeSwitchReadable || aaos.chargeStateAvailable,
                // 차량이 남은 시간을 실제로 한 번이라도 준 뒤에만 true. 속성은 있는데 늘 0인 환경에서
                // "차량이 시간을 준다"고 알리지 않기 위해서다
                remainingChargeTime = target.remainingTimeSeen,
                seatRecline = capabilities.seat != null,
                climate = capabilities.climate.controllable,
                doorState = capabilities.doors.isNotEmpty(),
                isDemo = false,
            )
        }
    }

    /** [stateLock]을 쥐고 부른다. */
    private fun resetOutputsLocked() {
        _chargingState.value = ChargingState.UNKNOWN
        _batteryPercent.value = null
        _remainingChargeMinutes.value = null
        _parkState.value = UNKNOWN_PARK_STATE
        _anyDoorOpen.value = null
        _capabilities.value = VehicleCapabilities.NONE
    }

    /** SDK와 AAOS에서 읽은 원시 값. 읽지 못한 값은 null. */
    private data class Signals(
        val batteryPercent: Int? = null,
        val chargeSwitchOn: Boolean? = null,
        val chargePortOpen: Boolean? = null,
        /** SDK 기어 기준. */
        val gearInPark: Boolean? = null,
        /** 감시하는 도어(capability 순서의 인덱스) → 열림 여부. */
        val doorOpen: Map<Int, Boolean?> = emptyMap(),
        val aaos: AaosSignals = AaosSignals.NONE,
    ) {
        fun withDoor(index: Int, open: Boolean?): Signals = copy(doorOpen = doorOpen + (index to open))
    }

    /** start()부터 stop()까지의 한 연결. stop() 뒤 다시 start() 하면 새로 만든다. */
    private inner class Session(val vehicle: Vehicle) {
        val sdk = SdkCalls()
        val job: Job = SupervisorJob(this@PleosVehicleGateway.scope.coroutineContext[Job])
        val scope: CoroutineScope = CoroutineScope(this@PleosVehicleGateway.scope.coroutineContext + job)
        val aaos = CarPropertyAdapter(appContext)

        @Volatile
        var capabilities: PleosCapabilities? = null

        @Volatile
        var seat: PleosSeatControl? = null

        @Volatile
        var climate: ClimateController? = null

        // 아래는 stateLock으로 지킨다
        var signals = Signals()
        var remainingTimeSeen = false
        var closed = false
        private val unregisters = mutableListOf<() -> Unit>()

        /**
         * 값마다 리스너 값과 조회 결과의 순서를 가린다. 조회를 시작한 뒤 리스너가 더 새 값을 줬으면 조회 결과를 버린다.
         * 판정과 [signals] 쓰기를 [stateLock] 안에서 한 번에 해야 오래된 조회 값(P·닫힘)이 방금 온 리스너 값(D·열림)을
         * 덮지 않는다.
         */
        private val freshness = LatestValueGuard(stateLock)

        /** 값마다 조회가 연달아 실패한 횟수. */
        private val readFailures = ConcurrentHashMap<String, AtomicInteger>()

        fun attach(probed: PleosCapabilities) {
            seat = probed.seat?.let { PleosSeatControl(vehicle.getSeat(), sdk, it, reclineBlockedReason = { reclineBlockedReason() }) }
            climate = if (probed.climate.controllable) {
                ClimateController(PleosClimatePort(vehicle.getHVAC(), sdk, probed.climate))
            } else {
                null
            }
            capabilities = probed
            update { it.copy(doorOpen = probed.doors.indices.associateWith { null }) }
        }

        fun ensureOpen() {
            if (synchronized(stateLock) { closed }) throw PleosUnavailableException("시작하는 도중에 stop()이 호출됨")
        }

        /** SDK·AAOS 콜백 스레드에서 불린다. MutableStateFlow에 쓰는 것 말고는 하지 않는다. */
        fun update(transform: (Signals) -> Signals) {
            synchronized(stateLock) {
                if (closed) return
                signals = transform(signals)
                if ((signals.aaos.chargeTimeRemainingSec ?: 0) > 0) remainingTimeSeen = true
                publishLocked(this)
                // Flow 수집자가 같은 스레드에서 곧바로 실행돼 그 안에서 stop()을 부른 경우, 방금 쓴 값을 다시 비운다
                if (closed && session == null) resetOutputsLocked()
            }
        }

        /** 리스너 값. 언제나 반영한다(가장 새 값). */
        fun onEvent(key: String, transform: (Signals) -> Signals) {
            freshness.push(key) {
                failuresOf(key).set(0)
                update(transform)
            }
        }

        /**
         * 조회 결과로 값을 채운다.
         * - 조회를 시작한 뒤 리스너가 울렸으면 리스너 값이 더 새것이므로 결과를 버린다(판정과 쓰기는 한 락 안에서).
         * - 조회가 실패·타임아웃이면 직전 값을 한 번은 유지하고, [UNREADABLE_AFTER_FAILURES]번 연달아 실패하면
         *   "읽지 못함"(null)으로 바꾼다. 처음부터 못 읽은 값은 원래 null이다.
         */
        suspend fun <T> refresh(
            key: String,
            get: suspend () -> SdkOutcome<T>,
            transform: (Signals, T?) -> Signals,
        ) {
            val ticket = freshness.beginRead(key)
            val outcome = get()
            freshness.completeRead(ticket) {
                if (outcome is SdkOutcome.Success) {
                    failuresOf(key).set(0)
                    update { transform(it, outcome.value) }
                } else if (failuresOf(key).incrementAndGet() >= UNREADABLE_AFTER_FAILURES) {
                    update { transform(it, null) }
                }
            }
        }

        /**
         * AAOS 값을 다시 읽어 반영한다. 폴링·변경 콜백·권한 재확인이 동시에 읽어도 나중에 시작한 읽기가 이긴다
         * (먼저 시작한 읽기가 늦게 끝나 오래된 기어·주차 브레이크 값으로 덮지 않는다).
         *
         * @param reconnect true면 읽기 전에 쓸 수 있는 속성을 다시 확인한다(권한이 새로 부여됐을 때)
         */
        suspend fun refreshAaos(reconnect: Boolean = false) {
            val ticket = freshness.beginRead(KEY_AAOS)
            val fresh = withContext(Dispatchers.IO) {
                if (reconnect) aaos.refresh()
                aaos.read()
            }
            freshness.completeRead(ticket) { update { it.copy(aaos = fresh) } }
        }

        /**
         * 리스너를 등록하고 해제 방법을 기억한다. 권한이 없으면 register가 SecurityException을 던지는데(sdk-notes 9.4)
         * 그 값은 조회로만 갱신된다.
         */
        fun subscribe(register: () -> Unit, unregister: () -> Unit) {
            try {
                register()
            } catch (t: Throwable) {
                return
            }
            val alreadyClosed = synchronized(stateLock) {
                if (!closed) unregisters += unregister
                closed
            }
            if (alreadyClosed) {
                try {
                    unregister()
                } catch (t: Throwable) {
                    // release()가 정리한다
                }
            }
        }

        /** [stateLock]을 쥐고 부른다. */
        fun takeUnregisters(): List<() -> Unit> {
            val taken = unregisters.toList()
            unregisters.clear()
            return taken
        }

        fun launchBackgroundJobs(probed: PleosCapabilities) {
            scope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    try {
                        refreshAll(this@Session, probed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // 한 번 실패해도 폴링은 계속한다
                    }
                }
            }
            // AAOS 콜백은 "바뀌었다"는 신호만 준다. 값은 여기서 다시 읽는다
            scope.launch {
                aaos.changes.drop(1).collect { refreshAaos() }
            }
        }

        private fun failuresOf(key: String): AtomicInteger = readFailures.getOrPut(key) { AtomicInteger(0) }
    }

    private companion object {
        /** 근거: docs/sdk-notes.md 1.3 */
        const val VEHICLE_SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"

        /**
         * 첫 capability 응답을 기다리는 시간. 실제 에뮬레이터에서 VehicleService가 콜드 스타트되어 onBind까지 약 13초,
         * 그 뒤에도 서비스가 준비될 때까지 요청이 큐에 머물렀다. 서비스 패키지가 없는 기기는 [requireVehicleService]에서
         * 바로 실패하므로 이 값 때문에 데모 대체가 늦어지지 않는다.
         */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** 충전 스위치에는 리스너가 없어 이 주기로 조회한다. 다른 값도 같은 주기로 다시 읽어 리스너를 보완한다. */
        const val POLL_INTERVAL_MS = 2_000L

        /** 조회가 이만큼 연달아 실패하면 그 값을 "읽지 못함"으로 본다. */
        const val UNREADABLE_AFTER_FAILURES = 2
        const val RELEASE_WAIT_MS = 2_000L
        const val RELEASE_CHECK_MS = 50L
        const val MAX_RECLINE_LEVEL = 100

        const val KEY_BATTERY = "battery"
        const val KEY_CHARGE_SWITCH = "chargeSwitch"
        const val KEY_CHARGE_PORT = "chargePort"
        const val KEY_GEAR = "gear"
        const val KEY_AAOS = "aaos"
        const val CONTROL_UNAVAILABLE = "차량 제어를 쓸 수 없음"
        const val FOREIGN_SNAPSHOT = "이 게이트웨이가 만든 스냅샷이 아님"

        val UNKNOWN_PARK_STATE = ParkState(gearInPark = null, parkingBrakeOn = null)

        fun doorKey(index: Int): String = "door$index"

        fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

        /** P면 true, 다른 단이면 false, 모르면(null·GEAR_UNKNOWN) null. */
        fun VehicleGear?.inPark(): Boolean? = when (this) {
            null, VehicleGear.GEAR_UNKNOWN -> null
            VehicleGear.GEAR_PARK -> true
            else -> false
        }
    }
}
