package io.github.fplayer.core.script

import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.DeviceTarget
import io.github.fplayer.core.device.AxisLimit
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.player.PlayerSnapshot
import kotlin.math.ceil
import kotlin.math.min

fun interface PlaybackClock {
    fun snapshot(): PlayerSnapshot
}

data class PlaybackSlice(
    val mediaStartMs: Long,
    val mediaEndExclusiveMs: Long,
    val scriptStartMs: Long = mediaStartMs,
) {
    init {
        require(mediaStartMs >= 0)
        require(mediaEndExclusiveMs > mediaStartMs)
        require(scriptStartMs >= 0)
    }
}

data class ScriptSchedulerConfig(
    val offsetMs: Long = 0,
    val lookAheadMediaMs: Long = 100,
    val maxCommandDurationMs: Long = 500,
    val slice: PlaybackSlice? = null,
    val scriptOutputLimits: ScriptOutputLimits = ScriptOutputLimits(),
    val deviceOutputLimits: Map<AxisId, AxisLimit> = emptyMap(),
) {
    init {
        require(lookAheadMediaMs > 0)
        require(maxCommandDurationMs in 1..500)
    }
}

enum class PlaybackDiscontinuity(
    internal val stopReason: StopReason,
) {
    SEEK(StopReason.PLAYBACK_SEEK),
    LOOP(StopReason.PLAYBACK_LOOP),
    SLICE_CHANGE(StopReason.SLICE_CHANGED),
}

