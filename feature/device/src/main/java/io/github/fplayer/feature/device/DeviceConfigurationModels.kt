package io.github.fplayer.feature.device

import io.github.fplayer.core.device.AxisLimit
import io.github.fplayer.core.device.AxisSafetyConfig
import io.github.fplayer.core.device.TCodeAxisRegistry
import io.github.fplayer.core.device.TCodeVersion
import io.github.fplayer.core.device.TransportFailureCode
import io.github.fplayer.core.device.TransportType
import io.github.fplayer.core.model.AxisId

enum class DeviceConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class DeviceDiagnostic(
    val code: String,
    val message: String,
    val isError: Boolean = false,
)

data class DiscoveredDeviceUi(
    val id: String,
    val title: String,
    val detail: String,
    val signalStrength: Int? = null,
)

data class AxisUiSettings(
    val id: AxisId,
    val name: String,
    val enabled: Boolean,
    val minimum: Int,
    val maximum: Int,
    val reversed: Boolean,
) {
    init {
        require(minimum in 0..100)
        require(maximum in 0..100)
        require(minimum <= maximum)
    }

    fun toSafetyConfig() = AxisSafetyConfig(
        limit = AxisLimit(minimum, maximum),
        reversed = reversed,
    )
}

data class NetworkConnectionSettings(
    val host: String = "",
    val port: String = "8000",
    val webSocketPath: String = "/ws",
    val secureWebSocket: Boolean = false,
)

data class DeviceConnectionRequest(
    val transportType: TransportType,
    val selectedDeviceId: String?,
    val host: String?,
    val port: Int?,
    val webSocketPath: String?,
    val secureWebSocket: Boolean,
    val axes: Map<AxisId, AxisSafetyConfig>,
)

data class SafeTestPlan(
    val axis: AxisId,
    val positions: List<Int>,
    val durationMs: Long = 250,
    val stepDelayMs: Long = 350,
) {
    init {
        require(positions.isNotEmpty())
        require(positions.all { it in 0..100 })
        require(durationMs in 1..500)
        require(stepDelayMs >= 10)
    }
}

data class DeviceUiState(
    val selectedTransport: TransportType = TransportType.TCP,
    val connectionStatus: DeviceConnectionStatus = DeviceConnectionStatus.DISCONNECTED,
    val isDiscovering: Boolean = false,
    val discoveredDevices: List<DiscoveredDeviceUi> = emptyList(),
    val selectedDeviceId: String? = null,
    val network: NetworkConnectionSettings = NetworkConnectionSettings(),
    val axes: List<AxisUiSettings> = defaultAxisSettings(),
    val testAxis: AxisId = AxisId("L0"),
    val testAmplitude: Int = DEFAULT_TEST_AMPLITUDE,
    val testInProgress: Boolean = false,
    val transportDiagnostics: String = "transport disconnected pending=0",
    val diagnostics: List<DeviceDiagnostic> = emptyList(),
) {
    val canEditProfile: Boolean
        get() = connectionStatus == DeviceConnectionStatus.DISCONNECTED ||
            connectionStatus == DeviceConnectionStatus.FAILED

    val needsDiscovery: Boolean
        get() = selectedTransport in setOf(
            TransportType.BLE,
            TransportType.BLUETOOTH_SPP,
            TransportType.USB_SERIAL,
        )

    val enabledAxes: List<AxisUiSettings>
        get() = axes.filter(AxisUiSettings::enabled)

    val selectedTestAxis: AxisUiSettings?
        get() = axes.firstOrNull { it.id == testAxis && it.enabled }

    fun withTransport(transportType: TransportType): DeviceUiState {
        if (!canEditProfile || selectedTransport == transportType) return this
        val defaultPort = when (transportType) {
            TransportType.WEBSOCKET -> "80"
            else -> "8000"
        }
        return copy(
            selectedTransport = transportType,
            discoveredDevices = emptyList(),
            selectedDeviceId = null,
            network = network.copy(port = defaultPort),
        )
    }

    fun withAxisEnabled(axis: AxisId, enabled: Boolean): DeviceUiState {
        if (!canEditProfile) return this
        val updated = axes.map { settings ->
            if (settings.id == axis) settings.copy(enabled = enabled) else settings
        }
        val nextTestAxis = updated.firstOrNull { it.id == testAxis && it.enabled }?.id
            ?: updated.firstOrNull(AxisUiSettings::enabled)?.id
            ?: testAxis
        return copy(
            axes = updated,
            testAxis = nextTestAxis,
            testAmplitude = testAmplitude.coerceAtMost(maximumSafeTestAmplitude(updated, nextTestAxis).coerceAtLeast(1)),
        )
    }

    fun withAxisRange(axis: AxisId, minimum: Int, maximum: Int): DeviceUiState {
        if (!canEditProfile) return this
        val safeMinimum = minimum.coerceIn(0, 100)
        val safeMaximum = maximum.coerceIn(safeMinimum, 100)
        val updated = axes.map { settings ->
            if (settings.id == axis) settings.copy(minimum = safeMinimum, maximum = safeMaximum) else settings
        }
        val maximumAmplitude = maximumSafeTestAmplitude(updated, testAxis).coerceAtLeast(1)
        return copy(axes = updated, testAmplitude = testAmplitude.coerceAtMost(maximumAmplitude))
    }

    fun withAxisReversed(axis: AxisId, reversed: Boolean): DeviceUiState {
        if (!canEditProfile) return this
        return copy(axes = axes.map { if (it.id == axis) it.copy(reversed = reversed) else it })
    }

    fun withTestAxis(axis: AxisId): DeviceUiState {
        if (axes.none { it.id == axis && it.enabled }) return this
        return copy(
            testAxis = axis,
            testAmplitude = testAmplitude.coerceAtMost(maximumSafeTestAmplitude(axes, axis).coerceAtLeast(1)),
        )
    }

    fun withTestAmplitude(amplitude: Int): DeviceUiState {
        val maximum = maximumSafeTestAmplitude().coerceAtLeast(1)
        return copy(testAmplitude = amplitude.coerceIn(1, maximum))
    }

    fun maximumSafeTestAmplitude(): Int = maximumSafeTestAmplitude(axes, testAxis)

    fun buildConnectionRequest(): Result<DeviceConnectionRequest> = runCatching {
        val enabled = enabledAxes.associate { it.id to it.toSafetyConfig() }
        require(enabled.isNotEmpty()) { "AXIS_REQUIRED" }

        val isNetwork = selectedTransport in setOf(
            TransportType.TCP,
            TransportType.UDP,
            TransportType.WEBSOCKET,
        )
        val host = network.host.trim().takeIf { isNetwork }
        val port = network.port.toIntOrNull().takeIf { isNetwork }
        if (isNetwork) {
            require(!host.isNullOrBlank()) { "NETWORK_HOST_REQUIRED" }
            require(port != null && port in 1..65535) { "NETWORK_PORT_INVALID" }
        } else {
            require(selectedDeviceId != null) { "DEVICE_SELECTION_REQUIRED" }
        }
        val path = network.webSocketPath.trim().takeIf { selectedTransport == TransportType.WEBSOCKET }
        if (selectedTransport == TransportType.WEBSOCKET) {
            require(path?.startsWith('/') == true && path.length <= 256) { "WEBSOCKET_PATH_INVALID" }
        }

        DeviceConnectionRequest(
            transportType = selectedTransport,
            selectedDeviceId = selectedDeviceId,
            host = host,
            port = port,
            webSocketPath = path,
            secureWebSocket = network.secureWebSocket,
            axes = enabled,
        )
    }

    fun buildSafeTestPlan(): Result<SafeTestPlan> = runCatching {
        require(connectionStatus == DeviceConnectionStatus.CONNECTED) { "DEVICE_NOT_CONNECTED" }
        val axis = selectedTestAxis ?: error("TEST_AXIS_REQUIRED")
        val center = (axis.minimum + axis.maximum) / 2
        val maximumAmplitude = minOf(MAXIMUM_TEST_AMPLITUDE, center - axis.minimum, axis.maximum - center)
        require(maximumAmplitude >= 1) { "TEST_RANGE_TOO_NARROW" }
        require(testAmplitude in 1..maximumAmplitude) { "TEST_AMPLITUDE_INVALID" }
        SafeTestPlan(
            axis = axis.id,
            positions = listOf(center - testAmplitude, center + testAmplitude, center),
        )
    }

    fun appendDiagnostic(diagnostic: DeviceDiagnostic): DeviceUiState = copy(
        diagnostics = (diagnostics + diagnostic).takeLast(MAX_DIAGNOSTICS),
    )

    companion object {
        const val DEFAULT_AXIS_MINIMUM = 40
        const val DEFAULT_AXIS_MAXIMUM = 60
        const val DEFAULT_TEST_AMPLITUDE = 5
        const val MAXIMUM_TEST_AMPLITUDE = 10
        private const val MAX_DIAGNOSTICS = 8
    }
}

