package io.github.fplayer.feature.device

import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.DeviceTarget
import io.github.fplayer.core.device.StopReason
import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.ScriptAction
import io.github.fplayer.core.player.PlayerSnapshot
import io.github.fplayer.core.script.PlaybackClock
import io.github.fplayer.core.script.ScriptBundle
import io.github.fplayer.core.script.ScriptTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlaybackCoordinatorTest {
    @Test
    fun schedulerAndSessionControllerOperationsNeverOverlap() {
        val controller = BlockingController()
        val coordinator = DevicePlaybackCoordinator(
            PlaybackClock {
                PlayerSnapshot(MediaId("synthetic"), 0L, 1_000L, 1.0, true, false)
            },
        )
        coordinator.onControllerConnected(controller)
        coordinator.load(
            ScriptBundle(
                mapOf(
                    AxisId("L0") to ScriptTrack(
                        AxisId("L0"),
                        listOf(ScriptAction(0L, 50), ScriptAction(500L, 60)),
                    ),
                ),
            ),
        )

        val tickFinished = CountDownLatch(1)
        Thread({ coordinator.tick(); tickFinished.countDown() }, "coordinator-tick-test").start()
        assertTrue(controller.submitEntered.await(2, TimeUnit.SECONDS))

        val stopFinished = CountDownLatch(1)
        Thread({
            coordinator.runControllerOperation(controller) { it.stop(StopReason.USER) }
            stopFinished.countDown()
        }, "session-operation-test").start()

        assertFalse(controller.stopEntered.await(150, TimeUnit.MILLISECONDS))
        controller.releaseSubmit.countDown()
        assertTrue(tickFinished.await(2, TimeUnit.SECONDS))
        assertTrue(stopFinished.await(2, TimeUnit.SECONDS))
        assertEquals(1, controller.maximumConcurrentOperations.get())
        coordinator.close()
    }

    @Test
    fun directConnectionLossCallbackClearsActiveSchedulerWithLiveController() {
        val controller = BlockingController().also { it.releaseSubmit.countDown() }
        val coordinator = DevicePlaybackCoordinator(
            PlaybackClock {
                PlayerSnapshot(MediaId("synthetic"), 0L, 1_000L, 1.0, true, false)
            },
        )
        coordinator.onControllerConnected(controller)
        coordinator.load(singleAxisBundle())
        coordinator.tick()

        coordinator.onConnectionLost()

        assertEquals(listOf(StopReason.CONNECTION_LOST), controller.stopReasons)
        coordinator.close()
    }

    @Test
    fun closeKeepsControllerOperationsOnCoordinatorBoundaryUntilTeardownCompletes() {
        val controller = BlockingController().also { it.blockStop = true }
        val coordinator = DevicePlaybackCoordinator(
            PlaybackClock {
                PlayerSnapshot(MediaId("synthetic"), 0L, 1_000L, 1.0, true, false)
            },
        )
        coordinator.onControllerConnected(controller)

        val closeFinished = CountDownLatch(1)
        Thread({ coordinator.close(); closeFinished.countDown() }, "coordinator-close-test")
            .also { it.start() }
        assertTrue(controller.stopEntered.await(2, TimeUnit.SECONDS))

        val operationRan = CountDownLatch(1)
        val operationFinished = CountDownLatch(1)
        Thread({
            assertTrue(coordinator.runControllerOperation(controller) { operationRan.countDown() })
            operationFinished.countDown()
        }, "session-close-race-test").also { it.start() }

        val releaseFinished = CountDownLatch(1)
        Thread({
            assertTrue(coordinator.releaseController(controller, StopReason.CONNECTION_LOST))
            releaseFinished.countDown()
        }, "session-release-race-test").also { it.start() }

        assertFalse(operationRan.await(150, TimeUnit.MILLISECONDS))
        controller.releaseStop.countDown()
        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
        assertTrue(operationFinished.await(2, TimeUnit.SECONDS))
        assertTrue(releaseFinished.await(2, TimeUnit.SECONDS))
        assertFalse(operationRan.await(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf(StopReason.SERVICE_DESTROYED), controller.stopReasons)
    }

    private fun singleAxisBundle() = ScriptBundle(
        mapOf(
            AxisId("L0") to ScriptTrack(
                AxisId("L0"),
                listOf(ScriptAction(0L, 50), ScriptAction(500L, 60)),
            ),
        ),
    )

    private class BlockingController : DeviceController {
        val submitEntered = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val releaseSubmit = CountDownLatch(1)
        val maximumConcurrentOperations = AtomicInteger()
        val stopReasons = CopyOnWriteArrayList<StopReason>()
        val releaseStop = CountDownLatch(1)
        var blockStop = false
        private val activeOperations = AtomicInteger()

        override fun connect() = Unit

        override fun submit(target: DeviceTarget) = operation {
            submitEntered.countDown()
            releaseSubmit.await(2, TimeUnit.SECONDS)
        }

        override fun stop(reason: StopReason) = operation {
            stopReasons += reason
            stopEntered.countDown()
            if (blockStop) releaseStop.await(2, TimeUnit.SECONDS)
        }

        override fun disconnect() = Unit

        private fun operation(block: () -> Unit) {
            val active = activeOperations.incrementAndGet()
            maximumConcurrentOperations.accumulateAndGet(active, ::maxOf)
            try {
                block()
            } finally {
                activeOperations.decrementAndGet()
            }
        }
    }
}
