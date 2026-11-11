package com.chargenap.app.vehicle.pleos

import com.chargenap.app.vehicle.ChargingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingStateMapperTest {

    // ---- 1순위: AAOS EV_CHARGE_STATE ----

    @Test
    fun aaosCharging_isCharging_evenIfSwitchSaysOff() {
        val state = mapChargingState(
            ChargingInputs(aaosChargeState = AaosEvChargeState.CHARGING, chargeSwitchOn = false, batteryPercent = 100),
        )
        assertEquals(ChargingState.CHARGING, state)
    }

    @Test
    fun aaosFullyCharged_isComplete() {
        val state = mapChargingState(
            ChargingInputs(aaosChargeState = AaosEvChargeState.FULLY_CHARGED, chargeSwitchOn = true, batteryPercent = 80),
        )
        assertEquals(ChargingState.COMPLETE, state)
    }

    @Test
    fun aaosNotCharging_connectorPlugged_isConnectedIdle() {
        val state = mapChargingState(
            ChargingInputs(aaosChargeState = AaosEvChargeState.NOT_CHARGING, aaosPortConnected = true),
        )
        assertEquals(ChargingState.CONNECTED_IDLE, state)
    }

    @Test
    fun aaosNotCharging_connectorUnplugged_isNotConnected_evenIfPortOpen() {
        val state = mapChargingState(
            ChargingInputs(
                aaosChargeState = AaosEvChargeState.NOT_CHARGING,
                aaosPortConnected = false,
                sdkPortOpen = true,
                chargeSwitchOn = true,
            ),
        )
        assertEquals(ChargingState.NOT_CONNECTED, state)
    }

    @Test
    fun aaosNotCharging_noConnectorSignal_usesSdkPortOpen() {
        val open = mapChargingState(ChargingInputs(aaosChargeState = AaosEvChargeState.NOT_CHARGING, sdkPortOpen = true))
        val closed = mapChargingState(ChargingInputs(aaosChargeState = AaosEvChargeState.NOT_CHARGING, sdkPortOpen = false))
        val unknown = mapChargingState(ChargingInputs(aaosChargeState = AaosEvChargeState.NOT_CHARGING))
        assertEquals(ChargingState.CONNECTED_IDLE, open)
        assertEquals(ChargingState.NOT_CONNECTED, closed)
        assertEquals(ChargingState.NOT_CONNECTED, unknown)
    }

    @Test
    fun aaosUnknownOrError_fallsBackToSdkInference() {
        for (aaos in listOf(AaosEvChargeState.UNKNOWN, AaosEvChargeState.ERROR, 99)) {
            val charging = mapChargingState(ChargingInputs(aaosChargeState = aaos, chargeSwitchOn = true, batteryPercent = 60))
            val unknown = mapChargingState(ChargingInputs(aaosChargeState = aaos))
            assertEquals("aaos=$aaos", ChargingState.CHARGING, charging)
            assertEquals("aaos=$aaos", ChargingState.UNKNOWN, unknown)
        }
    }

    // ---- 2순위: SDK 추론 ----

    @Test
    fun switchOn_below100_isCharging() {
        assertEquals(ChargingState.CHARGING, mapChargingState(ChargingInputs(chargeSwitchOn = true, batteryPercent = 99)))
    }

    @Test
    fun switchOn_at100_isComplete() {
        assertEquals(ChargingState.COMPLETE, mapChargingState(ChargingInputs(chargeSwitchOn = true, batteryPercent = 100)))
    }

    @Test
    fun switchOn_percentUnknown_isCharging() {
        assertEquals(ChargingState.CHARGING, mapChargingState(ChargingInputs(chargeSwitchOn = true, batteryPercent = null)))
    }

    @Test
    fun switchOff_connectorPlugged_isConnectedIdle() {
        val state = mapChargingState(ChargingInputs(chargeSwitchOn = false, aaosPortConnected = true, sdkPortOpen = false))
        assertEquals(ChargingState.CONNECTED_IDLE, state)
    }

    @Test
    fun switchOff_noConnectorSignal_portOpen_isConnectedIdle() {
        val state = mapChargingState(ChargingInputs(chargeSwitchOn = false, sdkPortOpen = true))
        assertEquals(ChargingState.CONNECTED_IDLE, state)
    }

    @Test
    fun switchOff_connectorUnplugged_isNotConnected_evenIfPortOpen() {
        val state = mapChargingState(ChargingInputs(chargeSwitchOn = false, aaosPortConnected = false, sdkPortOpen = true))
        assertEquals(ChargingState.NOT_CONNECTED, state)
    }

    @Test
    fun switchOff_nothingKnownAboutPort_isNotConnected() {
        assertEquals(ChargingState.NOT_CONNECTED, mapChargingState(ChargingInputs(chargeSwitchOn = false)))
    }

    @Test
    fun switchUnreadable_isUnknown() {
        val state = mapChargingState(ChargingInputs(chargeSwitchOn = null, batteryPercent = 50, sdkPortOpen = true))
        assertEquals(ChargingState.UNKNOWN, state)
    }

    @Test
    fun isPluggedIn_prefersConnectorSignalOverPortOpen() {
        assertTrue(isPluggedIn(aaosPortConnected = true, sdkPortOpen = false))
        assertFalse(isPluggedIn(aaosPortConnected = false, sdkPortOpen = true))
        assertTrue(isPluggedIn(aaosPortConnected = null, sdkPortOpen = true))
        assertFalse(isPluggedIn(aaosPortConnected = null, sdkPortOpen = null))
    }

    // ---- 남은 충전 시간 ----

    @Test
    fun remainingMinutes_roundsUpSeconds() {
        assertEquals(1, remainingChargeMinutes(1))
        assertEquals(1, remainingChargeMinutes(60))
        assertEquals(2, remainingChargeMinutes(61))
        assertEquals(20, remainingChargeMinutes(1_200))
        assertEquals(21, remainingChargeMinutes(1_201))
    }

    @Test
    fun remainingMinutes_zeroNegativeOrUnreadable_isNull() {
        assertNull(remainingChargeMinutes(0))
        assertNull(remainingChargeMinutes(-5))
        assertNull(remainingChargeMinutes(null))
    }
}
