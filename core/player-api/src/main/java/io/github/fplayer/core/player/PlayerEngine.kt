package io.github.fplayer.core.player

import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator

data class PlaybackRequest(
    val mediaId: MediaId,
    val locator: MediaLocator,
    val resumePositionMs: Long = 0,
    val generation: Long = 0,
)

sealed interface PlayerEvent {
    val requestId: Long
    val mediaId: MediaId
    val generation: Long

    data class Prepared(
        override val requestId: Long,
        override val mediaId: MediaId,
        override val generation: Long,
    ) : PlayerEvent

    data class Completed(
        override val requestId: Long,
        override val mediaId: MediaId,
        override val generation: Long,
    ) : PlayerEvent

    data class PrepareFailed(
        override val requestId: Long,
        override val mediaId: MediaId,
        override val generation: Long,
        val errorCode: String,
    ) : PlayerEvent
}

fun interface PlayerEventListener {
    fun onPlayerEvent(event: PlayerEvent)
}

data class PlayerSnapshot(
    val mediaId: MediaId?,
    val positionMs: Long,
    val durationMs: Long?,
    val speed: Double,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
)

interface PlayerEngine {
    fun prepare(request: PlaybackRequest)
    fun setEventListener(listener: PlayerEventListener?)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Double)
    fun snapshot(): PlayerSnapshot
    fun release()
}
