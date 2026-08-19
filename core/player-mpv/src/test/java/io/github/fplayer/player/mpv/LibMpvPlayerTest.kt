package io.github.fplayer.player.mpv

import android.content.Context
import android.view.Surface
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.core.player.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibMpvPlayerTest {
    @Test
    fun delegatesPlaybackOperationsAndProjectsSnapshot() {
        val bridge = FakeBridge()
        val player = LibMpvPlayer(bridge)
        val request = PlaybackRequest(
            mediaId = MediaId("synthetic"),
            locator = MediaLocator("file:///synthetic.mp4"),
            resumePositionMs = 250,
        )

        player.prepare(request)
        player.play()
        player.pause()
        player.seekTo(900)
        player.setSpeed(1.5)

        bridge.position = 910
        bridge.duration = 2_000
        bridge.currentSpeed = 1.5
        bridge.playing = false
        val snapshot = player.snapshot()

        assertEquals(request.mediaId, snapshot.mediaId)
        assertEquals(910L, snapshot.positionMs)
        assertEquals(2_000L, snapshot.durationMs)
        assertEquals(1.5, snapshot.speed, 0.0)
        assertFalse(snapshot.isPlaying)
        assertFalse(snapshot.isBuffering)
        assertEquals(
            listOf("prepare:file:///synthetic.mp4:250", "play", "pause", "seek:900", "speed:1.5"),
            bridge.calls,
        )
    }

    @Test
    fun unknownDurationRemainsNull() {
        val player = LibMpvPlayer(FakeBridge(duration = 0))
        assertNull(player.snapshot().durationMs)
    }

    @Test
    fun validatesInputsAndReleaseIsIdempotent() {
        val bridge = FakeBridge()
        val player = LibMpvPlayer(bridge)

        assertThrows(IllegalArgumentException::class.java) { player.seekTo(-1) }
        assertThrows(IllegalArgumentException::class.java) { player.setSpeed(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { player.setSpeed(4.01) }

        player.release()
        player.release()

        assertEquals(1, bridge.destroyCount)
        assertThrows(IllegalStateException::class.java) { player.play() }
    }

    @Test
    fun staleNativeEventsCannotCrossPreparedRequests() {
        val bridge = FakeBridge()
        val player = LibMpvPlayer(bridge)
        val events = mutableListOf<io.github.fplayer.core.player.PlayerEvent>()
        player.setEventListener(events::add)
        player.prepare(PlaybackRequest(MediaId("a"), MediaLocator("file:///a"), generation = 7))
        val staleRequestId = bridge.lastRequestId
        player.prepare(PlaybackRequest(MediaId("b"), MediaLocator("file:///b"), generation = 8))
        val currentRequestId = bridge.lastRequestId

        bridge.emit(MpvBridgeEvent(staleRequestId, MpvBridgeEvent.Type.PREPARED))
        bridge.emit(MpvBridgeEvent(currentRequestId, MpvBridgeEvent.Type.PREPARED))
        bridge.emit(MpvBridgeEvent(staleRequestId, MpvBridgeEvent.Type.COMPLETED))

        assertEquals(1, events.size)
        assertTrue(events.single() is io.github.fplayer.core.player.PlayerEvent.Prepared)
        assertEquals(MediaId("b"), events.single().mediaId)
        assertEquals(8L, events.single().generation)
    }
}

private class FakeBridge(
    var position: Long = 0,
    var duration: Long = 0,
    var currentSpeed: Double = 1.0,
    var playing: Boolean = false,
) : MpvBridge {
    val calls = mutableListOf<String>()
    var destroyCount = 0
    var lastRequestId = 0L
    private var listener: ((MpvBridgeEvent) -> Unit)? = null

    override fun setEventListener(listener: ((MpvBridgeEvent) -> Unit)?) { this.listener = listener }
    override fun create(context: Context) = Unit
    override fun initialize() = Unit
    override fun destroy() { destroyCount += 1 }
    override fun prepare(locator: String, resumePositionMs: Long, requestId: Long) {
        lastRequestId = requestId
        calls += "prepare:$locator:$resumePositionMs"
    }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun setSpeed(speed: Double) { calls += "speed:$speed" }
    override fun positionMs(): Long = position
    override fun durationMs(): Long = duration
    override fun speed(): Double = currentSpeed
    override fun isPlaying(): Boolean = playing
    override fun isBuffering(): Boolean = false
    override fun attachSurface(surface: Surface) = Unit
    override fun detachSurface() = Unit
    fun emit(event: MpvBridgeEvent) { listener?.invoke(event) }
}
