package com.chargenap.app.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 가상 충전 시나리오.
 *
 * @param autoStartChargingAfterMillis [FakeVehicleGateway.start] 뒤 이 시간이 지나면 충전을 시작한다.
 *   null이면 [FakeVehicleGateway.startCharging]을 직접 불러야 한다(단위 테스트용).
 * @param reportsRemainingTime true면 SDK가 남은 충전 시간을 주는 차량을 흉내 낸다.
 * @param restartWhenNearlyFull true면 목표 잔량까지 [RESTART_MARGIN_PERCENT]% 이내로 남았을 때
 *   [FakeVehicleGateway.startCharging]이 잔량을 [startPercent]로 되돌려 새 충전을 시작한다.
 *   데모를 앱 재시작 없이 다시 보여 주기 위한 것이다.
 */
data class FakeScenario(
    val startPercent: Int = 52,
    val percentPerMinute: Double = 1.0,
    val targetPercent: Int = 80,
    val autoStartChargingAfterMillis: Long? = 3_000,
    val reportsRemainingTime: Boolean = false,
    val rangeKmPerPercent: Double = 4.5,
    val restartWhenNearlyFull: Boolean = false,
) {
    companion object {
        const val RESTART_MARGIN_PERCENT = 15
    }
}

data class FakeClimate(val temperatureC: Float, val fanLevel: Int)

/**
 * 스크립트로 충전을 흉내 내는 가상 차량. 데모 모드와 단위 테스트에 쓴다.
 *
 * 시간은 [scope]의 디스패처를 따른다. 단위 테스트에서 테스트 스코프를 넘기면 가상 시간으로 흐른다.
 * [timeScale]배로 빠르게 충전된다(60이면 20분이 20초).
 */
