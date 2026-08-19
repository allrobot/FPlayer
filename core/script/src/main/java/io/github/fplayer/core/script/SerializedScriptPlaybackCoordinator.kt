package io.github.fplayer.core.script

import io.github.fplayer.core.device.DeviceController
import io.github.fplayer.core.device.StopReason
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns one scheduler/controller pair and serializes every lifecycle operation. */
class SerializedScriptPlaybackCoordinator(
    clock: PlaybackClock,
    controller: DeviceController,
    automaticOffsetProvider: () -> Long = { 0L },
    executor: Executor? = null,
) : Closeable {
    private val ownedExecutor = executor == null
    private val executor: Executor = executor ?: Executors.newSingleThreadExecutor { task ->
        Thread(task, "script-playback-serial").also { it.isDaemon = true }
    }
    private val scheduler = MediaClockScriptScheduler(clock, controller, automaticOffsetProvider)
    @Volatile private var closed = false

    val currentGeneration: Long
        get() = call { scheduler.currentGeneration }

    fun load(bundle: ScriptBundle, config: ScriptSchedulerConfig = ScriptSchedulerConfig()) {
        run { scheduler.load(bundle, config) }
    }

    fun tick() {
        run { scheduler.tick() }
    }

    fun onDiscontinuity(discontinuity: PlaybackDiscontinuity) {
        run { scheduler.onDiscontinuity(discontinuity) }
    }

    fun onPlaybackEnded() {
        run { scheduler.onPlaybackEnded() }
    }

    fun clear(reason: StopReason = StopReason.USER) {
        run { scheduler.clear(reason) }
    }

    fun submitManual(target: ManualAxisTarget, allowWhenPaused: Boolean = false): Boolean =
        call { scheduler.submitManual(target, allowWhenPaused) }

    override fun close() {
        if (closed) return
        closed = true
        runUnchecked { scheduler.clear(StopReason.SERVICE_DESTROYED) }
        (executor as? ExecutorService)?.takeIf { ownedExecutor }?.shutdownNow()
    }

    private fun run(block: () -> Unit) {
        call { block(); Unit }
    }

    private fun <T> call(block: () -> T): T {
        check(!closed) { "PLAYBACK_COORDINATOR_CLOSED" }
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        executor.execute {
            result = runCatching(block)
            done.countDown()
        }
        done.await()
        return result!!.getOrThrow()
    }

    private fun runUnchecked(block: () -> Unit) {
        val done = CountDownLatch(1)
        executor.execute {
            runCatching(block)
            done.countDown()
        }
        done.await()
    }
}
