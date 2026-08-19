package io.github.fplayer.core.device

import java.io.Closeable
import java.io.IOException

enum class TransportState { DISCONNECTED, CONNECTING, CONNECTED, FAILED, CLOSED }

enum class TransportFailureCode {
    CONNECT_TIMEOUT, CONNECT_FAILED, WRITE_TIMEOUT, WRITE_FAILED, REMOTE_CLOSED,
    FRAME_TOO_LARGE, BACKPRESSURE, PROTOCOL_ERROR, PERMISSION_DENIED,
    DEVICE_NOT_FOUND, DEVICE_DETACHED, UNSUPPORTED_DEVICE, CONFIGURATION_FAILED,
    READ_FAILED, BLUETOOTH_DISABLED, BLUETOOTH_UNAVAILABLE, BLE_PERMISSION_DENIED,
    BLE_DEVICE_NOT_FOUND, BLE_SERVICE_NOT_FOUND, BLE_CHARACTERISTIC_NOT_FOUND,
    BLE_SCAN_FAILED, BLE_MTU_FAILED, BLE_SUBSCRIPTION_FAILED, BLE_GATT_FAILED,
    SPP_PERMISSION_DENIED, SPP_DEVICE_NOT_PAIRED, SPP_SOCKET_FAILED,
}

data class TransportFailure(val code: TransportFailureCode, val message: String)

internal class TransportException(
    val failureCode: TransportFailureCode,
    val safeMessage: String,
    cause: Throwable? = null,
) : IOException(safeMessage, cause)

fun interface TransportListener {
    fun onStateChanged(state: TransportState, failure: TransportFailure?)
}

fun interface TransportReceiver {
    fun onBytesReceived(bytes: ByteArray)
}

data class TransportConfig(
    val connectTimeoutMs: Int = 1_000,
    val writeTimeoutMs: Int = 1_000,
    val maximumFrameBytes: Int = 512,
    val maximumPendingFrames: Int = 8,
) {
    init {
        require(connectTimeoutMs > 0)
        require(writeTimeoutMs > 0)
        require(maximumFrameBytes > 0)
        require(maximumPendingFrames > 0)
    }
}

interface DeviceTransport : DeviceFrameSink, Closeable {
    val state: TransportState
    val pendingFrameCount: Int
    val diagnostics: String
    fun connect()
    fun disconnect()
}

internal data class QueuedFrame(val bytes: ByteArray, val priority: DeviceFramePriority)

internal class BoundedFrameQueue(private val capacity: Int) {
    private val frames = ArrayDeque<QueuedFrame>()

    @Synchronized
    fun offer(frame: QueuedFrame): Boolean {
        if (frame.priority == DeviceFramePriority.EMERGENCY) {
            frames.removeAll { it.priority == DeviceFramePriority.NORMAL }
            if (frames.size >= capacity) return false
            frames.addLast(frame)
            return true
        }
        if (frames.size >= capacity) return false
        frames.addLast(frame)
        return true
    }

    @Synchronized fun poll(): QueuedFrame? = frames.removeFirstOrNull()
    @Synchronized fun clear() = frames.clear()
    @Synchronized fun size(): Int = frames.size
}