class FakeVehicleGateway(
    private val scenario: FakeScenario = FakeScenario(),
    private val timeScale: Double = 60.0,
    private val scope: CoroutineScope,
) : VehicleGateway, DemoVehicleControls {

    private val _chargingState = MutableStateFlow(ChargingState.NOT_CONNECTED)
    private val _batteryPercent = MutableStateFlow<Int?>(scenario.startPercent)
    private val _remainingChargeMinutes = MutableStateFlow<Int?>(null)
    private val _parkState = MutableStateFlow(ParkState(gearInPark = true, parkingBrakeOn = true))
    private val _anyDoorOpen = MutableStateFlow<Boolean?>(false)
    private val _capabilities = MutableStateFlow(
        VehicleCapabilities(
            batteryPercent = true,
            chargingState = true,
            remainingChargeTime = scenario.reportsRemainingTime,
            seatRecline = true,
            climate = true,
            doorState = true,
            isDemo = true,
        ),
    )
    private val _seatReclineLevel = MutableStateFlow(0)
    private val _climate = MutableStateFlow(FakeClimate(temperatureC = 21f, fanLevel = 3))

    override val chargingState: StateFlow<ChargingState> = _chargingState.asStateFlow()
    override val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()
    override val remainingChargeMinutes: StateFlow<Int?> = _remainingChargeMinutes.asStateFlow()
    override val parkState: StateFlow<ParkState> = _parkState.asStateFlow()
    override val anyDoorOpen: StateFlow<Boolean?> = _anyDoorOpen.asStateFlow()
    override val capabilities: StateFlow<VehicleCapabilities> = _capabilities.asStateFlow()

    /** 현재 가상 등받이 위치. 0이 원위치. */
    val seatReclineLevel: StateFlow<Int> = _seatReclineLevel.asStateFlow()
    val climate: StateFlow<FakeClimate> = _climate.asStateFlow()

    /** 테스트에서 true로 두면 해당 제어가 실패한다. */
    @Volatile var failSeat: Boolean = false
    @Volatile var failClimate: Boolean = false
    @Volatile var failRestore: Boolean = false

    private val log = mutableListOf<String>()

    /** 받은 캐빈 명령 기록. 예: "snapshot", "applyNap(60,23.0,2)", "applyWake", "restore" */
    val commandLog: List<String> get() = synchronized(log) { log.toList() }

    private var job: Job? = null
    private var fraction = 0.0
    private var doorOpen = false
    private var doorReadable = true
    private var gearInPark = true
    private var gearReadable = true
    private var lastNapPreset: NapPreset? = null

    override suspend fun start() {
        if (job != null) return
        job = scope.launch {
            scenario.autoStartChargingAfterMillis?.let {
                delay(it)
                startCharging()
            }
            while (isActive) {
                delay(STEP_MILLIS)
                tick()
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun tick() {
        if (_chargingState.value != ChargingState.CHARGING) return
        val current = _batteryPercent.value ?: return
        fraction += scenario.percentPerMinute * timeScale * STEP_MILLIS / 60_000.0
        var next = current
        while (fraction >= 1.0) {
            next += 1
            fraction -= 1.0
        }
        if (next >= scenario.targetPercent) {
            _batteryPercent.value = scenario.targetPercent
            fraction = 0.0
            _chargingState.value = ChargingState.COMPLETE
        } else if (next != current) {
            _batteryPercent.value = next
        }
        publishRemaining()
    }

    private fun publishRemaining() {
        if (!scenario.reportsRemainingTime) return
        val percent = _batteryPercent.value
        _remainingChargeMinutes.value = when {
            _chargingState.value != ChargingState.CHARGING || percent == null -> null
            scenario.percentPerMinute <= 0.0 -> null
            else -> ceil((scenario.targetPercent - percent - fraction) / scenario.percentPerMinute)
                .toInt().coerceAtLeast(0)
        }
    }

    // ---- DemoVehicleControls ----

    override fun startCharging() {
        if (scenario.restartWhenNearlyFull && _chargingState.value != ChargingState.CHARGING) {
            val current = _batteryPercent.value
            if (current == null || current >= scenario.targetPercent - FakeScenario.RESTART_MARGIN_PERCENT) {
                _batteryPercent.value = scenario.startPercent
                fraction = 0.0
            }
        }
        val percent = _batteryPercent.value
        _chargingState.value =
            if (percent != null && percent >= scenario.targetPercent) ChargingState.COMPLETE else ChargingState.CHARGING
        publishRemaining()
    }

    override fun interruptCharging() {
        if (_chargingState.value == ChargingState.CHARGING) _chargingState.value = ChargingState.CONNECTED_IDLE
        publishRemaining()
    }

    override fun completeCharging() {
        _batteryPercent.value = scenario.targetPercent
        fraction = 0.0
        _chargingState.value = ChargingState.COMPLETE
        publishRemaining()
    }

    override fun unplug() {
        _chargingState.value = ChargingState.NOT_CONNECTED
        publishRemaining()
    }

    override fun setDoorOpen(open: Boolean) {
        doorOpen = open
        publishDoor()
    }

    override fun setGearInPark(inPark: Boolean) {
        gearInPark = inPark
        publishGear()
    }

    // ---- 테스트 훅 ----

    fun plugIn() {
        _chargingState.value = ChargingState.CONNECTED_IDLE
        publishRemaining()
    }

    fun setBatteryPercent(percent: Int) {
        _batteryPercent.value = percent
        fraction = 0.0
        publishRemaining()
    }

    /** SDK가 주는 남은 충전 시간을 직접 지정한다. */
    fun setRemainingChargeMinutes(minutes: Int?) {
        _remainingChargeMinutes.value = minutes
    }

    fun setParkingBrake(on: Boolean?) {
        _parkState.update { it.copy(parkingBrakeOn = on) }
    }

    /** false면 기어 값을 읽지 못하는 상황(null)을 흉내 낸다. */
    fun setGearReadable(readable: Boolean) {
        gearReadable = readable
        publishGear()
    }

    /** false면 도어 값을 읽지 못하는 차종(null)을 흉내 낸다. */
    fun setDoorReadable(readable: Boolean) {
        doorReadable = readable
        publishDoor()
    }

    fun updateCapabilities(transform: (VehicleCapabilities) -> VehicleCapabilities) {
        _capabilities.update(transform)
    }

    private fun publishGear() {
        _parkState.update { it.copy(gearInPark = if (gearReadable) gearInPark else null) }
    }

    private fun publishDoor() {
        _anyDoorOpen.value = if (doorReadable) doorOpen else null
    }

    // ---- 캐빈 제어 ----

    override suspend fun captureCabinSnapshot(): CabinSnapshot {
        record("snapshot")
        val caps = _capabilities.value
        return CabinSnapshot(
            seat = if (caps.seatRecline) _seatReclineLevel.value else null,
            climate = if (caps.climate) _climate.value else null,
        )
    }

    override suspend fun applyNapPreset(preset: NapPreset): CabinApplyResult {
        record("applyNap(${preset.reclineLevel},${preset.temperatureC},${preset.fanLevel})")
        lastNapPreset = preset
        val caps = _capabilities.value
        val errors = mutableListOf<String>()

        var climateApplied = false
        when {
            !caps.climate -> Unit
            failClimate -> errors += "${CabinApplyResult.ERROR_CLIMATE}: 가상 공조 오류"
            else -> {
                _climate.value = FakeClimate(preset.temperatureC, preset.fanLevel.coerceIn(1, 5))
                climateApplied = true
            }
        }

        var seatApplied = false
        when {
            preset.reclineLevel <= 0 || !caps.seatRecline -> Unit
            failSeat -> errors += "${CabinApplyResult.ERROR_SEAT}: 가상 시트 오류"
            else -> {
                moveSeatTo(preset.reclineLevel.coerceIn(0, 100))
                seatApplied = true
            }
        }
        return CabinApplyResult(seatApplied, climateApplied, errors)
    }

    override suspend fun applyWakePreset(): CabinApplyResult {
        record("applyWake")
        if (!_capabilities.value.climate) return CabinApplyResult.NOTHING
        if (failClimate) {
            return CabinApplyResult(false, false, listOf("${CabinApplyResult.ERROR_CLIMATE}: 가상 공조 오류"))
        }
        val base = lastNapPreset?.let { FakeClimate(it.temperatureC, it.fanLevel) } ?: _climate.value
        _climate.value = FakeClimate(base.temperatureC - 2f, (base.fanLevel + 2).coerceIn(1, 5))
        return CabinApplyResult(seatApplied = false, climateApplied = true, errors = emptyList())
    }

    override suspend fun restoreCabin(snapshot: CabinSnapshot): CabinApplyResult {
        record("restore")
        val errors = mutableListOf<String>()
        var climateApplied = false
        var seatApplied = false

        (snapshot.climate as? FakeClimate)?.let {
            if (failRestore) {
                errors += "${CabinApplyResult.ERROR_CLIMATE}: 가상 공조 복구 오류"
            } else {
                _climate.value = it
                climateApplied = true
            }
        }
        (snapshot.seat as? Int)?.let {
            if (failRestore) {
                errors += "${CabinApplyResult.ERROR_SEAT}: 가상 시트 복구 오류"
            } else {
                moveSeatTo(it)
                seatApplied = true
            }
        }
        return CabinApplyResult(seatApplied, climateApplied, errors)
    }

    override suspend fun rangeRemainingKm(): Int? =
        _batteryPercent.value?.let { (it * scenario.rangeKmPerPercent).toInt() }

    /** 실제 시트처럼 조금씩 움직인다. 취소되면 그 자리에서 멈춘다. */
    private suspend fun moveSeatTo(target: Int) {
        while (_seatReclineLevel.value != target) {
            delay(SEAT_STEP_MILLIS)
            val current = _seatReclineLevel.value
            _seatReclineLevel.value =
                if (target > current) minOf(target, current + SEAT_STEP_LEVEL) else maxOf(target, current - SEAT_STEP_LEVEL)
        }
    }

    private fun record(command: String) {
        synchronized(log) { log += command }
    }

    private companion object {
        const val STEP_MILLIS = 1_000L
        const val SEAT_STEP_MILLIS = 250L
        const val SEAT_STEP_LEVEL = 20
    }
}
