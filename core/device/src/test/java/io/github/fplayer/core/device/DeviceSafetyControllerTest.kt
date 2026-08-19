package io.github.fplayer.core.device

import io.github.fplayer.core.model.AxisId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSafetyControllerTest {
    private val sink = RecordingSink()

    @Test
    fun `limits reverses conflates and emits deterministic frame`() {
        val controller = controller(
            axes = mapOf(
                "L0" to AxisSafetyConfig(AxisLimit(20, 80), reversed = true),
                "R0" to AxisSafetyConfig(),
            ),
        )
        controller.submit(target("L0", 10, mediaTimeMs = 100))
        controller.submit(target("L0", 30, mediaTimeMs = 110))
        controller.submit(target("R0", 75, mediaTimeMs = 110))

        val result = controller.drain(wallTimeMs = 0, mediaTimeMs = 0, currentGeneration = 1)

        assertEquals(DeviceDrainResult(2, 0, 0), result)
        assertEquals(listOf("L06999I100 R07499I100\n"), sink.normalFrames())
    }

    @Test
    fun `rate limit leaves commands pending until interval elapses`() {
        val controller = controller(minimumFrameIntervalMs = 10)
        controller.submit(target("L0", 10, mediaTimeMs = 100))
        controller.drain(0, 0, 1)
        controller.submit(target("L0", 20, mediaTimeMs = 200))

        assertEquals(0, controller.drain(5, 100, 1).sentCommands)
        assertEquals(1, controller.pendingCount)
        assertEquals(1, controller.drain(10, 100, 1).sentCommands)
    }

    @Test
    fun `duration is clamped to the configured safety horizon`() {
        val controller = controller()
        controller.submit(target("L0", 50, durationMs = 5_000, mediaTimeMs = 500))

        controller.drain(0, 0, 1)

        assertEquals(listOf("L05000I500\n"), sink.normalFrames())
    }

    @Test
    fun `old generations and expired targets are dropped`() {
        val controller = controller()
        controller.submit(target("L0", 10, generation = 2, mediaTimeMs = 100))
        controller.submit(target("L0", 20, generation = 1, mediaTimeMs = 200))

        val result = controller.drain(0, 101, 2)

        assertEquals(DeviceDrainResult(0, 2, 0), result)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `generation advance clears pending targets`() {
        val controller = controller()
        controller.submit(target("L0", 10, generation = 1, mediaTimeMs = 100))

        val result = controller.drain(0, 0, 2)

        assertEquals(DeviceDrainResult(0, 1, 0), result)
        assertEquals(2, controller.currentGeneration)
    }

    @Test
    fun `stale drain cannot send a future generation`() {
        val controller = controller()
        controller.submit(target("L0", 10, generation = 2, mediaTimeMs = 100))

        assertEquals(DeviceDrainResult(0, 0, 1), controller.drain(0, 0, 1))
        assertTrue(sink.events.isEmpty())
        assertEquals(1, controller.drain(1, 0, 2).sentCommands)
    }

    @Test
    fun `higher submitted generation counts conflated old work as dropped`() {
        val controller = controller(
            axes = mapOf("L0" to AxisSafetyConfig(), "R0" to AxisSafetyConfig()),
        )
        controller.submit(target("L0", 10, generation = 1, mediaTimeMs = 100))
        controller.submit(target("R0", 20, generation = 2, mediaTimeMs = 100))

        assertEquals(DeviceDrainResult(1, 1, 0), controller.drain(0, 0, 2))
    }

    @Test
    fun `future horizon and backwards media time stop once`() {
        val horizonController = controller()
        horizonController.submit(target("L0", 10, mediaTimeMs = 501))
        assertFailure(DeviceSafetyErrorCode.FUTURE_HORIZON_EXCEEDED) {
            horizonController.drain(0, 0, 1)
        }
        horizonController.stop(StopReason.APPLICATION_ERROR)
        assertEquals(listOf("DSTOP\n"), sink.emergencyFrames())

        val otherSink = RecordingSink()
        val monotonicController = controller(sink = otherSink)
        monotonicController.submit(target("L0", 10, mediaTimeMs = 100))
        assertFailure(DeviceSafetyErrorCode.NON_MONOTONIC_MEDIA_TIME) {
            monotonicController.submit(target("L0", 20, mediaTimeMs = 99))
        }
        assertEquals(listOf("DSTOP\n"), otherSink.emergencyFrames())
    }

    @Test
    fun `future horizon check does not overflow at long max`() {
        val controller = controller()
        controller.submit(target("L0", 10, mediaTimeMs = Long.MAX_VALUE))

        assertEquals(1, controller.drain(0, Long.MAX_VALUE, 1).sentCommands)
    }

    @Test
    fun `queue overflow clears normal work and emits one emergency stop`() {
        val controller = controller(
            axes = mapOf("L0" to AxisSafetyConfig(), "R0" to AxisSafetyConfig()),
            maximumPendingAxes = 1,
        )
        controller.submit(target("L0", 10, mediaTimeMs = 100))

        assertFailure(DeviceSafetyErrorCode.QUEUE_OVERFLOW) {
            controller.submit(target("R0", 20, mediaTimeMs = 100))
        }
        controller.stop(StopReason.QUEUE_OVERFLOW)

        assertEquals(0, controller.pendingCount)
        assertEquals(listOf("DSTOP\n"), sink.emergencyFrames())
        assertEquals(2, controller.currentGeneration)
        assertEquals(StopReason.QUEUE_OVERFLOW, controller.lastStopReason)
    }

    @Test
    fun `command and byte budgets leave remaining axes for later drains`() {
        val controller = controller(
            axes = mapOf(
                "L0" to AxisSafetyConfig(),
                "R0" to AxisSafetyConfig(),
                "V0" to AxisSafetyConfig(),
            ),
            maximumCommandsPerFrame = 2,
            maximumFrameBytes = 22,
        )
        controller.submit(target("V0", 30, mediaTimeMs = 100))
        controller.submit(target("R0", 20, mediaTimeMs = 100))
        controller.submit(target("L0", 10, mediaTimeMs = 100))

        assertEquals(DeviceDrainResult(2, 0, 1), controller.drain(0, 0, 1))
        assertEquals(DeviceDrainResult(1, 0, 0), controller.drain(1, 0, 1))
        assertEquals(
            listOf("L01000I100 R02000I100\n", "V03000I100\n"),
            sink.normalFrames(),
        )
    }

    @Test
    fun `unknown profile axis stops before any normal frame`() {
        val controller = controller()

        assertFailure(DeviceSafetyErrorCode.UNKNOWN_AXIS) {
            controller.submit(target("R0", 20, mediaTimeMs = 100))
        }

        assertEquals(listOf("DSTOP\n"), sink.emergencyFrames())
        assertTrue(sink.normalFrames().isEmpty())
    }

    @Test
    fun `normal sink failure is followed by one emergency attempt`() {
        val failingSink = RecordingSink(failFirstNormal = true)
        val controller = controller(sink = failingSink)
        controller.submit(target("L0", 10, mediaTimeMs = 100))

        val result = controller.drain(0, 0, 1)
        controller.stop(StopReason.APPLICATION_ERROR)

        assertEquals(0, result.sentCommands)
        assertEquals(0, controller.pendingCount)
        assertEquals(
            listOf(DeviceFramePriority.NORMAL, DeviceFramePriority.EMERGENCY),
            failingSink.events.map { it.priority },
        )
        assertEquals("DSTOP\n", failingSink.emergencyFrames().single())
    }

    @Test
    fun `connection loss disconnect and release are idempotent stops`() {
        val connectionSink = RecordingSink()
        val lost = controller(sink = connectionSink)
        lost.submit(target("L0", 10, mediaTimeMs = 100))
        lost.onConnectionLost()
        lost.onConnectionLost()
        assertEquals(listOf("DSTOP\n"), connectionSink.emergencyFrames())
        assertFailure(DeviceSafetyErrorCode.NOT_CONNECTED) {
            lost.submit(target("L0", 20, generation = 2, mediaTimeMs = 200))
        }
        assertFailure(DeviceSafetyErrorCode.NOT_CONNECTED) {
            lost.drain(0, 0, 2)
        }

        val releaseSink = RecordingSink()
        val released = controller(sink = releaseSink)
        released.submit(target("L0", 10, mediaTimeMs = 100))
        released.close()
        released.close()
        assertEquals(listOf("DSTOP\n"), releaseSink.emergencyFrames())
        assertFailure(DeviceSafetyErrorCode.RELEASED) {
            released.submit(target("L0", 20, generation = 2, mediaTimeMs = 200))
        }
    }

    @Test
    fun `center stop is range aware and chunks emergency frames`() {
        val centerSink = RecordingSink()
        val controller = controller(
            sink = centerSink,
            axes = mapOf(
                "L0" to AxisSafetyConfig(AxisLimit(20, 60)),
                "R0" to AxisSafetyConfig(AxisLimit(40, 80)),
            ),
            stopBehavior = StopBehavior.CENTER,
            maximumCommandsPerFrame = 1,
        )
        controller.submit(target("L0", 20, mediaTimeMs = 100))
        controller.stop(StopReason.USER)

        assertEquals(listOf("L04000I250\n", "R05999I250\n"), centerSink.emergencyFrames())
    }

    @Test
    fun `version 0_2 requires a supported stop behavior`() {
        val axes = mapOf(AxisId("L0") to AxisSafetyConfig())
        runCatching {
            DeviceSafetyConfig(TCodeVersion.V0_2, axes, StopBehavior.PROTOCOL_STOP)
        }.onSuccess { throw AssertionError("Expected configuration failure") }

        val holdSink = RecordingSink()
        val hold = DeviceSafetyController(
            DeviceSafetyConfig(TCodeVersion.V0_2, axes, StopBehavior.HOLD),
            holdSink,
        )
        hold.connect()
        hold.submit(target("L0", 10, mediaTimeMs = 100))
        hold.stop(StopReason.USER)
        assertTrue(holdSink.events.isEmpty())
        assertTrue(hold.isStopped)
    }

    private fun controller(
        sink: RecordingSink = this.sink,
        axes: Map<String, AxisSafetyConfig> = mapOf("L0" to AxisSafetyConfig()),
        stopBehavior: StopBehavior = StopBehavior.PROTOCOL_STOP,
        minimumFrameIntervalMs: Long = 0,
        maximumPendingAxes: Int = axes.size,
        maximumCommandsPerFrame: Int = axes.size,
        maximumFrameBytes: Int = 512,
    ) = DeviceSafetyController(
        DeviceSafetyConfig(
            version = TCodeVersion.V0_3,
            axes = axes.mapKeys { AxisId(it.key) },
            stopBehavior = stopBehavior,
            minimumFrameIntervalMs = minimumFrameIntervalMs,
            maximumPendingAxes = maximumPendingAxes,
            maximumCommandsPerFrame = maximumCommandsPerFrame,
            maximumFrameBytes = maximumFrameBytes,
        ),
        sink,
    ).also { it.connect() }

    private fun target(
        axis: String,
        position: Int,
        generation: Long = 1,
        durationMs: Long = 100,
        mediaTimeMs: Long,
    ) = DeviceTarget(AxisId(axis), position, durationMs, generation, mediaTimeMs)

    private fun assertFailure(expected: DeviceSafetyErrorCode, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull() as DeviceSafetyException
        assertEquals(expected, error.code)
    }

    private data class SinkEvent(val text: String, val priority: DeviceFramePriority)

    private class RecordingSink(
        private val failFirstNormal: Boolean = false,
    ) : DeviceFrameSink {
        val events = mutableListOf<SinkEvent>()
        private var failed = false

        override fun write(frame: ByteArray, priority: DeviceFramePriority) {
            events += SinkEvent(frame.toString(Charsets.US_ASCII), priority)
            if (failFirstNormal && priority == DeviceFramePriority.NORMAL && !failed) {
                failed = true
                throw IllegalStateException("synthetic write failure")
            }
        }

        fun normalFrames() = events.filter { it.priority == DeviceFramePriority.NORMAL }.map { it.text }
        fun emergencyFrames() = events.filter { it.priority == DeviceFramePriority.EMERGENCY }.map { it.text }
    }
}
