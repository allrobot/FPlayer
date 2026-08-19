package io.github.fplayer.core.script

import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.DeviceTarget
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.ScriptAction
import io.github.fplayer.core.player.PlayerSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptLatencyRecordingIntegrationTest {
    @Test
    fun correlatedEchoEnablesMeasuredOffsetAndKeepsMediaTimeInTheRecording() {
        val estimator = LatencyEstimator()
        val clock = MutableClock(snapshot(positionMs = 0L))
        val controller = RecordingController()
        val config = LatencyCompensationConfig()
        estimator.recordSample(LatencySample(100L, 220L))
        val scheduler = MediaClockScriptScheduler(
            clock = clock,
            controller = controller,
            automaticOffsetProvider = { effectiveOffsetMs(config, estimator.estimate(220L)) },
        )
        scheduler.load(
            ScriptBundle(
                mapOf(
                    AxisId("L0") to ScriptTrack(
                        axis = AxisId("L0"),
                        actions = listOf(
                            ScriptAction(0L, 50),
                            ScriptAction(500L, 90),
                        ),
                    ),
                ),
            ),
            ScriptSchedulerConfig(lookAheadMediaMs = 100L),
        )

        scheduler.tick()

        assertEquals(LatencyMeasurementState.MEASURED, estimator.estimate(220L).state)
        assertEquals(-60L, estimator.estimate(220L).automaticOffsetMs)
        assertEquals(1, controller.targets.size)
        assertEquals(100L, controller.targets.single().mediaTimeMs)
        assertEquals(63, controller.targets.single().position)
    }

    @Test
    fun absentResponseLeavesTransportUnmeasuredAndDoesNotInventOffset() {
        val estimator = LatencyEstimator()
        val estimate = estimator.estimate(500L)

        assertEquals(LatencyMeasurementState.UNMEASURED, estimate.state)
        assertEquals(0L, estimate.automaticOffsetMs)
        assertEquals(null, estimate.medianRoundTripMs)
    }

    @Test
    fun coordinatorSerializesClearAndDoesNotStopTwice() {
        val clock = MutableClock(snapshot(positionMs = 0L))
        val controller = RecordingController()
        val coordinator = SerializedScriptPlaybackCoordinator(clock, controller)
        coordinator.load(singleAxisBundle())
        coordinator.tick()

        coordinator.clear(StopReason.CONNECTION_LOST)
        coordinator.clear(StopReason.CONNECTION_LOST)

        assertEquals(listOf(StopReason.CONNECTION_LOST), controller.stopReasons)
        assertTrue(controller.targets.single().generation < controller.generationAfterClear)
    }

    @Test
    fun playbackDiscontinuitiesStopOnceAndAdvanceGeneration() {
        val cases = listOf<Pair<StopReason, (SerializedScriptPlaybackCoordinator, MutableClock) -> Unit>>(
            StopReason.PLAYBACK_PAUSED to { coordinator, clock ->
                clock.value = snapshot(positionMs = 0L, playing = false)
                coordinator.tick()
            },
            StopReason.PLAYBACK_SEEK to { coordinator, _ ->
                coordinator.onDiscontinuity(PlaybackDiscontinuity.SEEK)
            },
            StopReason.PLAYBACK_SPEED_CHANGED to { coordinator, _ ->
                coordinator.onDiscontinuity(PlaybackDiscontinuity.SPEED_CHANGED)
            },
            StopReason.PLAYBACK_LOOP to { coordinator, _ ->
                coordinator.onDiscontinuity(PlaybackDiscontinuity.LOOP)
            },
            StopReason.CONNECTION_LOST to { coordinator, _ ->
                coordinator.clear(StopReason.CONNECTION_LOST)
            },
            StopReason.USER to { coordinator, _ ->
                coordinator.clear(StopReason.USER)
            },
        )

        cases.forEach { (reason, action) ->
            val clock = MutableClock(snapshot(positionMs = 0L))
            val controller = RecordingController()
            val coordinator = SerializedScriptPlaybackCoordinator(clock, controller)
            coordinator.load(singleAxisBundle())
            coordinator.tick()
            val before = coordinator.currentGeneration

            action(coordinator, clock)

            assertEquals(reason, controller.stopReasons.single())
            assertTrue(coordinator.currentGeneration > before)
        }
    }

    private fun singleAxisBundle() = ScriptBundle(
        mapOf(
            AxisId("L0") to ScriptTrack(
                axis = AxisId("L0"),
                actions = listOf(ScriptAction(0L, 50), ScriptAction(500L, 60)),
            ),
        ),
    )

    private fun snapshot(positionMs: Long, playing: Boolean = true) = PlayerSnapshot(
        mediaId = MediaId("synthetic"),
        positionMs = positionMs,
        durationMs = 1_000L,
        speed = 1.0,
        isPlaying = playing,
        isBuffering = false,
    )

    private class MutableClock(var value: PlayerSnapshot) : PlaybackClock {
        override fun snapshot(): PlayerSnapshot = value
    }

    private class RecordingController : DeviceController {
        val targets = CopyOnWriteArrayList<DeviceTarget>()
        val stopReasons = CopyOnWriteArrayList<StopReason>()
        var generationAfterClear = 0L

        override fun connect() = Unit
        override fun submit(target: DeviceTarget) { targets += target }
        override fun stop(reason: StopReason) {
            stopReasons += reason
            generationAfterClear = targets.maxOfOrNull { it.generation + 1L } ?: 1L
        }
        override fun disconnect() = Unit
    }
}
