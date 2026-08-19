package io.github.fplayer.feature.feed

/** Stable control geometry contract consumed by the app renderer. */
data class PlaybackOverlayLayout(
    val progressHeightDp: Int = 4,
    val controlTouchTargetDp: Int = 48,
    val normalTopControls: Int = 7,
    val cleanControls: Int = 4,
    val rightRailActions: Int = 4,
    val metadataLines: Int = 3,
) {
    init {
        require(progressHeightDp > 0)
        require(controlTouchTargetDp >= 48)
        require(normalTopControls >= 0)
        require(cleanControls >= 0)
        require(rightRailActions >= 0)
        require(metadataLines in 0..3)
    }

    fun visibleControls(mode: PlaybackOverlayMode): Int =
        if (mode == PlaybackOverlayMode.CLEAN) cleanControls else normalTopControls + rightRailActions
}