class MediaClockScriptScheduler(
    private val clock: PlaybackClock,
    private val controller: DeviceController,
    private val automaticOffsetProvider: () -> Long = { 0L },
) {
    private var bundle: ScriptBundle? = null
    private var config = ScriptSchedulerConfig()
    private var generation = 0L
    private var active = false
    private var outputMode = OutputMode.NONE
    private var lastSpeed: Double? = null
    private val lastSubmittedMediaTimeByAxis = mutableMapOf<AxisId, Long>()

    val currentGeneration: Long get() = generation

    fun load(bundle: ScriptBundle, config: ScriptSchedulerConfig = ScriptSchedulerConfig()) {
        if (this.bundle != null) stopAndAdvance(StopReason.SCRIPT_CHANGED)
        else generation += 1
        this.bundle = bundle
        this.config = config
        active = false
        outputMode = OutputMode.NONE
        lastSpeed = null
        lastSubmittedMediaTimeByAxis.clear()
    }

    fun onDiscontinuity(discontinuity: PlaybackDiscontinuity) {
        if (bundle == null) return
        stopAndAdvance(discontinuity.stopReason)
    }

    fun onPlaybackEnded() {
        deactivate(StopReason.PLAYBACK_ENDED)
    }

    fun clear(reason: StopReason = StopReason.USER) {
        if (bundle != null) stopAndAdvance(reason)
        bundle = null
        active = false
        outputMode = OutputMode.NONE
        lastSpeed = null
        lastSubmittedMediaTimeByAxis.clear()
    }

    fun tick() {
        val currentBundle = bundle ?: return
        val snapshot = clock.snapshot()
        if (!snapshot.isPlaying) {
            deactivate(StopReason.PLAYBACK_PAUSED)
            return
        }
        if (snapshot.isBuffering) {
            deactivate(StopReason.PLAYBACK_BUFFERING)
            return
        }
        if (!snapshot.speed.isFinite() || snapshot.speed <= 0.0 || snapshot.positionMs < 0) {
            deactivate(StopReason.APPLICATION_ERROR)
            return
        }
        if (snapshot.durationMs?.let { snapshot.positionMs >= it } == true) {
            deactivate(StopReason.PLAYBACK_ENDED)
            return
        }

        val slice = config.slice
        if (slice != null && snapshot.positionMs >= slice.mediaEndExclusiveMs) {
            deactivate(StopReason.SLICE_ENDED)
            return
        }
        if (slice != null && snapshot.positionMs < slice.mediaStartMs) return

        if (outputMode == OutputMode.MANUAL) {
            stopAndAdvance(StopReason.SCRIPT_CHANGED)
        }

        val previousSpeed = lastSpeed
        if (active && previousSpeed != null && previousSpeed != snapshot.speed) {
            stopAndAdvance(StopReason.PLAYBACK_SPEED_CHANGED)
        }

        val futureMediaTime = futureMediaTime(snapshot, slice)
        val effectiveOffsetMs = saturatedAdd(config.offsetMs, automaticOffsetProvider())
        val scriptTime = mapToScriptTime(futureMediaTime, slice, effectiveOffsetMs)
        if (scriptTime < 0) return
        val durationMs = commandDuration(snapshot.positionMs, futureMediaTime, snapshot.speed)

        val targets = currentBundle.tracks.toSortedMap(compareBy { it.value }).mapNotNull { (axis, track) ->
            val position = ScriptInterpolator.positionAt(track, scriptTime) ?: return@mapNotNull null
            DeviceTarget(
                axis = axis,
                position = effectivePosition(axis, position),
                durationMs = durationMs,
                generation = generation,
                mediaTimeMs = futureMediaTime,
            )
        }
        targets.forEach(controller::submit)
        targets.forEach { lastSubmittedMediaTimeByAxis[it.axis] = it.mediaTimeMs }
        val submitted = targets.isNotEmpty()
        active = submitted
        outputMode = if (submitted) OutputMode.SCRIPTED else OutputMode.NONE
        lastSpeed = snapshot.speed
    }

    fun submitManual(target: ManualAxisTarget, allowWhenPaused: Boolean = false): Boolean {
        if (bundle == null) return false
        val snapshot = clock.snapshot()
        if (!isManualSnapshotValid(snapshot) || (!snapshot.isPlaying && !allowWhenPaused)) return false
        if (outputMode == OutputMode.SCRIPTED) {
            stopAndAdvance(if (snapshot.isPlaying) StopReason.USER else StopReason.PLAYBACK_PAUSED)
        }
        val mediaTimeMs = maxOf(
            snapshot.positionMs,
            lastSubmittedMediaTimeByAxis[target.axis] ?: 0L,
        )
        val durationMs = target.durationMs.coerceIn(1L, config.maxCommandDurationMs)
        val deviceTarget = DeviceTarget(
            axis = target.axis,
            position = effectivePosition(target.axis, target.position),
            durationMs = durationMs,
            generation = generation,
            mediaTimeMs = mediaTimeMs,
        )
        controller.submit(deviceTarget)
        lastSubmittedMediaTimeByAxis[target.axis] = mediaTimeMs
        active = true
        outputMode = OutputMode.MANUAL
        return true
    }

    private fun isManualSnapshotValid(snapshot: PlayerSnapshot): Boolean {
        if (snapshot.isBuffering || !snapshot.speed.isFinite() || snapshot.speed <= 0.0 || snapshot.positionMs < 0) {
            return false
        }
        return snapshot.durationMs?.let { it >= 0 && snapshot.positionMs < it } ?: true
    }

    private fun effectivePosition(axis: AxisId, position: Int): Int {
        val scriptRange = config.scriptOutputLimits.rangeFor(axis)
        val deviceRange = config.deviceOutputLimits[axis]
        return if (deviceRange == null) {
            applyScriptRange(position, scriptRange)
        } else {
            val intersection = intersectAxisRanges(scriptRange, deviceRange)
            position.coerceIn(intersection.minimum, intersection.maximum)
        }
    }

    private fun futureMediaTime(snapshot: PlayerSnapshot, slice: PlaybackSlice?): Long {
        var future = saturatedAdd(snapshot.positionMs, config.lookAheadMediaMs)
        snapshot.durationMs?.let { future = min(future, it) }
        slice?.let { future = min(future, it.mediaEndExclusiveMs - 1) }
        return future.coerceAtLeast(snapshot.positionMs)
    }

    private fun mapToScriptTime(mediaTimeMs: Long, slice: PlaybackSlice?, offsetMs: Long): Long {
        val base = if (slice == null) {
            mediaTimeMs
        } else {
            saturatedAdd(slice.scriptStartMs, mediaTimeMs - slice.mediaStartMs)
        }
        return saturatedSubtract(base, offsetMs)
    }

    private fun commandDuration(currentMediaMs: Long, futureMediaMs: Long, speed: Double): Long {
        val mediaDelta = (futureMediaMs - currentMediaMs).coerceAtLeast(1)
        return ceil(mediaDelta / speed)
            .toLong()
            .coerceIn(1, config.maxCommandDurationMs)
    }

    private fun deactivate(reason: StopReason) {
        if (!active) return
        stopAndAdvance(reason)
    }

    private fun stopAndAdvance(reason: StopReason) {
        generation += 1
        controller.stop(reason)
        active = false
        outputMode = OutputMode.NONE
        lastSpeed = null
        lastSubmittedMediaTimeByAxis.clear()
    }

    private fun saturatedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        if (right >= 0) Long.MAX_VALUE else Long.MIN_VALUE
    }

    private fun saturatedSubtract(left: Long, right: Long): Long = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        if (right < 0) Long.MAX_VALUE else Long.MIN_VALUE
    }

    private enum class OutputMode {
        NONE,
        SCRIPTED,
        MANUAL,
    }
}
