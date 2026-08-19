package io.github.fplayer.feature.feed

/**
 * App-facing boundary for progress gestures. The UI owns pointer events and the playback layer
 * owns the actual seek; callbacks carry the state-machine generation token across that boundary.
 */
class PlaybackProgressBinding(
    durationMs: Long,
    positionMs: Long = 0L,
    mode: ProgressInteractionMode = ProgressInteractionMode.BAR,
    private val onSeekRequested: (positionMs: Long, token: Long) -> Unit,
) {
    private val interaction = ProgressInteractionStateMachine(durationMs, positionMs, mode)

    fun setMode(mode: ProgressInteractionMode) = interaction.setMode(mode)

    fun updateMediaClock(positionMs: Long, durationMs: Long = interaction.snapshot().durationMs) {
        interaction.updateDuration(durationMs)
        interaction.updateMediaPosition(positionMs)
    }

    fun onPointerDown() = interaction.onPointerDown()

    fun onPointerMove(progress: Float) = interaction.onPointerMove(progress)

    fun onPointerUp() {
        val token = interaction.onPointerUp() ?: return
        val position = interaction.snapshot().submittedPositionMs ?: return
        onSeekRequested(position, token)
    }

    fun onSeekConfirmed(token: Long, positionMs: Long): Boolean =
        interaction.onSeekConfirmed(token, positionMs)

    fun onSeekFailed(token: Long): Boolean = interaction.onSeekFailed(token)

    fun consumeHapticEdge(): ProgressHapticEdge = interaction.consumeHapticEdge()

    fun snapshot(): ProgressInteractionSnapshot = interaction.snapshot()
}
