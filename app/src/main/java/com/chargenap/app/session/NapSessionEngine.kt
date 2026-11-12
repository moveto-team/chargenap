package com.chargenap.app.session

import com.chargenap.app.vehicle.CabinApplyResult
import com.chargenap.app.vehicle.CabinSnapshot
import com.chargenap.app.vehicle.ChargingState
import com.chargenap.app.vehicle.NapPreset
import com.chargenap.app.vehicle.ParkState
import com.chargenap.app.vehicle.VehicleCapabilities
import com.chargenap.app.vehicle.VehicleGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 파워냅 세션 상태 머신 (킥오프 4.2).
 *
 * 구조
 * - 사용자 이벤트 함수와 차량 값 변화, 틱은 모두 하나의 이벤트 큐로 들어가고 **루프 코루틴 하나**가 순서대로
 *   처리한다. 내부 상태는 그 코루틴만 건드리므로 경쟁 상태가 없다. 이벤트 함수는 큐에 넣고 바로 돌아온다.
 * - 오래 걸리는 차량 제어(프리셋 적용, 복구)는 루프의 자식 작업으로 돌리고 끝나면 결과를 큐에 넣는다.
 *   그동안에도 루프는 도어·기어 변화를 처리하고, 필요하면 그 작업을 취소한다(시트 정지).
 * - 모든 대기는 `clock.nowMillis()` 비교 + `delay(tickMillis)` 폴링이다. 데모 배속 시계에서도 그대로 동작한다.
 *
 * [start]를 부르기 전과 [stop]/[shutdown] 뒤에는 이벤트 함수가 아무 일도 하지 않는다.
 * 게이트웨이의 `start()`/`stop()`은 이 엔진이 부르지 않는다.
 */
