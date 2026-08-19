package io.github.fplayer.core.script

import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.DeviceFramePriority
import io.github.fplayer.core.device.DeviceSafetyConfig
import io.github.fplayer.core.device.DeviceSafetyController
import io.github.fplayer.core.device.DeviceTarget
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.core.device.TCodeVersion
import io.github.fplayer.core.device.AxisSafetyConfig
import io.github.fplayer.core.device.AxisLimit
import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.ScriptAction
import io.github.fplayer.core.player.PlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockScriptSchedulerTest {
    private val clock = MutableClock(snapshot())
    private val controller = RecordingController()
    private val scheduler = MediaClockScriptScheduler(clock, controller)

    @Test
    fun emitsSortedAxisTargetsFromMediaClockLookAhead() {
        scheduler.load(twoAxisBundle())
        scheduler.tick()

        assertEquals(
            listOf(
                Event.Target(axis = "L0", position = 10, durationMs = 100, generation = 1, mediaTimeMs = 100),
                Event.Target(axis = "R0", position = 90, durationMs = 100, generation = 1, mediaTimeMs = 100),
            ),
            controller.events,
        )
    }

    @Test
    fun positiveOffsetDelaysAndNegativeOffsetAdvancesScript() {
        scheduler.load(singleAxisBundle(), ScriptSchedulerConfig(offsetMs = 200))
        scheduler.tick()
        assertTrue(controller.events.isEmpty())

        clock.value = snapshot(positionMs = 100)
        scheduler.tick()
        assertEquals(0, (controller.events.single() as Event.Target).position)

        val advancedController = RecordingController()
        val advanced = MediaClockScriptScheduler(MutableClock(snapshot(positionMs = 100)), advancedController)
        advanced.load(singleAxisBundle(), ScriptSchedulerConfig(offsetMs = -100))
        advanced.tick()
        assertEquals(30, (advancedController.events.single() as Event.Target).position)
    }

    @Test
    fun `automatic offset is applied in script space while media timestamp stays unchanged`() {
        val automaticController = RecordingController()
        val scheduler = MediaClockScriptScheduler(
            clock,
            automaticController,
            automaticOffsetProvider = { -100L },
        )
        scheduler.load(
            singleAxisBundle(),
            ScriptSchedulerConfig(offsetMs = 0, lookAheadMediaMs = 100),
        )

        scheduler.tick()

        assertEquals(Event.Target("L0", 20, 100, 1, 100), automaticController.events.single())
    }

    @Test
    fun `automatic offset provider is called once per valid tick and never by manual output`() {
        var providerCalls = 0
        val scheduler = MediaClockScriptScheduler(
            clock,
            controller,
            automaticOffsetProvider = { providerCalls += 1; 0L },
        )
        scheduler.load(singleAxisBundle())

        scheduler.tick()
        scheduler.submitManual(ManualAxisTarget(AxisId("L0"), 50), allowWhenPaused = true)
        clock.value = snapshot(isPlaying = false)
        scheduler.tick()

        assertEquals(1, providerCalls)
    }

    @Test
    fun `combined offsets saturate before script mapping`() {
        val saturatedController = RecordingController()
        val scheduler = MediaClockScriptScheduler(
            clock,
            saturatedController,
            automaticOffsetProvider = { Long.MAX_VALUE },
        )
        scheduler.load(singleAxisBundle(), ScriptSchedulerConfig(offsetMs = Long.MAX_VALUE))

        scheduler.tick()

        assertTrue(saturatedController.events.isEmpty())
    }

    @Test
    fun `script limits clamp every axis before device safety limits`() {
        scheduler.load(
            twoAxisBundle(),
            ScriptSchedulerConfig(
                scriptOutputLimits = ScriptOutputLimits(
                    mapOf(
                        AxisId("L0") to ScriptAxisOutputRange(30, 40),
                        AxisId("R0") to ScriptAxisOutputRange(60, 70),
                    ),
                ),
                deviceOutputLimits = mapOf(
                    AxisId("L0") to AxisLimit(35, 90),
                    AxisId("R0") to AxisLimit(0, 65),
                ),
            ),
        )
        scheduler.tick()

        assertEquals(listOf(35, 65), controller.events.filterIsInstance<Event.Target>().map { it.position })
    }

    @Test
    fun `default output ranges preserve interpolated targets`() {
        scheduler.load(twoAxisBundle())

        scheduler.tick()

        assertEquals(listOf(10, 90), controller.events.filterIsInstance<Event.Target>().map { it.position })
    }

    @Test
    fun `empty effective output range submits no axis targets`() {
        scheduler.load(
            twoAxisBundle(),
            ScriptSchedulerConfig(
                scriptOutputLimits = ScriptOutputLimits(mapOf(AxisId("L0") to ScriptAxisOutputRange(0, 10))),
                deviceOutputLimits = mapOf(AxisId("L0") to AxisLimit(20, 30)),
            ),
        )

        val error = runCatching { scheduler.tick() }.exceptionOrNull()

        assertEquals("NO_OUTPUT_RANGE", error?.message)
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `manual target is submitted with current generation and bounded duration`() {
        scheduler.load(singleAxisBundle())

        assertTrue(scheduler.submitManual(ManualAxisTarget(AxisId("L0"), 120, durationMs = 900), allowWhenPaused = true))

        assertEquals(Event.Target("L0", 100, 500, 1, 0), controller.events.single())
    }

    @Test
    fun `manual output rejects no bundle and paused session without opt in`() {
        assertFalse(scheduler.submitManual(ManualAxisTarget(AxisId("L0"), 50)))

        scheduler.load(singleAxisBundle())
        clock.value = snapshot(isPlaying = false)
        assertFalse(scheduler.submitManual(ManualAxisTarget(AxisId("L0"), 50)))
        assertTrue(controller.events.isEmpty())
    }

    @Test
    fun `manual output allows explicit paused override through effective range`() {
        scheduler.load(
            singleAxisBundle(),
            ScriptSchedulerConfig(
                scriptOutputLimits = ScriptOutputLimits(mapOf(AxisId("L0") to ScriptAxisOutputRange(20, 40))),
                deviceOutputLimits = mapOf(AxisId("L0") to AxisLimit(30, 35)),
            ),
        )
        clock.value = snapshot(isPlaying = false)

        assertTrue(scheduler.submitManual(ManualAxisTarget(AxisId("L0"), 10), allowWhenPaused = true))

        assertEquals(Event.Target("L0", 30, 250, 1, 0), controller.events.single())
    }

    @Test
    fun `manual output remains per axis monotonic after look ahead scheduling`() {
        val safety = DeviceSafetyController(
            DeviceSafetyConfig(
                version = TCodeVersion.V0_3,
                axes = mapOf(AxisId("L0") to AxisSafetyConfig()),
                minimumFrameIntervalMs = 0,
            ),
        ) { _, _ -> Unit }
        safety.connect()
        val integrated = MediaClockScriptScheduler(clock, safety)
        integrated.load(singleAxisBundle())
        integrated.tick()

        assertTrue(integrated.submitManual(ManualAxisTarget(AxisId("L0"), 50), allowWhenPaused = true))

        assertEquals(1, safety.drain(0, 0, integrated.currentGeneration).sentCommands)
    }

    @Test
    fun pauseStopsOnceAndResumeUsesAdvancedGeneration() {
        scheduler.load(singleAxisBundle())
        scheduler.tick()
        clock.value = snapshot(isPlaying = false)
        scheduler.tick()
        scheduler.tick()

        assertEquals(Event.Stop(StopReason.PLAYBACK_PAUSED), controller.events.last())
        assertEquals(2, controller.events.size)

        clock.value = snapshot(positionMs = 100)
        scheduler.tick()
        assertEquals(2, (controller.events.last() as Event.Target).generation)
    }

    @Test
    fun bufferingStopsOnceAndRequiresFreshGeneration() {
        scheduler.load(singleAxisBundle())
        scheduler.tick()
        clock.value = snapshot(isBuffering = true)
        scheduler.tick()
        scheduler.tick()

        assertEquals(Event.Stop(StopReason.PLAYBACK_BUFFERING), controller.events.last())
        clock.value = snapshot(positionMs = 100)
        scheduler.tick()
        assertEquals(2, (controller.events.last() as Event.Target).generation)
    }

    @Test
    fun speedChangeClearsOldTargetsAndRecomputesWallDuration() {
        scheduler.load(singleAxisBundle())
        scheduler.tick()
        clock.value = snapshot(positionMs = 100, speed = 2.0)
        scheduler.tick()

        assertEquals(Event.Stop(StopReason.PLAYBACK_SPEED_CHANGED), controller.events[1])
        assertEquals(
            Event.Target(axis = "L0", position = 20, durationMs = 50, generation = 2, mediaTimeMs = 200),
            controller.events[2],
        )
    }

    @Test
    fun seekAndLoopDiscardPreviousGenerationsAndResample() {
        scheduler.load(singleAxisBundle())
        scheduler.tick()

        scheduler.onDiscontinuity(PlaybackDiscontinuity.SEEK)
        clock.value = snapshot(positionMs = 500)
        scheduler.tick()
        scheduler.onDiscontinuity(PlaybackDiscontinuity.LOOP)
        clock.value = snapshot(positionMs = 0)
        scheduler.tick()

        assertEquals(Event.Stop(StopReason.PLAYBACK_SEEK), controller.events[1])
        assertEquals(Event.Target("L0", 60, 100, 2, 600), controller.events[2])
        assertEquals(Event.Stop(StopReason.PLAYBACK_LOOP), controller.events[3])
        assertEquals(Event.Target("L0", 10, 100, 3, 100), controller.events[4])
    }

    @Test
    fun sliceMapsMediaWindowToScriptAndStopsAtExclusiveEnd() {
        scheduler.load(
            singleAxisBundle(),
            ScriptSchedulerConfig(
                slice = PlaybackSlice(mediaStartMs = 1_000, mediaEndExclusiveMs = 1_200, scriptStartMs = 0),
            ),
        )
        clock.value = snapshot(positionMs = 999, durationMs = 2_000)
        scheduler.tick()
        assertTrue(controller.events.isEmpty())

        clock.value = snapshot(positionMs = 1_000, durationMs = 2_000)
        scheduler.tick()
        assertEquals(Event.Target("L0", 10, 100, 1, 1_100), controller.events.single())

        clock.value = snapshot(positionMs = 1_200, durationMs = 2_000)
        scheduler.tick()
        scheduler.tick()
        assertEquals(Event.Stop(StopReason.SLICE_ENDED), controller.events.last())
        assertEquals(2, controller.events.size)
    }

    @Test
    fun playbackEndStopsOnceForSnapshotOrExplicitEvent() {
        scheduler.load(singleAxisBundle())
        clock.value = snapshot(positionMs = 900, durationMs = 1_000)
        scheduler.tick()
        clock.value = snapshot(positionMs = 1_000, durationMs = 1_000)
        scheduler.tick()
        scheduler.tick()

        assertEquals(Event.Stop(StopReason.PLAYBACK_ENDED), controller.events.last())
        assertEquals(2, controller.events.size)

        scheduler.load(singleAxisBundle())
        clock.value = snapshot()
        scheduler.tick()
        scheduler.onPlaybackEnded()
        scheduler.onPlaybackEnded()
        assertEquals(Event.Stop(StopReason.PLAYBACK_ENDED), controller.events.last())
    }

    @Test
    fun commandDurationNeverExceedsConfiguredSafetyHorizon() {
        scheduler.load(
            singleAxisBundle(),
            ScriptSchedulerConfig(lookAheadMediaMs = 1_000, maxCommandDurationMs = 500),
        )
        clock.value = snapshot(speed = 0.1, durationMs = 10_000)
        scheduler.tick()

        assertEquals(500, (controller.events.single() as Event.Target).durationMs)
    }

    @Test
    fun replacingAndClearingScriptProduceExplicitStops() {
        scheduler.load(singleAxisBundle())
        scheduler.tick()
        scheduler.load(singleAxisBundle())
        scheduler.clear()

        assertEquals(Event.Stop(StopReason.SCRIPT_CHANGED), controller.events[1])
        assertEquals(Event.Stop(StopReason.USER), controller.events[2])
        assertEquals(3, scheduler.currentGeneration)
    }

    @Test
    fun `config reload stops queued output and advances generation`() {
        scheduler.load(singleAxisBundle())
        scheduler.tick()
        scheduler.load(singleAxisBundle(), ScriptSchedulerConfig(offsetMs = -100))
        scheduler.tick()

        assertEquals(Event.Stop(StopReason.SCRIPT_CHANGED), controller.events[1])
        assertEquals(Event.Target("L0", 20, 100, 2, 100), controller.events[2])
    }

    @Test
    fun schedulerGenerationAndSafetyStopRemainAlignedAcrossSeek() {
        val frames = mutableListOf<Pair<String, DeviceFramePriority>>()
        val safety = DeviceSafetyController(
            DeviceSafetyConfig(
                version = TCodeVersion.V0_3,
                axes = mapOf(AxisId("L0") to AxisSafetyConfig()),
                minimumFrameIntervalMs = 0,
            ),
        ) { frame, priority -> frames += frame.toString(Charsets.US_ASCII) to priority }
        safety.connect()
        val integrated = MediaClockScriptScheduler(clock, safety)

        integrated.load(singleAxisBundle())
        integrated.tick()
        assertEquals(1, safety.drain(0, 0, integrated.currentGeneration).sentCommands)

        integrated.onDiscontinuity(PlaybackDiscontinuity.SEEK)
        clock.value = snapshot(positionMs = 500)
        integrated.tick()
        assertEquals(1, safety.drain(10, 500, integrated.currentGeneration).sentCommands)

        assertEquals(
            listOf(
                "L01000I100\n" to DeviceFramePriority.NORMAL,
                "DSTOP\n" to DeviceFramePriority.EMERGENCY,
                "L05999I100\n" to DeviceFramePriority.NORMAL,
            ),
            frames,
        )
        assertEquals(integrated.currentGeneration, safety.currentGeneration)
    }

    @Test
    fun `scheduler limits still flow through final device safety reversal and clamp`() {
        val frames = mutableListOf<String>()
        val safety = DeviceSafetyController(
            DeviceSafetyConfig(
                version = TCodeVersion.V0_3,
                axes = mapOf(AxisId("L0") to AxisSafetyConfig(AxisLimit(36, 37), reversed = true)),
                minimumFrameIntervalMs = 0,
            ),
        ) { frame, _ -> frames += frame.toString(Charsets.US_ASCII) }
        safety.connect()
        val integrated = MediaClockScriptScheduler(clock, safety)
        integrated.load(
            singleAxisBundle(),
            ScriptSchedulerConfig(
                scriptOutputLimits = ScriptOutputLimits(mapOf(AxisId("L0") to ScriptAxisOutputRange(30, 40))),
                deviceOutputLimits = mapOf(AxisId("L0") to AxisLimit(35, 38)),
            ),
        )

        integrated.tick()
        safety.drain(0, 0, integrated.currentGeneration)

        assertEquals(listOf("L03700I100\n"), frames)
    }

    private fun singleAxisBundle() = ScriptBundle(
        mapOf(
            AxisId("L0") to ScriptTrack(
                AxisId("L0"),
                listOf(ScriptAction(0, 0), ScriptAction(1_000, 100)),
            ),
        ),
    )

    private fun twoAxisBundle(): ScriptBundle {
        val l0 = singleAxisBundle().tracks.getValue(AxisId("L0"))
        val r0 = ScriptTrack(
            AxisId("R0"),
            listOf(ScriptAction(0, 100), ScriptAction(1_000, 0)),
        )
        return ScriptBundle(linkedMapOf(AxisId("R0") to r0, AxisId("L0") to l0))
    }

    private fun snapshot(
        positionMs: Long = 0,
        durationMs: Long? = 2_000,
        speed: Double = 1.0,
        isPlaying: Boolean = true,
        isBuffering: Boolean = false,
    ) = PlayerSnapshot(
        mediaId = null,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
    )

    private class MutableClock(var value: PlayerSnapshot) : PlaybackClock {
        override fun snapshot(): PlayerSnapshot = value
    }

    private class RecordingController : DeviceController {
        val events = mutableListOf<Event>()

        override fun connect() = Unit

        override fun submit(target: DeviceTarget) {
            events += Event.Target(
                target.axis.value,
                target.position,
                target.durationMs,
                target.generation,
                target.mediaTimeMs,
            )
        }

        override fun stop(reason: StopReason) {
            events += Event.Stop(reason)
        }

        override fun disconnect() = Unit
    }

    private sealed interface Event {
        data class Target(
            val axis: String,
            val position: Int,
            val durationMs: Long,
            val generation: Long,
            val mediaTimeMs: Long,
        ) : Event

        data class Stop(val reason: StopReason) : Event
    }
}
