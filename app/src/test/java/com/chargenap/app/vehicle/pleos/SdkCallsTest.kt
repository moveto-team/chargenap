package com.chargenap.app.vehicle.pleos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkCallsTest {

    @Test
    fun onComplete_returnsSuccess() = runTest {
        val sdk = SdkCalls()
        val outcome = sdk.request<Int?> { complete, _ -> complete(72) }
        assertEquals(SdkOutcome.Success<Int?>(72), outcome)
        assertEquals(72, outcome.valueOrNull())
        assertNull(outcome.errorOrNull())
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun onComplete_withNull_isSuccessWithNullValue() = runTest {
        val outcome = SdkCalls().request<Int?> { complete, _ -> complete(null) }
        assertTrue(outcome is SdkOutcome.Success)
        assertNull(outcome.valueOrNull())
    }

    @Test
    fun onFailed_returnsFailureWithMessage() = runTest {
        val outcome = SdkCalls().request<Int?> { _, fail -> fail(Exception("ERROR_NOT_SUPPORTED_PROPERTY")) }
        assertEquals(SdkOutcome.Failure("ERROR_NOT_SUPPORTED_PROPERTY"), outcome)
        assertNull(outcome.valueOrNull())
        assertEquals("ERROR_NOT_SUPPORTED_PROPERTY", outcome.errorOrNull())
    }

    @Test
    fun onFailed_withSecurityException_marksPermissionDenied() = runTest {
        val direct = SdkCalls().request<Int?> { _, fail -> fail(SecurityException("getHvacFanSpeed need Permission")) }
        val wrapped = SdkCalls().request<Int?> { _, fail -> fail(Exception(SecurityException("need Permission"))) }
        assertTrue((direct as SdkOutcome.Failure).permissionDenied)
        assertTrue((wrapped as SdkOutcome.Failure).permissionDenied)
    }

    @Test
    fun synchronousThrow_becomesFailure() = runTest {
        val sdk = SdkCalls()
        val outcome = sdk.request<Unit> { _, _ -> throw SecurityException("registerDoorPosition need Permission") }
        val failure = outcome as SdkOutcome.Failure
        assertTrue(failure.permissionDenied)
        assertEquals("registerDoorPosition need Permission", failure.message)
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun noCallback_timesOut_andKeepsCountingUntilCallbackArrives() = runTest {
        val sdk = SdkCalls(defaultTimeoutMs = 3_000)
        var late: ((Int?) -> Unit)? = null

        val outcome = sdk.request<Int?> { complete, _ -> late = complete }

        assertEquals(SdkOutcome.Timeout, outcome)
        assertEquals(3_000, currentTime)
        assertEquals("응답 시간 초과", outcome.errorOrNull())
        assertEquals(1, sdk.awaitingCallbacks) // 콜백이 아직 오지 않았으니 release()를 미뤄야 한다

        late!!.invoke(50) // 뒤늦게 온 콜백. 터지지 않고 세기만 한다
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun defaultTimeout_isFiveSeconds() = runTest {
        // 부하가 높은 에뮬레이터에서 3초는 정상 응답도 자주 넘겼다
        val outcome = SdkCalls().request<Int?> { _, _ -> }
        assertEquals(SdkOutcome.Timeout, outcome)
        assertEquals(5_000, currentTime)
    }

    @Test
    fun customTimeout_isUsed() = runTest {
        val outcome = SdkCalls().request<Int?>(timeoutMs = 8_000) { _, _ -> }
        assertEquals(SdkOutcome.Timeout, outcome)
        assertEquals(8_000, currentTime)
    }

    @Test
    fun callbackCalledTwice_usesFirstAndIgnoresRest() = runTest {
        val sdk = SdkCalls()
        val outcome = sdk.request<Int?> { complete, fail ->
            complete(10)
            complete(20)
            fail(Exception("late failure"))
        }
        assertEquals(10, outcome.valueOrNull())
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun deferredCallback_resumesWhenItArrives() = runTest {
        val sdk = SdkCalls()
        var pending: ((Boolean?) -> Unit)? = null
        val deferred = async { sdk.request<Boolean?> { complete, _ -> pending = complete } }
        runCurrent()
        assertEquals(1, sdk.awaitingCallbacks)

        advanceTimeBy(1_000)
        pending!!.invoke(true)

        assertEquals(true, deferred.await().valueOrNull())
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun cancelledCaller_doesNotCrashOnLateCallback() = runTest {
        val sdk = SdkCalls()
        var pending: ((Int?) -> Unit)? = null
        val deferred = async { sdk.request<Int?> { complete, _ -> pending = complete } }
        runCurrent()

        deferred.cancel()
        runCurrent()
        assertEquals(1, sdk.awaitingCallbacks)

        pending!!.invoke(1)
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun fire_countsUntilCallback() {
        val sdk = SdkCalls()
        var pending: ((Unit) -> Unit)? = null
        sdk.fire<Unit> { complete, _ -> pending = complete }
        assertEquals(1, sdk.awaitingCallbacks)
        pending!!.invoke(Unit)
        assertEquals(0, sdk.awaitingCallbacks)

        sdk.fire<Unit> { _, _ -> throw IllegalStateException("boom") } // 던져도 밖으로 새지 않는다
        assertEquals(0, sdk.awaitingCallbacks)
    }

    @Test
    fun isConnectionFailure_recognisesSdkMessages() {
        assertTrue(isConnectionFailure("Service connection failed"))
        assertTrue(isConnectionFailure("ERROR_SERVICE_CONNECTION_FAILURE"))
        assertFalse(isConnectionFailure("ERROR_NOT_SUPPORTED_PROPERTY"))
    }
}
