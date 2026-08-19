package io.github.fplayer.feature.device

import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.core.script.LatencyEstimate
import io.github.fplayer.core.script.LatencyCompensationConfig
import io.github.fplayer.core.script.LatencyMeasurementState
import io.github.fplayer.core.script.ManualAxisTarget
import io.github.fplayer.core.script.PlaybackClock
import io.github.fplayer.core.script.PlaybackDiscontinuity
import io.github.fplayer.core.script.ScriptBundle
import io.github.fplayer.core.script.ScriptSchedulerConfig
import io.github.fplayer.core.script.SerializedScriptPlaybackCoordinator
import io.github.fplayer.core.script.effectiveOffsetMs
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Process-scoped bridge between the device route and the playback service. */
class DevicePlaybackCoordinator(
    clock: PlaybackClock,
) : DeviceSessionLifecycle, Closeable {
    private var clock: PlaybackClock = clock
    private val serial: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "device-playback-serial").also { it.isDaemon = true }
    }
    private var controller: DeviceController? = null
    private var scheduler: SerializedScriptPlaybackCoordinator? = null
    private var loadedBundle: ScriptBundle? = null
    private var loadedConfig = ScriptSchedulerConfig()
    private var latencyConfig = LatencyCompensationConfig()
    private var estimate = LatencyEstimate(LatencyMeasurementState.UNMEASURED, 0L, null, 0, null)
    @Volatile private var closed = false

    fun load(bundle: ScriptBundle, config: ScriptSchedulerConfig = ScriptSchedulerConfig()) = submit {
        loadedBundle = bundle
        loadedConfig = config
        scheduler?.load(bundle, config)
    }

    fun tick() = submit { scheduler?.tick() }

    fun onPlaybackEnded() = submit { scheduler?.onPlaybackEnded() }

    fun updateClock(nextClock: PlaybackClock) = submit {
        clock = nextClock
        if (controller == null || scheduler == null) return@submit
        scheduler?.clear(StopReason.SCRIPT_CHANGED)
        scheduler?.close()
        scheduler = SerializedScriptPlaybackCoordinator(
            clock = nextClock,
            controller = controller!!,
            automaticOffsetProvider = { effectiveOffsetMs(latencyConfig, estimate) },
        )
        loadedBundle?.let { scheduler?.load(it, loadedConfig) }
    }

    fun clear(reason: StopReason = StopReason.USER) = submit {
        scheduler?.clear(reason)
    }

    fun onDiscontinuity(discontinuity: PlaybackDiscontinuity) = submit {
        scheduler?.onDiscontinuity(discontinuity)
    }

    fun submitManual(target: ManualAxisTarget, allowWhenPaused: Boolean = false): Boolean {
        if (closed) return false
        var result = false
        serial.submit {
            result = scheduler?.submitManual(target, allowWhenPaused) == true
        }.get()
        return result
    }

    fun onTimingEstimate(next: LatencyEstimate) = submit {
        val previousOffset = effectiveOffsetMs(latencyConfig, estimate)
        estimate = next
        val nextOffset = effectiveOffsetMs(latencyConfig, estimate)
        if (previousOffset != nextOffset && scheduler != null && loadedBundle != null) {
            scheduler?.clear(StopReason.SCRIPT_CHANGED)
            scheduler?.load(loadedBundle!!, loadedConfig)
        }
    }

    override fun onControllerConnected(controller: DeviceController) = submit {
        scheduler?.clear(StopReason.CONNECTION_LOST)
        scheduler?.close()
        this.controller = controller
        scheduler = SerializedScriptPlaybackCoordinator(
            clock = clock,
            controller = controller,
            automaticOffsetProvider = { effectiveOffsetMs(latencyConfig, estimate) },
        )
        loadedBundle?.let { scheduler?.load(it, loadedConfig) }
    }

    override fun onConnectionLost() = submit {
        scheduler?.clear(StopReason.CONNECTION_LOST)
        scheduler?.close()
        scheduler = null
        controller = null
        estimate = LatencyEstimate(LatencyMeasurementState.UNMEASURED, 0L, null, 0, null)
    }

    override fun onSessionClosed() = onConnectionLost()

    override fun close() {
        if (closed) return
        closed = true
        serial.submit {
            scheduler?.clear(StopReason.SERVICE_DESTROYED)
            scheduler?.close()
            controller?.stop(StopReason.SERVICE_DESTROYED)
            controller?.disconnect()
            (controller as? AutoCloseable)?.close()
            scheduler = null
            controller = null
        }.get()
        serial.shutdownNow()
    }

    private fun submit(block: () -> Unit) {
        if (closed) return
        serial.submit(block).get()
    }

}

object DevicePlaybackCoordinatorRegistry {
    private val lock = Any()
    @Volatile private var active: DevicePlaybackCoordinator? = null

    fun install(clock: PlaybackClock): DevicePlaybackCoordinator = synchronized(lock) {
        active?.also { it.updateClock(clock) } ?: DevicePlaybackCoordinator(clock).also { active = it }
    }

    fun currentOrCreate(): DevicePlaybackCoordinator = synchronized(lock) {
        active ?: DevicePlaybackCoordinator {
            io.github.fplayer.core.player.PlayerSnapshot(null, 0L, null, 1.0, false, false)
        }.also { active = it }
    }

    fun remove(coordinator: DevicePlaybackCoordinator) = synchronized(lock) {
        if (active === coordinator) active = null
    }
}
