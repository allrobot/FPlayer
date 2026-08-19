package io.github.fplayer.core.device

import io.github.fplayer.core.model.AxisId

enum class TransportType {
    TCP,
    UDP,
    WEBSOCKET,
    BLE,
    BLUETOOTH_SPP,
    USB_SERIAL,
}

data class AxisLimit(
    val minimum: Int,
    val maximum: Int,
) {
    init {
        require(minimum in 0..100)
        require(maximum in 0..100)
        require(minimum <= maximum)
    }
}

data class DeviceTarget(
    val axis: AxisId,
    val position: Int,
    val durationMs: Long,
    val generation: Long,
    val mediaTimeMs: Long,
) {
    init {
        require(position in 0..100)
        require(durationMs >= 0)
        require(generation >= 0)
        require(mediaTimeMs >= 0)
    }
}

enum class StopReason {
    USER,
    PLAYBACK_PAUSED,
    PLAYBACK_BUFFERING,
    PLAYBACK_SEEK,
    PLAYBACK_LOOP,
    PLAYBACK_SPEED_CHANGED,
    SLICE_CHANGED,
    SLICE_ENDED,
    SCRIPT_CHANGED,
    PLAYBACK_ENDED,
    QUEUE_OVERFLOW,
    CONNECTION_LOST,
    CONTROLLER_RELEASED,
    SERVICE_DESTROYED,
    APPLICATION_ERROR,
}

interface DeviceController {
    fun connect()
    fun submit(target: DeviceTarget)
    fun stop(reason: StopReason)
    fun disconnect()
}