class NapSessionEngine(
    private val gateway: VehicleGateway,
    val clock: Clock,
    private val scope: CoroutineScope,
    private val config: StateFlow<SessionConfig>,
    private val tickMillis: Long = 1_000,
    private val timings: SessionTimings = SessionTimings(),
) {
    private val _state = MutableStateFlow<NapSessionState>(NapSessionState.Idle)
    val state: StateFlow<NapSessionState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<SessionNotice>(
        extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notices: SharedFlow<SessionNotice> = _notices.asSharedFlow()

    private val _completedSessions = MutableSharedFlow<SessionSummary>(
        extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** `Briefing`을 확인할 때 한 번 방출한다. 중단된 세션과 수면에 들어가지 못한 세션은 방출하지 않는다. */
    val completedSessions: SharedFlow<SessionSummary> = _completedSessions.asSharedFlow()

    private val _estimatedRemainingChargeMinutes = MutableStateFlow<Int?>(null)

    /** 목표 잔량까지 남은 충전 시간(분). 충전 중이 아니거나 추정할 수 없으면 null. */
    val estimatedRemainingChargeMinutes: StateFlow<Int?> = _estimatedRemainingChargeMinutes.asStateFlow()

    private val _startBlockers = MutableStateFlow(
        SafetyGuard.evaluateStart(gateway.parkState.value, gateway.chargingState.value, gateway.anyDoorOpen.value),
    )

    /** 지금 시작을 요청하면 받게 될 점검 결과. `blocksStart`면 시작할 수 없다. */
    val startBlockers: StateFlow<SafetyReport> = _startBlockers.asStateFlow()

    // ---- 수명 주기 (lock으로 보호) ----
    private val lock = Any()
    private var events: Channel<Event>? = null
    private var loopJob: Job? = null

    // ---- 아래는 루프 코루틴만 읽고 쓴다 ----
    private val estimator = ChargeTimeEstimator()
    private var session: Session? = null
    private var offerSuppressed = false
    private var chargingActive = false
    private var chargeEndAtMillis: Long? = null
    private var remainingMinutes: Int? = null
    private var work: Job? = null
    private var workId = 0
    private lateinit var loopScope: CoroutineScope
    private lateinit var loopEvents: Channel<Event>

    // ================================================================ 공개 API

    /** 차량 값 구독과 틱을 시작한다. 이미 돌고 있으면 아무 일도 하지 않는다. */
    fun start() {
        synchronized(lock) {
            if (events != null) return
            val channel = Channel<Event>(Channel.UNLIMITED)
            val previous = loopJob
            events = channel
            loopJob = scope.launch {
                previous?.join()
                try {
                    runLoop(channel)
                } finally {
                    synchronized(lock) { if (events === channel) events = null }
                }
            }
        }
    }

    /** [shutdown]과 같은 일을 하되 끝나기를 기다리지 않는다. 진행 중 세션이 있으면 캐빈을 복구한 뒤 멈춘다. */
    fun stop() {
        requestStop()
    }

    /** 진행 중 세션이 있으면 캐빈을 복구하고 `Idle`로 만든 뒤 구독을 끝낸다. 그래프를 교체하기 전에 부른다. */
    suspend fun shutdown() {
        requestStop()?.join()
    }

    /** `Idle`/`Offer` → `SafetyCheck`(또는 곧장 `Preparing`). [minutes]는 5..25로 자른다. null이면 추천 값을 쓴다. */
    fun requestStart(minutes: Int? = null) = post(Event.RequestStart(minutes))

    /** `Offer` → `Idle`. 이번 충전 동안 다시 제안하지 않는다. */
    fun declineOffer() = post(Event.DeclineOffer)

    /** `SafetyCheck` → `Idle`. `Preparing`/`Napping`에서는 [requestWake]와 같다. */
    fun cancel() = post(Event.Cancel)

    /**
     * 뒷좌석 질문의 답. true("없어요")만 시트 이동을 허용한다. false("있어요")와 null(무응답)은 시트를 그대로 둔다.
     * `SafetyCheck.rearSeatQuestionPending`이 true일 때만 받는다. 답한 뒤 도어가 열리면 무효가 되고 다시 묻는다.
     */
    fun answerRearSeat(clear: Boolean?) = post(Event.AnswerRearSeat(clear))

    /** 도어 값을 읽지 못할 때의 사용자 확인. 도어 열림으로 중단된 상태에서는 "닫았으니 복구해도 된다"는 확인이다. */
    fun confirmDoorsClosed() = post(Event.ConfirmDoorsClosed)

    fun requestExtend(minutes: Int = 5) = post(Event.RequestExtend(minutes))

    fun requestWake() = post(Event.RequestWake)

    fun acknowledgeBriefing() = post(Event.AcknowledgeBriefing)

    fun acknowledgeAbort() = post(Event.AcknowledgeAbort)

    // ================================================================ 루프

    private fun post(event: Event) {
        synchronized(lock) { events }?.trySend(event)
    }

    private fun requestStop(): Job? = synchronized(lock) {
        events?.trySend(Event.Shutdown)
        events = null
        loopJob
    }

    private suspend fun runLoop(channel: Channel<Event>) = coroutineScope {
        loopScope = this
        loopEvents = channel
        estimator.reset()
        chargingActive = false
        offerSuppressed = false

        val feeders = launch {
            launch {
                merge<Any?>(
                    gateway.chargingState,
                    gateway.batteryPercent,
                    gateway.remainingChargeMinutes,
                    gateway.parkState,
                    gateway.anyDoorOpen,
                    gateway.capabilities,
                    config,
                ).collect { channel.send(Event.Evaluate) }
            }
            while (true) {
                delay(tickMillis)
                channel.send(Event.Evaluate)
            }
        }
        try {
            for (event in channel) {
                if (event === Event.Shutdown) {
                    shutdownNow()
                    break
                }
                handle(event)
            }
        } finally {
            feeders.cancel()
            work?.cancel()
        }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            Event.Evaluate -> evaluate()
            is Event.RequestStart -> onRequestStart(event.minutes)
            Event.DeclineOffer -> onDeclineOffer()
            Event.Cancel -> onCancel()
            is Event.AnswerRearSeat -> onAnswerRearSeat(event.clear)
            Event.ConfirmDoorsClosed -> onConfirmDoorsClosed()
            is Event.RequestExtend -> onRequestExtend(event.minutes)
            Event.RequestWake -> onRequestWake()
            Event.AcknowledgeBriefing -> finishBriefing()
            Event.AcknowledgeAbort -> onAcknowledgeAbort()
            is Event.PrepareDone -> onPrepareDone(event)
            is Event.WakeDone -> onWakeDone(event)
            is Event.RestoreDone -> onRestoreDone(event)
            Event.Shutdown -> Unit
        }
    }

    /** 차량 값을 읽고 추정치·시작 점검·재제안 억제 같은 파생 값을 갱신한다. */
    private fun refresh(): Vehicle {
        val now = clock.nowMillis()
        val v = Vehicle(
            now = now,
            charging = gateway.chargingState.value,
            park = gateway.parkState.value,
            doorOpen = gateway.anyDoorOpen.value,
            batteryPercent = gateway.batteryPercent.value,
            sdkRemainingMinutes = gateway.remainingChargeMinutes.value,
            capabilities = gateway.capabilities.value,
        )

        // 충전 상태를 잠깐 못 읽는 것(UNKNOWN)은 충전이 끊긴 것으로 보지 않는다.
        val active = when (v.charging) {
            ChargingState.CHARGING -> true
            ChargingState.UNKNOWN -> chargingActive
            else -> false
        }
        if (active && !chargingActive) estimator.reset()
        chargingActive = active
        if (v.charging == ChargingState.CHARGING) {
            v.batteryPercent?.let { estimator.addSample(now, it) }
            estimator.setSdkRemaining(now, v.sdkRemainingMinutes)
            session?.sawCharging = true
        }
        val target = config.value.targetPercent
        chargeEndAtMillis = if (active) estimator.chargeEndAtMillis(now, target) else null
        remainingMinutes = if (active) estimator.remainingMinutes(now, target) else null
        _estimatedRemainingChargeMinutes.value = remainingMinutes

        _startBlockers.value = SafetyGuard.evaluateStart(v.park, v.charging, v.doorOpen)

        // 충전이 확실히 끊기면 다음 충전에서 다시 제안할 수 있다.
        when (v.charging) {
            ChargingState.NOT_CONNECTED, ChargingState.CONNECTED_IDLE, ChargingState.COMPLETE -> offerSuppressed = false
            else -> Unit
        }
        return v
    }

    private suspend fun evaluate() {
        val v = refresh()
        when (val current = _state.value) {
            NapSessionState.Idle -> evaluateIdle(v)
            is NapSessionState.Offer -> evaluateOffer(v)
            is NapSessionState.SafetyCheck -> evaluateSafetyCheck(v)
            is NapSessionState.Preparing, is NapSessionState.Waking -> abortIfUnsafe(v)
            is NapSessionState.Napping -> if (!abortIfUnsafe(v)) evaluateNapping(v, current)
            // 브리핑을 확인하지 않고 출발하면 확인한 것으로 보고 기록한다.
            is NapSessionState.Briefing -> if (v.park.gearInPark == false) finishBriefing()
            is NapSessionState.Aborted -> evaluateAborted(v, current)
        }
    }

    // ================================================================ 제안

    private fun offerConditionsMet(v: Vehicle): Boolean =
        v.charging == ChargingState.CHARGING && v.park.gearInPark == true

    private fun evaluateIdle(v: Vehicle) {
        if (offerSuppressed || !offerConditionsMet(v)) return
        val recommendation = ChargeTimeEstimator.recommend(remainingMinutes)
        if (recommendation != Recommendation.None) _state.value = NapSessionState.Offer(recommendation)
    }

    private fun evaluateOffer(v: Vehicle) {
        val recommendation =
            if (offerConditionsMet(v)) ChargeTimeEstimator.recommend(remainingMinutes) else Recommendation.None
        _state.value =
            if (recommendation == Recommendation.None) NapSessionState.Idle else NapSessionState.Offer(recommendation)
    }

    private fun onDeclineOffer() {
        if (_state.value !is NapSessionState.Offer) return
        offerSuppressed = true
        _state.value = NapSessionState.Idle
    }

    // ================================================================ 시작 요청과 안전 확인

    private fun onRequestStart(requestedMinutes: Int?) {
        val current = _state.value
        if (current != NapSessionState.Idle && current !is NapSessionState.Offer) return
        val v = refresh()
        val report = _startBlockers.value
        if (report.blocksStart) {
            notify(SessionNotice.StartBlocked(report))
            _state.value = NapSessionState.Idle
            return
        }

        val (minutes, mode) = resolvePlan(requestedMinutes, current)
        val s = Session(mode, minutes)
        s.batteryStartPercent = v.batteryPercent
        s.sawCharging = v.charging == ChargingState.CHARGING
        s.rearSeatPending = SafetyGuard.shouldAskRearSeat(config.value, v.capabilities, mode)
        session = s
        evaluateSafetyCheck(v)
    }

    /** 세션 길이와 모드. 10분 미만은 호흡 모드(시트를 움직이지 않는다)다. */
    private fun resolvePlan(requestedMinutes: Int?, current: NapSessionState): Pair<Int, SessionMode> {
        if (requestedMinutes != null) {
            val minutes = requestedMinutes.coerceIn(MIN_SESSION_MINUTES, MAX_SESSION_MINUTES)
            return minutes to if (minutes < MIN_NAP_MINUTES) SessionMode.BREATHING else SessionMode.NAP
        }
        val recommendation = (current as? NapSessionState.Offer)?.recommendation
            ?: ChargeTimeEstimator.recommend(remainingMinutes)
        return when (recommendation) {
            is Recommendation.Nap -> recommendation.minutes to SessionMode.NAP
            is Recommendation.Breathing -> recommendation.minutes to SessionMode.BREATHING
            is Recommendation.ChooseDuration -> recommendation.options.min() to SessionMode.NAP
            Recommendation.None -> ChargeTimeEstimator.BREATHING_MINUTES to SessionMode.BREATHING
        }
    }

    private fun evaluateSafetyCheck(v: Vehicle) {
        val s = session ?: return
        val report = _startBlockers.value
        if (report.blocksStart) {
            session = null
            notify(SessionNotice.StartBlocked(report))
            _state.value = NapSessionState.Idle
            return
        }
        if (v.doorOpen == true) revokeConfirmations(s, v)
        val doorConfirmPending = report.doors == CheckResult.NEEDS_USER_CONFIRM && !s.doorConfirmed
        if (!report.mustWait && !doorConfirmPending && !s.rearSeatPending) {
            startPreparing(s, v)
        } else {
            // 뒷좌석 질문은 도어·주차 브레이크 대기가 풀린 뒤에만 띄운다. 문이 열려 있는 동안 받은 답은 믿을 수 없다.
            val rearSeatQuestion = s.rearSeatPending && !report.mustWait
            _state.value =
                NapSessionState.SafetyCheck(s.plannedMinutes, s.mode, report, rearSeatQuestion, doorConfirmPending)
        }
    }

    /**
     * 확인을 받은 뒤 도어가 열리면 그사이 누가 뒷좌석에 타거나 짐을 실었을 수 있다.
     * 시트 이동 동의와 "문 닫았어요" 확인을 무효로 하고, 도어가 닫히면 다시 묻는다.
     */
    private fun revokeConfirmations(s: Session, v: Vehicle) {
        s.seatConsent = false
        s.doorConfirmed = false
        s.rearSeatPending = SafetyGuard.shouldAskRearSeat(config.value, v.capabilities, s.mode)
    }

    private fun onAnswerRearSeat(clear: Boolean?) {
        val s = session ?: return
        val current = _state.value as? NapSessionState.SafetyCheck ?: return
        // 질문을 띄운 상태에서 온 답만 받는다. 대기 중(질문을 내린 동안)에 온 답은 버린다.
        if (!current.rearSeatQuestionPending || !s.rearSeatPending) return
        s.rearSeatPending = false
        s.seatConsent = clear == true
        evaluateSafetyCheck(refresh())      // 그사이 도어가 열렸으면 여기서 다시 무효가 된다
    }

    private fun onConfirmDoorsClosed() {
        when (val current = _state.value) {
            is NapSessionState.SafetyCheck -> {
                val s = session ?: return
                if (!current.doorConfirmPending) return
                s.doorConfirmed = true
                evaluateSafetyCheck(refresh())
            }
            is NapSessionState.Aborted -> confirmAbortRestore(current)
            else -> Unit
        }
    }

    private suspend fun onCancel() {
        when (_state.value) {
            is NapSessionState.Offer -> onDeclineOffer()
            else -> onRequestWake()
        }
    }

    // ================================================================ 캐빈 준비

    private fun startPreparing(s: Session, v: Vehicle) {
        val cfg = config.value
        val seatAllowed = s.seatConsent && SafetyGuard.seatControlAvailable(cfg, v.capabilities, s.mode)
        val preset = SafetyGuard.napPreset(cfg.preset, seatAllowed)
        _state.value = NapSessionState.Preparing(s.plannedMinutes, s.mode, seatAllowed)
        launchWork { id ->
            val result = prepareCabin(s, preset)
            loopEvents.send(Event.PrepareDone(id, result))
        }
    }

    /** 스냅샷을 먼저 저장한 뒤에만 프리셋을 적용한다. 되돌릴 수 없는 것은 움직이지 않는다. */
    private suspend fun prepareCabin(s: Session, preset: NapPreset): CabinApplyResult {
        val snapshot = try {
            gateway.captureCabinSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(seat = preset.reclineLevel > 0, climate = true, cause = e)
        }
        s.snapshot = snapshot
        val effective = if (snapshot.seat == null) preset.copy(reclineLevel = 0) else preset
        return guarded(seat = effective.reclineLevel > 0, climate = true) { gateway.applyNapPreset(effective) }
    }

    private suspend fun onPrepareDone(event: Event.PrepareDone) {
        if (event.workId != workId || _state.value !is NapSessionState.Preparing) return
        val s = session ?: return
        work = null
        val now = clock.nowMillis()
        s.cabin = event.result
        s.failures += event.result.errors
        s.seatMoved = event.result.seatApplied
        s.napStarted = true
        s.startedAtMillis = now
        s.timerEndAtMillis = now + s.plannedMinutes * MINUTE_MILLIS
        s.batteryStartPercent = gateway.batteryPercent.value
        refresh()
        val wakeAt = wakeAtMillis(s)
        s.announcedWakeAtMillis = wakeAt
        _state.value = NapSessionState.Napping(
            startedAtMillis = now,
            wakeAtMillis = wakeAt,
            plannedMinutes = s.plannedMinutes,
            mode = s.mode,
            timerOnly = chargeEndAtMillis == null,
            cabin = event.result,
        )
        evaluate()
    }

    // ================================================================ 수면

    /** `min(타이머 종료 시각, 충전 완료 예상 시각 − 5분)`. */
    private fun wakeAtMillis(s: Session): Long {
        val chargeLimit = chargeEndAtMillis?.minus(CHARGE_MARGIN_MILLIS)
        return if (chargeLimit != null) minOf(s.timerEndAtMillis, chargeLimit) else s.timerEndAtMillis
    }

    private fun evaluateNapping(v: Vehicle, current: NapSessionState.Napping) {
        val s = session ?: return
        when {
            v.charging == ChargingState.COMPLETE -> return beginWaking(s, WakeReason.CHARGE_COMPLETE)
            v.charging == ChargingState.NOT_CONNECTED ||
                (v.charging == ChargingState.CONNECTED_IDLE && s.sawCharging) ->
                return beginWaking(s, WakeReason.CHARGING_INTERRUPTED)
        }

        val wakeAt = wakeAtMillis(s)
        if (v.now >= wakeAt) {
            return beginWaking(s, if (wakeAt < s.timerEndAtMillis) WakeReason.CHARGE_NEARLY_DONE else WakeReason.TIMER)
        }

        if (!s.breathingGuideNotified && v.now >= s.startedAtMillis + timings.breathingGuideMillis) {
            s.breathingGuideNotified = true
            notify(SessionNotice.BreathingGuideFinished)
        }

        _state.value = current.copy(
            wakeAtMillis = wakeAt,
            plannedMinutes = s.plannedMinutes,
            timerOnly = chargeEndAtMillis == null,
        )
        // 추정치가 조금씩 흔들릴 때마다 알리지 않도록, 마지막으로 알린 시각에서 1분 이상 달라졌을 때만 알린다.
        if (abs(wakeAt - s.announcedWakeAtMillis) >= RESCHEDULE_NOTICE_MILLIS) {
            s.announcedWakeAtMillis = wakeAt
            notify(SessionNotice.WakeRescheduled(wakeAt))
        }
    }

    private fun onRequestExtend(minutes: Int) {
        val current = _state.value
        val s = session
        if (current !is NapSessionState.Napping || s == null) {
            notify(SessionNotice.ExtendDenied(ExtendDenyReason.NOT_NAPPING))
            return
        }
        if (minutes <= 0) return
        refresh()
        val newTimerEnd = s.timerEndAtMillis + minutes * MINUTE_MILLIS
        if (newTimerEnd - s.startedAtMillis > MAX_TOTAL_MILLIS) {
            notify(SessionNotice.ExtendDenied(ExtendDenyReason.MAX_TOTAL))
            return
        }
        val chargeLimit = chargeEndAtMillis?.minus(CHARGE_MARGIN_MILLIS)
        if (chargeLimit != null && newTimerEnd > chargeLimit) {
            notify(SessionNotice.ExtendDenied(ExtendDenyReason.CHARGE_ENDS_SOON))
            return
        }
        s.timerEndAtMillis = newTimerEnd
        s.plannedMinutes += minutes
        val wakeAt = wakeAtMillis(s)
        s.announcedWakeAtMillis = wakeAt
        _state.value = current.copy(
            wakeAtMillis = wakeAt,
            plannedMinutes = s.plannedMinutes,
            timerOnly = chargeEndAtMillis == null,
        )
        notify(SessionNotice.ExtendGranted(minutes, wakeAt))
    }

    private suspend fun onRequestWake() {
        val s = session ?: return
        when (_state.value) {
            is NapSessionState.SafetyCheck -> {
                // 아직 차량에 아무것도 하지 않았다.
                session = null
                offerSuppressed = true
                _state.value = NapSessionState.Idle
            }
            is NapSessionState.Preparing -> {
                // 잠들기 전에 그만둠: 적용을 멈추고(시트 정지) 기상 바람 없이 복구만 한다.
                cancelWork()
                beginWaking(s, WakeReason.USER, restoreOnly = true)
            }
            is NapSessionState.Napping -> beginWaking(s, WakeReason.USER)
            else -> Unit
        }
    }

    // ================================================================ 기상과 브리핑

    private fun beginWaking(s: Session, reason: WakeReason, restoreOnly: Boolean = false) {
        val now = clock.nowMillis()
        s.wakeReason = reason
        s.endedAtMillis = now
        if (!s.napStarted) s.startedAtMillis = now
        _state.value = NapSessionState.Waking(reason)
        launchWork { id ->
            val wake = if (restoreOnly) {
                null
            } else {
                guarded(seat = false, climate = true) { gateway.applyWakePreset() }
                    .also { waitOnSessionClock(timings.wakeBreezeMillis) }
            }
            val restore = restoreCabinWithRetry(s)
            val rangeKm = try {
                gateway.rangeRemainingKm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            loopEvents.send(Event.WakeDone(id, wake, restore, rangeKm))
        }
    }

    private fun onWakeDone(event: Event.WakeDone) {
        if (event.workId != workId || _state.value !is NapSessionState.Waking) return
        val s = session ?: return
        work = null
        keepSnapshotIfSeatNotRestored(s, event.restore)
        event.wake?.let { s.failures += it.errors }
        s.failures += event.restore.errors
        _state.value = NapSessionState.Briefing(
            SessionSummary(
                startedAtMillis = s.startedAtMillis,
                endedAtMillis = s.endedAtMillis,
                mode = s.mode,
                plannedMinutes = s.plannedMinutes,
                sleptMinutes = ((s.endedAtMillis - s.startedAtMillis) / MINUTE_MILLIS).toInt().coerceAtLeast(0),
                batteryStartPercent = s.batteryStartPercent,
                batteryEndPercent = gateway.batteryPercent.value,
                rangeKm = event.rangeKm,
                wakeReason = s.wakeReason ?: WakeReason.USER,
                seatMoved = s.seatMoved,
                failures = s.failures.distinct(),
            ),
        )
    }

    private fun finishBriefing() {
        val current = _state.value as? NapSessionState.Briefing ?: return
        if (session?.napStarted != false) _completedSessions.tryEmit(current.summary)
        session = null
        offerSuppressed = true
        _state.value = NapSessionState.Idle
    }

    // ================================================================ 중단

    /** 기어가 P를 벗어났거나 도어가 열렸으면 진행 중 작업을 멈추고 `Aborted`로 간다. */
    private suspend fun abortIfUnsafe(v: Vehicle): Boolean {
        val s = session ?: return false
        val reason = SafetyGuard.abortReason(v.park.gearInPark, v.doorOpen) ?: return false
        cancelWork()
        when (reason) {
            AbortReason.GEAR_LEFT_PARK -> startAbortRestore(s, reason)
            AbortReason.DOOR_OPENED -> waitForDoor(s)
        }
        return true
    }

    /** 도어가 열려 있는 동안은 시트를 움직이지 않는다. 닫히거나 사용자가 확인하면 복구한다. */
    private fun waitForDoor(s: Session) {
        s.doorOverridden = false
        if (s.snapshot == null) {
            // 아직 차량에 아무것도 하지 않았다. 기다릴 것이 없다.
            startAbortRestore(s, AbortReason.DOOR_OPENED)
            return
        }
        s.waitingForDoor = true
        _state.value = NapSessionState.Aborted(AbortReason.DOOR_OPENED, restoring = true, restore = null)
    }

    private fun startAbortRestore(s: Session, reason: AbortReason) {
        s.waitingForDoor = false
        _state.value = NapSessionState.Aborted(reason, restoring = true, restore = null)
        if (s.snapshot == null) {
            finishAbortRestore(s, reason, CabinApplyResult.NOTHING)
            return
        }
        launchWork { id ->
            val result = restoreCabinWithRetry(s)
            loopEvents.send(Event.RestoreDone(id, result))
        }
    }

    private fun onRestoreDone(event: Event.RestoreDone) {
        val current = _state.value as? NapSessionState.Aborted ?: return
        if (event.workId != workId || !current.restoring) return
        val s = session ?: return
        finishAbortRestore(s, current.reason, event.result)
    }

    private fun finishAbortRestore(s: Session, reason: AbortReason, result: CabinApplyResult) {
        work = null
        keepSnapshotIfSeatNotRestored(s, result)
        s.abortFinishedAtMillis = clock.nowMillis()
        _state.value = NapSessionState.Aborted(reason, restoring = false, restore = result)
    }

    private suspend fun evaluateAborted(v: Vehicle, current: NapSessionState.Aborted) {
        val s = session
        if (s == null) {
            _state.value = NapSessionState.Idle
            return
        }
        if (!current.restoring) {
            if (v.now >= s.abortFinishedAtMillis + timings.abortLingerMillis) endAbortedSession()
            return
        }
        if (current.reason != AbortReason.DOOR_OPENED) return
        when {
            // 도어가 열린 채로 출발하려 한다: 운전 자세가 먼저이므로 기다리지 않고 복구한다.
            v.park.gearInPark == false -> {
                cancelWork()
                startAbortRestore(s, AbortReason.GEAR_LEFT_PARK)
            }
            s.waitingForDoor -> if (v.doorOpen == false) startAbortRestore(s, AbortReason.DOOR_OPENED)
            // 복구 도중 도어가 다시 열림: 시트를 멈추고 다시 기다린다.
            v.doorOpen == true && !s.doorOverridden -> {
                cancelWork()
                waitForDoor(s)
            }
        }
    }

    private fun onAcknowledgeAbort() {
        val current = _state.value as? NapSessionState.Aborted ?: return
        if (current.restoring) confirmAbortRestore(current) else endAbortedSession()
    }

    /** 도어가 닫히기를 기다리는 중에 사용자가 확인하면 도어 값과 상관없이 복구한다. */
    private fun confirmAbortRestore(current: NapSessionState.Aborted) {
        val s = session ?: return
        if (!current.restoring || current.reason != AbortReason.DOOR_OPENED || !s.waitingForDoor) return
        s.doorOverridden = true
        startAbortRestore(s, AbortReason.DOOR_OPENED)
    }

    private fun endAbortedSession() {
        session = null
        offerSuppressed = true
        _state.value = NapSessionState.Idle
    }

    // ================================================================ 종료

    private suspend fun shutdownNow() {
        cancelWork()
        // 아직 복구하지 않았거나 시트 복구가 끝내 실패해 스냅샷이 남아 있으면 한 번 더 복구한다.
        session?.let { restoreCabin(it) }
        if (_state.value is NapSessionState.Briefing) {
            finishBriefing()        // 기록을 남긴다
            return
        }
        session = null
        _state.value = NapSessionState.Idle
    }

    // ================================================================ 차량 제어 도우미

    private fun launchWork(block: suspend (Int) -> Unit) {
        val id = ++workId
        work = loopScope.launch { block(id) }
    }

    /** 진행 중인 차량 제어를 취소하고 끝날 때까지 기다린다(게이트웨이가 시트를 멈춘다). 늦게 온 결과는 버린다. */
    private suspend fun cancelWork() {
        workId++
        val job = work ?: return
        work = null
        job.cancelAndJoin()
    }

    private suspend fun restoreCabin(s: Session): CabinApplyResult {
        val snapshot = s.snapshot ?: return CabinApplyResult.NOTHING
        return guarded(seat = snapshot.seat != null, climate = snapshot.climate != null) {
            gateway.restoreCabin(snapshot)
        }
    }

    /**
     * 시트 복구가 실패하면 세션 시계로 [RESTORE_RETRY_MILLIS] 뒤에 다시 시도한다. 모두 [RESTORE_ATTEMPTS]번.
     * 운전 자세로 돌아가는 것이 가장 중요하므로 공조만 실패한 것은 다시 시도하지 않는다. 결과는 마지막 시도의 것이다.
     */
    private suspend fun restoreCabinWithRetry(s: Session): CabinApplyResult {
        var result = restoreCabin(s)
        var attempts = 1
        while (result.seatFailed && attempts < RESTORE_ATTEMPTS) {
            waitOnSessionClock(RESTORE_RETRY_MILLIS)
            result = restoreCabin(s)
            attempts++
        }
        return result
    }

    /** 시트를 되돌리지 못했으면 스냅샷을 남겨 종료([shutdown]) 때 한 번 더 복구한다. 되돌렸으면 지운다. */
    private fun keepSnapshotIfSeatNotRestored(s: Session, result: CabinApplyResult) {
        if (!result.seatFailed) s.snapshot = null
    }

    /** 게이트웨이는 예외를 던지지 않기로 했지만, 던지더라도 세션이 멈추지 않게 실패 항목으로 바꾼다. */
    private suspend fun guarded(
        seat: Boolean,
        climate: Boolean,
        block: suspend () -> CabinApplyResult,
    ): CabinApplyResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(seat, climate, e)
    }

    private fun failure(seat: Boolean, climate: Boolean, cause: Exception): CabinApplyResult {
        val reason = cause.message ?: cause.javaClass.simpleName
        return CabinApplyResult(
            seatApplied = false,
            climateApplied = false,
            errors = buildList {
                if (seat) add("${CabinApplyResult.ERROR_SEAT}: $reason")
                if (climate) add("${CabinApplyResult.ERROR_CLIMATE}: $reason")
            },
        )
    }

    private suspend fun waitOnSessionClock(millis: Long) {
        val until = clock.nowMillis() + millis
        while (clock.nowMillis() < until) delay(tickMillis)
    }

    private fun notify(notice: SessionNotice) {
        _notices.tryEmit(notice)
    }

    // ================================================================ 내부 타입

    private class Vehicle(
        val now: Long,
        val charging: ChargingState,
        val park: ParkState,
        val doorOpen: Boolean?,
        val batteryPercent: Int?,
        val sdkRemainingMinutes: Int?,
        val capabilities: VehicleCapabilities,
    )

    /** 시작 요청부터 `Idle`로 돌아갈 때까지의 세션 하나. */
    private class Session(val mode: SessionMode, var plannedMinutes: Int) {
        var rearSeatPending = false
        var seatConsent = false
        var doorConfirmed = false
        var sawCharging = false

        /**
         * 복구해야 할 캐빈 상태. null이면 복구할 것이 없다(아직 안 건드렸거나 이미 복구했다).
         * 시트 복구가 끝내 실패하면 지우지 않고 남겨 종료 때 한 번 더 복구한다.
         */
        @Volatile var snapshot: CabinSnapshot? = null
        var cabin: CabinApplyResult = CabinApplyResult.NOTHING
        val failures = mutableListOf<String>()
        var seatMoved = false

        var napStarted = false
        var startedAtMillis = 0L
        var timerEndAtMillis = 0L
        var endedAtMillis = 0L
        var batteryStartPercent: Int? = null
        var breathingGuideNotified = false
        var announcedWakeAtMillis = 0L
        var wakeReason: WakeReason? = null

        var waitingForDoor = false
        var doorOverridden = false
        var abortFinishedAtMillis = 0L
    }

    private sealed interface Event {
        data object Evaluate : Event
        data object Shutdown : Event
        data class RequestStart(val minutes: Int?) : Event
        data object DeclineOffer : Event
        data object Cancel : Event
        data class AnswerRearSeat(val clear: Boolean?) : Event
        data object ConfirmDoorsClosed : Event
        data class RequestExtend(val minutes: Int) : Event
        data object RequestWake : Event
        data object AcknowledgeBriefing : Event
        data object AcknowledgeAbort : Event
        class PrepareDone(val workId: Int, val result: CabinApplyResult) : Event
        class WakeDone(
            val workId: Int,
            val wake: CabinApplyResult?,
            val restore: CabinApplyResult,
            val rangeKm: Int?,
        ) : Event
        class RestoreDone(val workId: Int, val result: CabinApplyResult) : Event
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val MIN_SESSION_MINUTES = 5
        const val MAX_SESSION_MINUTES = 25
        const val MIN_NAP_MINUTES = 10
        const val MAX_TOTAL_MILLIS = 30 * MINUTE_MILLIS
        const val CHARGE_MARGIN_MILLIS = 5 * MINUTE_MILLIS
        const val RESCHEDULE_NOTICE_MILLIS = MINUTE_MILLIS
        const val EVENT_BUFFER = 64
        const val RESTORE_ATTEMPTS = 3
        const val RESTORE_RETRY_MILLIS = 2_000L
    }
}
