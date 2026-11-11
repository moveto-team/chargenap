package com.chargenap.app.vehicle.pleos

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** SDK 호출 한 번의 결과. 예외로 던지지 않고 값으로 돌려준다. */
internal sealed interface SdkOutcome<out T> {
    data class Success<T>(val value: T) : SdkOutcome<T>

    /** `onFailed`가 불렸거나 호출이 동기적으로 예외를 던졌다. */
    data class Failure(val message: String, val permissionDenied: Boolean = false) : SdkOutcome<Nothing>

    /** 제한 시간 안에 콜백이 오지 않았다(서비스에 연결되지 않으면 콜백이 영영 오지 않을 수 있다). */
    data object Timeout : SdkOutcome<Nothing>
}

/** 조회용: 실패·타임아웃이면 null. */
internal fun <T> SdkOutcome<T>.valueOrNull(): T? = (this as? SdkOutcome.Success<T>)?.value

/** 제어용: 성공이면 null, 아니면 오류 사유. */
internal fun SdkOutcome<*>.errorOrNull(): String? = when (this) {
    is SdkOutcome.Success<*> -> null
    is SdkOutcome.Failure -> message
    SdkOutcome.Timeout -> TIMEOUT_MESSAGE
}

/**
 * 서비스에 연결하지 못했을 때 SDK가 주는 메시지인지.
 * 근거: docs/sdk-notes.md 2절(get/set은 "Service connection failed", capability는 ERROR_SERVICE_CONNECTION_FAILURE).
 */
internal fun isConnectionFailure(message: String): Boolean =
    message.contains("Service connection failed") || message.contains("ERROR_SERVICE_CONNECTION_FAILURE")

/**
 * Pleos Vehicle SDK의 콜백형 호출(get / set / capability)을 suspend 함수로 바꾸는 어댑터.
 *
 * - 콜백은 메인 스레드가 아닌 곳에서 오고, 오지 않을 수도 있으므로 모든 호출에 제한 시간을 건다.
 * - 콜백이 두 번 불려도(예: `onComplete` 뒤 `onFailed`) 첫 번째만 쓴다.
 * - 권한이 없을 때 등 호출이 동기적으로 던지는 예외도 [SdkOutcome.Failure]로 바꾼다.
 * - [awaitingCallbacks]로 아직 콜백이 오지 않은 호출 수를 센다. `Vehicle.release()`는 이 값이 0이 된 뒤에 부른다.
 */
internal class SdkCalls(private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val awaiting = AtomicInteger(0)

    /** 호출은 했는데 콜백이 아직 오지 않은 수. 호출자가 기다리기를 포기(타임아웃·취소)해도 콜백이 올 때까지 남는다. */
    val awaitingCallbacks: Int get() = awaiting.get()

    /**
     * [call]에 넘긴 `complete` 또는 `fail` 중 먼저 불린 것으로 끝난다.
     *
     * ```
     * sdk.request<Int?> { complete, fail -> evBattery.getEvBatteryStateOfCharge(complete, fail) }
     * ```
     */
    suspend fun <T> request(
        timeoutMs: Long = defaultTimeoutMs,
        call: (complete: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): SdkOutcome<T> {
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<SdkOutcome<T>> { continuation ->
                // 이미 취소된 continuation에 resume하면 무시된다. 두 번 resume하지 않게만 막으면 된다.
                dispatch(call) { continuation.resume(it) }
            }
        }
        return outcome ?: SdkOutcome.Timeout
    }

    /**
     * 결과를 기다리지 않고 호출만 한다(suspend할 수 없는 `stop()`에서 시트 정지 명령을 보낼 때).
     * 콜백은 [awaitingCallbacks]에 잡히므로 `release()`가 그 뒤로 밀린다.
     */
    fun <T> fire(call: (complete: (T) -> Unit, fail: (Exception) -> Unit) -> Unit) {
        dispatch(call) { }
    }

    private fun <T> dispatch(
        call: (complete: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
        deliver: (SdkOutcome<T>) -> Unit,
    ) {
        val answered = AtomicBoolean(false)
        awaiting.incrementAndGet()
        fun answer(outcome: SdkOutcome<T>) {
            if (!answered.compareAndSet(false, true)) return
            awaiting.decrementAndGet()
            deliver(outcome)
        }
        try {
            call({ answer(SdkOutcome.Success(it)) }, { answer(failureOf(it)) })
        } catch (t: Throwable) {
            answer(failureOf(t))
        }
    }

    private fun failureOf(t: Throwable): SdkOutcome.Failure = SdkOutcome.Failure(
        message = t.message ?: t.javaClass.simpleName,
        // SDK가 예외를 Exception(cause)로 한 번 감싸 주는 경로가 있다
        permissionDenied = t is SecurityException || t.cause is SecurityException,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}

private const val TIMEOUT_MESSAGE = "응답 시간 초과"
