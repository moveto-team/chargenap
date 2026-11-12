package com.chargenap.app.vehicle.pleos

import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 운전석 등받이 스냅샷: 위치를 읽은 영역 키와 그때의 위치. */
internal data class PleosSeatSnapshot(val positionArea: SeatArea, val position: Int)

/**
 * 운전석 등받이의 스냅샷·리클라인·복구. SDK 호출은 여기서만 하고, 이동 중 판단은 [runSeatMove]가 한다.
 *
 * 사용 API(docs/sdk-notes.md 5.2): `getCurrentSeatBackrestAnglePosition`, `moveSeatBackrestAngle`,
 * `registerSeatBackrestAnglePosition` / `unregisterSeatBackrestAnglePosition`.
 *
 * @param reclineBlockedReason 지금 눕히면 안 되는 사유(도어 열림, 기어가 P가 아님). 없으면 null.
 *   세션 엔진의 안전 가드와 별개로, 눕히는 방향의 이동에만 거는 이중 방어다.
 * @param reclineLimits 눕힐 때의 이동 제한
 * @param restoreLimits 세울(복구할) 때의 이동 제한. 운전 자세로 돌아가는 것이 더 중요해 더 오래 기다린다
 */
internal class PleosSeatControl(
    private val seat: Seat,
    private val sdk: SdkCalls,
    private val capability: SeatCapability,
    private val reclineBlockedReason: () -> String?,
    private val reclineLimits: SeatMoveLimits = SeatMoveLimits.RECLINE,
    private val restoreLimits: SeatMoveLimits = SeatMoveLimits.RESTORE,
) {
    /** 이동 명령을 보낸 뒤 정지를 확인하거나(또는 확인 시도가 끝나거나) 할 때까지 true. */
    private val moving = AtomicBoolean(false)

    suspend fun snapshot(): PleosSeatSnapshot? =
        readPosition()?.let { PleosSeatSnapshot(capability.positionArea, it) }

    /** @param reclineLevel 1..100. 가동 범위의 %. 이미 그만큼 누워 있으면 움직이지 않는다. */
    suspend fun recline(reclineLevel: Int): SeatMoveResult {
        reclineBlockedReason()?.let { return SeatMoveResult.failed(it) }
        val current = readPosition() ?: return SeatMoveResult.failed(POSITION_UNREADABLE)
        val plan = planRecline(capability.min, capability.max, current, reclineLevel)
            ?: return SeatMoveResult.NOT_MOVED
        return move(plan, reclineLimits, reclineBlockedReason)
    }

    /** 스냅샷 위치까지 세운다. 이미 그만큼 서 있으면 움직이지 않는다. */
    suspend fun restore(snapshot: PleosSeatSnapshot): SeatMoveResult {
        if (snapshot.positionArea != capability.positionArea) return SeatMoveResult.failed(AREA_MISMATCH)
        val current = readPosition() ?: return SeatMoveResult.failed(POSITION_UNREADABLE)
        val plan = planRaise(capability.min, capability.max, current, snapshot.position)
            ?: return SeatMoveResult.NOT_MOVED
        return move(plan, restoreLimits) { null }
    }

    /**
     * 이동 중이거나 정지를 확인하는 중이면 정지 명령을 보낸다. suspend할 수 없는 `stop()`에서 SDK를 해제하기 직전에 부른다.
     * 이동 코루틴 쪽의 `finally` 정지 확인과 겹쳐도 해가 없다.
     */
    fun stopIfMoving() {
        if (!moving.get()) return
        sdk.fire<Unit> { complete, fail ->
            seat.moveSeatBackrestAngle(
                area = capability.moveArea,
                value = MoveControl.STOP,
                onComplete = { _, _ -> complete(Unit) },
                onFailed = fail,
            )
        }
    }

    private suspend fun move(
        plan: SeatMovePlan,
        limits: SeatMoveLimits,
        abortReason: () -> String?,
    ): SeatMoveResult = coroutineScope {
        val port = Port(initialPosition = plan.start)
        moving.set(true)
        port.subscribe()
        val polling = launch { port.pollPosition() }
        try {
            // runSeatMove는 취소돼도 정지를 확인한(또는 확인 시도가 끝난) 뒤에야 돌아온다
            runSeatMove(port, plan, limits, abortReason = abortReason)
        } finally {
            moving.set(false)
            polling.cancel()
            port.unsubscribe()
        }
    }

    private suspend fun readPosition(): Int? =
        sdk.request<Int?> { complete, fail ->
            seat.getCurrentSeatBackrestAnglePosition(capability.positionArea, complete, fail)
        }.valueOrNull()

    /**
     * 이동 한 번 동안만 사는 포트. 위치는 리스너로 받고, 리스너가 조용한 차량에 대비해 주기적으로도 조회한다.
     * 조회 결과가 그 사이에 온 리스너 값보다 오래된 것일 수 있어, 조회를 시작한 뒤 리스너가 울렸으면 조회 결과를
     * 버린다([LatestValueGuard]: 판정과 쓰기를 한 락 안에서 한다).
     */
    private inner class Port(initialPosition: Int) : BackrestPort {
        private val _position = MutableStateFlow<Int?>(initialPosition)
        override val position: StateFlow<Int?> = _position.asStateFlow()

        private val freshness = LatestValueGuard()
        private var subscribed = false

        private val listener = object : SeatBackrestAnglePositionListener {
            override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
                if (position == null) return
                freshness.push(KEY_POSITION) { _position.value = position }
            }

            override fun onFailed(e: Exception) {
                // 위치를 못 받으면 runSeatMove의 "응답 없음" 안전장치가 멈춘다
            }
        }

        fun subscribe() {
            // 권한이 없으면 register가 SecurityException을 던진다(docs/sdk-notes.md 9.4)
            subscribed = try {
                seat.registerSeatBackrestAnglePosition(capability.positionArea, listener)
                true
            } catch (t: Throwable) {
                false
            }
        }

        fun unsubscribe() {
            if (!subscribed) return
            subscribed = false
            try {
                seat.unregisterSeatBackrestAnglePosition(capability.positionArea, listener)
            } catch (t: Throwable) {
                // 해제 실패는 무시한다. Vehicle.release()가 리스너를 모두 비운다
            }
        }

        suspend fun pollPosition() {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val ticket = freshness.beginRead(KEY_POSITION)
                val polled = readPosition()
                if (polled != null) freshness.completeRead(ticket) { _position.value = polled }
            }
        }

        override suspend fun command(command: SeatCommand): String? =
            sdk.request<Unit> { complete, fail ->
                seat.moveSeatBackrestAngle(
                    area = capability.moveArea,
                    value = command.toMoveControl(),
                    onComplete = { _, _ -> complete(Unit) },
                    onFailed = fail,
                )
            }.errorOrNull()

        override suspend fun readPosition(): Int? = this@PleosSeatControl.readPosition()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val KEY_POSITION = "position"
        const val POSITION_UNREADABLE = "현재 위치를 읽지 못함"
        const val AREA_MISMATCH = "스냅샷의 좌석 영역이 현재 차량과 다름"
    }
}

/**
 * 눕히기 = `BACKWARD`, 세우기 = `FORWARD`. AAOS 정의에서 따온 **가정**이다(docs/sdk-notes.md 5.2).
 * 가정이 틀린 차량에서는 [runSeatMove]가 "방향 불일치"로 즉시 멈춘다. 방향을 바꾸려면 여기만 고친다.
 */
private fun SeatCommand.toMoveControl(): MoveControl = when (this) {
    SeatCommand.RECLINE -> MoveControl.BACKWARD
    SeatCommand.RAISE -> MoveControl.FORWARD
    SeatCommand.STOP -> MoveControl.STOP
}
