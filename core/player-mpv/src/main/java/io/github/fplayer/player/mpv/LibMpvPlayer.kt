package io.github.fplayer.player.mpv

import android.content.Context
import android.view.Surface
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.player.PlaybackRequest
import io.github.fplayer.core.player.PlayerEngine
import io.github.fplayer.core.player.PlayerEvent
import io.github.fplayer.core.player.PlayerEventListener
import io.github.fplayer.core.player.PlayerSnapshot

class LibMpvPlayer internal constructor(
    private val bridge: MpvBridge,
) : PlayerEngine {
    private var preparedMediaId: MediaId? = null
    private var eventListener: PlayerEventListener? = null
    private var nextRequestId = 0L
    private val requests = mutableMapOf<Long, PlaybackRequest>()
    private var released = false

    init {
        bridge.setEventListener(::onBridgeEvent)
    }

    constructor(context: Context) : this(createNativeBridge(context))

    @Synchronized
    override fun prepare(request: PlaybackRequest) {
        checkOpen()
        require(request.resumePositionMs >= 0) { "Resume position must be non-negative" }
        require(request.generation >= 0) { "Generation must be non-negative" }
        preparedMediaId = request.mediaId
        val requestId = ++nextRequestId
        requests.clear()
        requests[requestId] = request
        bridge.prepare(request.locator.value, request.resumePositionMs, requestId)
    }

    @Synchronized
    override fun setEventListener(listener: PlayerEventListener?) {
        checkOpen()
        eventListener = listener
    }

    @Synchronized
    override fun play() {
        checkOpen()
        bridge.play()
    }

    @Synchronized
    override fun pause() {
        checkOpen()
        bridge.pause()
    }

    @Synchronized
    override fun seekTo(positionMs: Long) {
        checkOpen()
        require(positionMs >= 0) { "Seek position must be non-negative" }
        bridge.seekTo(positionMs)
    }

    @Synchronized
    override fun setSpeed(speed: Double) {
        checkOpen()
        require(speed.isFinite() && speed in 0.25..4.0) { "Speed must be finite and in [0.25, 4.0]" }
        bridge.setSpeed(speed)
    }

    @Synchronized
    override fun snapshot(): PlayerSnapshot {
        checkOpen()
        return PlayerSnapshot(
            mediaId = preparedMediaId,
            positionMs = bridge.positionMs(),
            durationMs = bridge.durationMs().takeIf { it > 0 },
            speed = bridge.speed(),
            isPlaying = bridge.isPlaying(),
            isBuffering = bridge.isBuffering(),
        )
    }

    @Synchronized
    fun attachSurface(surface: Surface) {
        checkOpen()
        bridge.attachSurface(surface)
    }

    @Synchronized
    fun detachSurface() {
        checkOpen()
        bridge.detachSurface()
    }

    @Synchronized
    override fun release() {
        if (!released) {
            bridge.setEventListener(null)
            bridge.destroy()
            released = true
            preparedMediaId = null
            eventListener = null
            requests.clear()
        }
    }

    @Synchronized
    private fun onBridgeEvent(event: MpvBridgeEvent) {
        if (released) return
        val request = requests[event.requestId] ?: return
        val playerEvent = when (event.type) {
            MpvBridgeEvent.Type.PREPARED -> PlayerEvent.Prepared(
                event.requestId,
                request.mediaId,
                request.generation,
            )
            MpvBridgeEvent.Type.COMPLETED -> PlayerEvent.Completed(
                event.requestId,
                request.mediaId,
                request.generation,
            )
            MpvBridgeEvent.Type.PREPARE_FAILED -> PlayerEvent.PrepareFailed(
                event.requestId,
                request.mediaId,
                request.generation,
                "MPV_ERROR_${event.errorCode}",
            )
        }
        if (event.type == MpvBridgeEvent.Type.PREPARE_FAILED) requests.remove(event.requestId)
        eventListener?.onPlayerEvent(playerEvent)
    }

    private fun checkOpen() {
        check(!released) { "Player has been released" }
    }

    companion object {
        private fun createNativeBridge(context: Context): MpvBridge {
            NativeMpv.create(context.applicationContext)
            NativeMpv.initialize()
            return NativeMpv
        }
    }
}
