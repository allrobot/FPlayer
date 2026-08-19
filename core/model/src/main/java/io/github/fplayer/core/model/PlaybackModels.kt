package io.github.fplayer.core.model

data class ScriptAction(
    val atMs: Long,
    val position: Int,
) {
    init {
        require(atMs >= 0) { "Action time must be non-negative" }
        require(position in 0..100) { "Action position must be in 0..100" }
    }
}

enum class ReplayOrigin {
    MANUAL,
    FOREGROUND_AUTO_LOOP,
    BACKGROUND_AUTO_LOOP,
}

data class PlaybackEngagement(
    val mediaId: MediaId,
    val uniqueForegroundPlayedMs: Long,
    val completionCount: Int,
    val manualReplayCount: Int,
    val foregroundLoopCount: Int,
    val backgroundLoopCount: Int,
    val skippedCount: Int,
    val liked: Boolean,
    val disliked: Boolean,
    val favorite: Boolean,
)
