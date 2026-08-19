package io.github.fplayer.feature.device

import android.content.Context
import io.github.fplayer.core.device.BleDeviceTransport
import io.github.fplayer.core.device.BleDiscovery
import io.github.fplayer.core.device.DeviceTransport
import io.github.fplayer.core.device.RawWebSocketDeviceTransport
import io.github.fplayer.core.device.SppDeviceTransport
import io.github.fplayer.core.device.SppDiscovery
import io.github.fplayer.core.device.TCodeEsp32BleProfile
import io.github.fplayer.core.device.TCodeEsp32SppProfile
import io.github.fplayer.core.device.TcpDeviceTransport
import io.github.fplayer.core.device.TransportConfig
import io.github.fplayer.core.device.TransportListener
import io.github.fplayer.core.device.TransportType
import io.github.fplayer.core.device.UdpDeviceTransport
import io.github.fplayer.core.device.UsbSerialDeviceTransport
import io.github.fplayer.core.device.UsbSerialDiscovery
import io.github.fplayer.core.device.UsbSerialSelector
import java.util.concurrent.atomic.AtomicLong

class AndroidDeviceBackend(context: Context) : DeviceBackend {
    private val applicationContext = context.applicationContext
    private val endpointIds = AtomicLong(0)
    private val endpoints = mutableMapOf<String, DeviceEndpoint>()

    override fun discover(transportType: TransportType): List<DiscoveredDeviceUi> {
        val discovered = when (transportType) {
            TransportType.BLE -> BleDiscovery(applicationContext)
                .scan(
                    serviceUuid = TCodeEsp32BleProfile.serviceUuid,
                    timeoutMs = DISCOVERY_TIMEOUT_MS,
                    name = TCodeEsp32BleProfile.selector().advertisedName,
                )
                .map { result ->
                    DeviceEndpoint.Ble(result.address) to DiscoveredDeviceUi(
                        id = "",
                        title = result.name ?: "TCode BLE",
                        detail = maskBluetoothAddress(result.address),
                        signalStrength = result.rssi,
                    )
                }
            TransportType.BLUETOOTH_SPP -> SppDiscovery(applicationContext)
                .pairedDevices(TCodeEsp32SppProfile.deviceName)
                .map { result ->
                    DeviceEndpoint.Spp(result.address) to DiscoveredDeviceUi(
                        id = "",
                        title = result.name ?: "TCode SPP",
                        detail = maskBluetoothAddress(result.address),
                    )
                }
            TransportType.USB_SERIAL -> UsbSerialDiscovery(applicationContext)
                .discover()
                .flatMap { result ->
                    (0 until result.portCount).map { portIndex ->
                        DeviceEndpoint.Usb(
                            vendorId = result.vendorId,
                            productId = result.productId,
                            deviceId = result.deviceId,
                            portIndex = portIndex,
                        ) to DiscoveredDeviceUi(
                            id = "",
                            title = "USB %04X:%04X".format(result.vendorId, result.productId),
                            detail = "Port ${portIndex + 1} of ${result.portCount}",
                        )
                    }
                }
            else -> emptyList()
        }

        synchronized(endpoints) {
            endpoints.clear()
            return discovered.map { (endpoint, ui) ->
                val id = "endpoint-${endpointIds.incrementAndGet()}"
                endpoints[id] = endpoint
                ui.copy(id = id)
            }
        }
    }

    override fun createTransport(
        request: DeviceConnectionRequest,
        listener: TransportListener,
    ): DeviceTransport {
        val config = TransportConfig(connectTimeoutMs = CONNECTION_TIMEOUT_MS)
        return when (request.transportType) {
            TransportType.TCP -> TcpDeviceTransport(
                host = requireNotNull(request.host),
                port = requireNotNull(request.port),
                config = config,
                listener = listener,
            )
            TransportType.UDP -> UdpDeviceTransport(
                host = requireNotNull(request.host),
                port = requireNotNull(request.port),
                config = config,
                listener = listener,
            )
            TransportType.WEBSOCKET -> RawWebSocketDeviceTransport(
                host = requireNotNull(request.host),
                port = requireNotNull(request.port),
                path = requireNotNull(request.webSocketPath),
                secure = request.secureWebSocket,
                config = config,
                listener = listener,
            )
            TransportType.BLE -> {
                val endpoint = requireEndpoint<DeviceEndpoint.Ble>(request.selectedDeviceId)
                BleDeviceTransport(
                    context = applicationContext,
                    selector = TCodeEsp32BleProfile.selector(confirmedAddress = endpoint.address),
                    config = config,
                    listener = listener,
                )
            }
            TransportType.BLUETOOTH_SPP -> {
                val endpoint = requireEndpoint<DeviceEndpoint.Spp>(request.selectedDeviceId)
                SppDeviceTransport(
                    context = applicationContext,
                    selector = TCodeEsp32SppProfile.selector(endpoint.address),
                    config = config,
                    listener = listener,
                )
            }
            TransportType.USB_SERIAL -> {
                val endpoint = requireEndpoint<DeviceEndpoint.Usb>(request.selectedDeviceId)
                UsbSerialDeviceTransport(
                    context = applicationContext,
                    selector = UsbSerialSelector(
                        vendorId = endpoint.vendorId,
                        productId = endpoint.productId,
                        confirmedDeviceId = endpoint.deviceId,
                        portIndex = endpoint.portIndex,
                    ),
                    config = config,
                    listener = listener,
                )
            }
        }
    }

    private inline fun <reified T : DeviceEndpoint> requireEndpoint(id: String?): T {
        val endpoint = synchronized(endpoints) { id?.let(endpoints::get) }
        return endpoint as? T ?: error("Selected endpoint is no longer available")
    }

    private sealed interface DeviceEndpoint {
        data class Ble(val address: String) : DeviceEndpoint
        data class Spp(val address: String) : DeviceEndpoint
        data class Usb(
            val vendorId: Int,
            val productId: Int,
            val deviceId: Int,
            val portIndex: Int,
        ) : DeviceEndpoint
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 3_000
        const val CONNECTION_TIMEOUT_MS = 3_000

        fun maskBluetoothAddress(address: String): String {
            val suffix = address.split(':').takeLast(3).joinToString(":")
            return "xx:xx:xx:$suffix"
        }
    }
}
