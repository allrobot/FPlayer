package io.github.fplayer.core.device

import io.github.fplayer.core.model.AxisId
import java.nio.charset.StandardCharsets

enum class StopBehavior {
    HOLD,
    PROTOCOL_STOP,
    CENTER,
}

enum class DeviceFramePriority {
    NORMAL,
    EMERGENCY,
}

fun interface DeviceFrameSink {
    fun write(frame: ByteArray, priority: DeviceFramePriority)
}

data class AxisSafetyConfig(
    val limit: AxisLimit = AxisLimit(0, 100),
    val reversed: Boolean = false,
)

data class DeviceSafetyConfig(
    val version: TCodeVersion,
    val axes: Map<AxisId, AxisSafetyConfig>,
    val stopBehavior: StopBehavior = StopBehavior.PROTOCOL_STOP,
    val centerDurationMs: Long = 250,
    val maximumHorizonMs: Long = 500,
    val minimumFrameIntervalMs: Long = 10,
    val maximumPendingAxes: Int = axes.size,
    val maximumCommandsPerFrame: Int = axes.size,
    val maximumFrameBytes: Int = 512,
) {
    init {
        require(axes.isNotEmpty())
        require(axes.keys.all { TCodeAxisRegistry.find(it, version) != null })
        require(stopBehavior != StopBehavior.PROTOCOL_STOP || version != TCodeVersion.V0_2) {
            "TCode 0.2 does not support DSTOP"
        }
        require(centerDurationMs in 1..500)
        require(maximumHorizonMs in 1..500)
        require(minimumFrameIntervalMs >= 0)
        require(maximumPendingAxes in 1..axes.size)
        require(maximumCommandsPerFrame in 1..axes.size)
        require(maximumFrameBytes >= MINIMUM_FRAME_BYTES)
    }

    private companion object {
        const val MINIMUM_FRAME_BYTES = 11
    }
}

enum class DeviceSafetyErrorCode {
    NOT_CONNECTED,
    UNKNOWN_AXIS,
    NON_MONOTONIC_MEDIA_TIME,
    FUTURE_HORIZON_EXCEEDED,
    INVALID_WALL_TIME,
    QUEUE_OVERFLOW,
    FRAME_TOO_LARGE,
    RELEASED,
}

class DeviceSafetyException(
    val code: DeviceSafetyErrorCode,
    message: String,
) : IllegalStateException(message)

data class DeviceDrainResult(
    val sentCommands: Int,
    val droppedCommands: Int,
    val pendingCommands: Int,
)