internal fun failureDiagnostic(code: TransportFailureCode): DeviceDiagnostic = DeviceDiagnostic(
    code = code.name,
    message = when (code) {
        TransportFailureCode.CONNECT_TIMEOUT -> "Connection timed out"
        TransportFailureCode.PERMISSION_DENIED,
        TransportFailureCode.BLE_PERMISSION_DENIED,
        TransportFailureCode.SPP_PERMISSION_DENIED,
        -> "Permission denied"
        TransportFailureCode.BLUETOOTH_DISABLED -> "Bluetooth is disabled"
        TransportFailureCode.BLUETOOTH_UNAVAILABLE -> "Bluetooth is unavailable"
        TransportFailureCode.DEVICE_NOT_FOUND,
        TransportFailureCode.BLE_DEVICE_NOT_FOUND,
        TransportFailureCode.SPP_DEVICE_NOT_PAIRED,
        -> "Selected device is unavailable"
        TransportFailureCode.DEVICE_DETACHED -> "USB device was detached"
        TransportFailureCode.BACKPRESSURE -> "Device queue is full"
        TransportFailureCode.WRITE_TIMEOUT -> "Device write timed out"
        TransportFailureCode.REMOTE_CLOSED -> "Remote device closed the connection"
        else -> "Device connection failed"
    },
    isError = true,
)

private fun defaultAxisSettings(): List<AxisUiSettings> =
    TCodeAxisRegistry.forVersion(TCodeVersion.V0_3).map { descriptor ->
        AxisUiSettings(
            id = descriptor.id,
            name = descriptor.name,
            enabled = descriptor.id.value == "L0",
            minimum = DeviceUiState.DEFAULT_AXIS_MINIMUM,
            maximum = DeviceUiState.DEFAULT_AXIS_MAXIMUM,
            reversed = false,
        )
    }

private fun maximumSafeTestAmplitude(axes: List<AxisUiSettings>, axis: AxisId): Int {
    val settings = axes.firstOrNull { it.id == axis && it.enabled } ?: return 0
    val center = (settings.minimum + settings.maximum) / 2
    return minOf(
        DeviceUiState.MAXIMUM_TEST_AMPLITUDE,
        center - settings.minimum,
        settings.maximum - center,
    ).coerceAtLeast(0)
}
