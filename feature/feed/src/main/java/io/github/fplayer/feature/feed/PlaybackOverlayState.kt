package io.github.fplayer.feature.feed

enum class PlaybackOverlayMode { NORMAL, CLEAN }

data class PlaybackOverlayState(
    val mode: PlaybackOverlayMode = PlaybackOverlayMode.NORMAL,
    val isPlaying: Boolean = false,
    val deviceConnected: Boolean = false,
    val deviceStopped: Boolean = false,
    val isFavorite: Boolean = false,
    val isBookmarked: Boolean = false,
    val isDisliked: Boolean = false,
    val speed: Float = 1f,
    val title: String = "",
    val folder: String = "",
    val summary: String = "",
) {
    init {
        require(speed in 0.1f..5f)
    }
}

enum class PlaybackOverlayAction {
    TogglePlayPause,
    ToggleCleanScreen,
    SeekBack,
    CycleSpeed,
    ToggleFavorite,
    ToggleBookmark,
    ToggleDislike,
    StopDevice,
    OpenDrawer,
    OpenSource,
    OpenSearch,
    OpenMore,
}

object PlaybackOverlayReducer {
    fun reduce(state: PlaybackOverlayState, action: PlaybackOverlayAction): PlaybackOverlayState = when (action) {
        PlaybackOverlayAction.TogglePlayPause -> state.copy(isPlaying = !state.isPlaying)
        PlaybackOverlayAction.ToggleCleanScreen -> state.copy(
            mode = if (state.mode == PlaybackOverlayMode.NORMAL) PlaybackOverlayMode.CLEAN else PlaybackOverlayMode.NORMAL,
        )
        PlaybackOverlayAction.CycleSpeed -> state.copy(speed = nextSpeed(state.speed))
        PlaybackOverlayAction.ToggleFavorite -> state.copy(isFavorite = !state.isFavorite)
        PlaybackOverlayAction.ToggleBookmark -> state.copy(isBookmarked = !state.isBookmarked)
        PlaybackOverlayAction.ToggleDislike -> state.copy(isDisliked = !state.isDisliked)
        PlaybackOverlayAction.StopDevice -> state.copy(deviceStopped = true)
        PlaybackOverlayAction.SeekBack,
        PlaybackOverlayAction.OpenDrawer,
        PlaybackOverlayAction.OpenSource,
        PlaybackOverlayAction.OpenSearch,
        PlaybackOverlayAction.OpenMore -> state
    }

    private fun nextSpeed(speed: Float): Float {
        val values = floatArrayOf(0.25f, 0.5f, 1f, 2f, 3f, 5f)
        val index = values.indexOfFirst { kotlin.math.abs(it - speed) < 0.001f }
        return values[if (index < 0) 2 else (index + 1) % values.size]
    }
}