class DeviceSafetyController(
    private val config: DeviceSafetyConfig,
    private val sink: DeviceFrameSink,
) : DeviceController, AutoCloseable {
    private val encoder = TCodeEncoder(config.version)
    private val axisConfigs = config.axes.toMap()
    private val pending = linkedMapOf<AxisId, DeviceTarget>()
    private val lastMediaTimeByAxis = mutableMapOf<AxisId, Long>()
    private var generation = 0L
    private var lastSentWallTimeMs: Long? = null
    private var lastDrainWallTimeMs: Long? = null
    private var stopped = true
    private var connected = false
    private var released = false
    private var droppedSinceDrain = 0

    init {
        require(axisConfigs.isNotEmpty())
        require(axisConfigs.keys.all { TCodeAxisRegistry.find(it, config.version) != null })
        require(config.maximumPendingAxes in 1..axisConfigs.size)
        require(config.maximumCommandsPerFrame in 1..axisConfigs.size)
    }

    val currentGeneration: Long get() = generation
    val pendingCount: Int get() = pending.size
    val isStopped: Boolean get() = stopped
    var lastStopReason: StopReason? = null
        private set

    @Synchronized
    override fun connect() {
        ensureNotReleased()
        if (connected) return
        connected = true
        stopped = false
    }

    @Synchronized
    override fun submit(target: DeviceTarget) {
        ensureNotReleased()
        if (!connected) {
            failSubmission(DeviceSafetyErrorCode.NOT_CONNECTED, "Controller is not connected")
        }
        val axisConfig = axisConfigs[target.axis]
            ?: failSubmission(
                DeviceSafetyErrorCode.UNKNOWN_AXIS,
                "Axis ${target.axis.value} is not allowed by the connection profile",
            )

        if (target.generation < generation) {
            droppedSinceDrain += 1
            return
        }
        if (target.generation > generation) {
            droppedSinceDrain += pending.size
            pending.clear()
            lastMediaTimeByAxis.clear()
            generation = target.generation
        }

        val previousMediaTime = lastMediaTimeByAxis[target.axis]
        if (previousMediaTime != null && target.mediaTimeMs < previousMediaTime) {
            failSubmission(
                DeviceSafetyErrorCode.NON_MONOTONIC_MEDIA_TIME,
                "Media time moved backwards for ${target.axis.value}",
            )
        }

        if (target.axis !in pending && pending.size >= config.maximumPendingAxes) {
            failSubmission(
                DeviceSafetyErrorCode.QUEUE_OVERFLOW,
                "Pending axis capacity ${config.maximumPendingAxes} exceeded",
                StopReason.QUEUE_OVERFLOW,
            )
        }

        val limitedPosition = target.position.coerceIn(axisConfig.limit.minimum, axisConfig.limit.maximum)
        val safePosition = if (axisConfig.reversed) {
            axisConfig.limit.minimum + axisConfig.limit.maximum - limitedPosition
        } else {
            limitedPosition
        }
        pending[target.axis] = target.copy(
            position = safePosition,
            durationMs = target.durationMs.coerceAtMost(config.maximumHorizonMs),
        )
        lastMediaTimeByAxis[target.axis] = target.mediaTimeMs
        stopped = false
    }

    @Synchronized
    fun drain(
        wallTimeMs: Long,
        mediaTimeMs: Long,
        currentGeneration: Long,
    ): DeviceDrainResult {
        ensureNotReleased()
        if (!connected) {
            throw DeviceSafetyException(DeviceSafetyErrorCode.NOT_CONNECTED, "Controller is not connected")
        }
        if (wallTimeMs < 0 || mediaTimeMs < 0 || lastDrainWallTimeMs?.let { wallTimeMs < it } == true) {
            return failDrain(DeviceSafetyErrorCode.INVALID_WALL_TIME, "Drain time must be non-negative and monotonic")
        }
        lastDrainWallTimeMs = wallTimeMs

        var dropped = droppedSinceDrain
        droppedSinceDrain = 0
        if (currentGeneration < generation) {
            return DeviceDrainResult(0, dropped, pending.size)
        }
        if (currentGeneration > generation) {
            dropped += pending.size
            pending.clear()
            lastMediaTimeByAxis.clear()
            generation = currentGeneration
        }

        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next().value
            val expired = target.generation < generation || target.generation < currentGeneration ||
                target.mediaTimeMs < mediaTimeMs
            if (expired) {
                iterator.remove()
                dropped += 1
            } else if (target.mediaTimeMs > saturatedAdd(mediaTimeMs, config.maximumHorizonMs)) {
                return failDrain(
                    DeviceSafetyErrorCode.FUTURE_HORIZON_EXCEEDED,
                    "Target horizon exceeds ${config.maximumHorizonMs} ms",
                )
            }
        }

        val lastSent = lastSentWallTimeMs
        if (pending.isEmpty() ||
            (lastSent != null && wallTimeMs - lastSent < config.minimumFrameIntervalMs)
        ) {
            return DeviceDrainResult(0, dropped, pending.size)
        }

        val candidates = pending.values.sortedWith(
            compareBy<DeviceTarget>(DeviceTarget::mediaTimeMs, { it.axis.value }),
        )
        val batch = mutableListOf<DeviceTarget>()
        for (target in candidates) {
            if (batch.size >= config.maximumCommandsPerFrame) break
            val proposed = batch + target
            if (encoder.encodeFrame(proposed.map { it.toAxisTarget() }).size > config.maximumFrameBytes) {
                if (batch.isEmpty()) {
                    return failDrain(DeviceSafetyErrorCode.FRAME_TOO_LARGE, "A single command exceeds the frame limit")
                }
                break
            }
            batch += target
        }

        val frame = encoder.encodeFrame(batch.map { it.toAxisTarget() })
        val pendingBeforeWrite = pending.size
        try {
            sink.write(frame, DeviceFramePriority.NORMAL)
        } catch (_: Exception) {
            emergencyStop(StopReason.APPLICATION_ERROR)
            return DeviceDrainResult(0, dropped + pendingBeforeWrite, pending.size)
        }
        batch.forEach { pending.remove(it.axis) }
        lastSentWallTimeMs = wallTimeMs
        return DeviceDrainResult(batch.size, dropped, pending.size)
    }

    @Synchronized
    override fun stop(reason: StopReason) {
        emergencyStop(reason)
    }

    @Synchronized
    fun onConnectionLost() {
        emergencyStop(StopReason.CONNECTION_LOST)
        connected = false
    }

    @Synchronized
    override fun disconnect() {
        emergencyStop(StopReason.USER)
        connected = false
    }

    @Synchronized
    override fun close() {
        if (released) return
        emergencyStop(StopReason.CONTROLLER_RELEASED)
        connected = false
        released = true
    }

    private fun emergencyStop(reason: StopReason) {
        pending.clear()
        lastMediaTimeByAxis.clear()
        if (stopped) return
        generation = if (generation == Long.MAX_VALUE) Long.MAX_VALUE else generation + 1
        stopped = true
        lastStopReason = reason
        stopFrames().forEach { frame ->
            runCatching { sink.write(frame, DeviceFramePriority.EMERGENCY) }
        }
    }

    private fun stopFrames(): List<ByteArray> = when (config.stopBehavior) {
        StopBehavior.HOLD -> emptyList()
        StopBehavior.PROTOCOL_STOP -> listOf(PROTOCOL_STOP_BYTES.copyOf())
        StopBehavior.CENTER -> {
            val targets = axisConfigs.entries
                .sortedBy { it.key.value }
                .map { (axis, axisConfig) ->
                    TCodeAxisTarget(
                        axis = axis,
                        position = (axisConfig.limit.minimum + axisConfig.limit.maximum) / 2,
                        durationMs = config.centerDurationMs,
                    )
                }
            chunkFrames(targets)
        }
    }

    private fun chunkFrames(targets: List<TCodeAxisTarget>): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var batch = mutableListOf<TCodeAxisTarget>()
        targets.forEach { target ->
            val proposed = batch + target
            val tooMany = proposed.size > config.maximumCommandsPerFrame
            val tooLarge = !tooMany && encoder.encodeFrame(proposed).size > config.maximumFrameBytes
            if (tooMany || tooLarge) {
                if (batch.isNotEmpty()) frames += encoder.encodeFrame(batch)
                batch = mutableListOf(target)
            } else {
                batch += target
            }
        }
        if (batch.isNotEmpty()) frames += encoder.encodeFrame(batch)
        return frames
    }

    private fun failSubmission(
        code: DeviceSafetyErrorCode,
        message: String,
        reason: StopReason = StopReason.APPLICATION_ERROR,
    ): Nothing {
        emergencyStop(reason)
        throw DeviceSafetyException(code, message)
    }

    private fun failDrain(code: DeviceSafetyErrorCode, message: String): DeviceDrainResult {
        val dropped = pending.size + droppedSinceDrain
        droppedSinceDrain = 0
        emergencyStop(StopReason.APPLICATION_ERROR)
        throw DeviceSafetyException(code, "$message; dropped $dropped pending targets")
    }

    private fun ensureNotReleased() {
        if (released) throw DeviceSafetyException(DeviceSafetyErrorCode.RELEASED, "Controller is released")
    }

    private fun saturatedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun DeviceTarget.toAxisTarget() = TCodeAxisTarget(axis, position, durationMs)

    private companion object {
        val PROTOCOL_STOP_BYTES = "DSTOP\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
