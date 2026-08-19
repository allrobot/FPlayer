package io.github.fplayer.feature.device

import io.github.fplayer.core.device.TransportType
import io.github.fplayer.core.model.AxisId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConfigurationModelsTest {
    @Test
    fun defaultsLimitL0AndUseLowTestAmplitude() {
        val state = DeviceUiState()

        assertEquals(listOf("L0"), state.enabledAxes.map { it.id.value })
        assertEquals(40, state.enabledAxes.single().minimum)
        assertEquals(60, state.enabledAxes.single().maximum)
        assertEquals(5, state.testAmplitude)
        assertEquals(10, state.maximumSafeTestAmplitude())
    }

    @Test
    fun axisRangeClampsTestAmplitude() {
        val state = DeviceUiState()
            .withTestAmplitude(10)
            .withAxisRange(AxisId("L0"), 48, 52)

        assertEquals(2, state.testAmplitude)
        assertEquals(2, state.maximumSafeTestAmplitude())
    }

    @Test
    fun networkProfileRequiresHostAndValidPort() {
        val missingHost = DeviceUiState().buildConnectionRequest()
        val invalidPort = DeviceUiState(
            network = NetworkConnectionSettings(host = "device.local", port = "70000"),
        ).buildConnectionRequest()

        assertTrue(missingHost.isFailure)
        assertEquals("NETWORK_HOST_REQUIRED", missingHost.exceptionOrNull()?.message)
        assertTrue(invalidPort.isFailure)
        assertEquals("NETWORK_PORT_INVALID", invalidPort.exceptionOrNull()?.message)
    }

    @Test
    fun discoveredTransportRequiresExplicitSelection() {
        val result = DeviceUiState(selectedTransport = TransportType.BLE).buildConnectionRequest()

        assertTrue(result.isFailure)
        assertEquals("DEVICE_SELECTION_REQUIRED", result.exceptionOrNull()?.message)
    }

    @Test
    fun safeTestPlanNeverLeavesConfiguredRange() {
        val state = DeviceUiState(
            connectionStatus = DeviceConnectionStatus.CONNECTED,
            testAmplitude = 7,
        )

        val plan = state.buildSafeTestPlan().getOrThrow()

        assertEquals(AxisId("L0"), plan.axis)
        assertEquals(listOf(43, 57, 50), plan.positions)
        assertTrue(plan.positions.all { it in 40..60 })
    }

    @Test
    fun profileCannotBeEditedWhileConnected() {
        val state = DeviceUiState(connectionStatus = DeviceConnectionStatus.CONNECTED)

        assertFalse(state.canEditProfile)
        assertEquals(TransportType.TCP, state.withTransport(TransportType.UDP).selectedTransport)
        assertEquals(40, state.withAxisRange(AxisId("L0"), 0, 100).enabledAxes.single().minimum)
    }
}
