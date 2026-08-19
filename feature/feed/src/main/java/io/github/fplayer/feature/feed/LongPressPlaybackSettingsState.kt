package io.github.fplayer.feature.feed

enum class PlaybackTouchRegion {
    CENTER,
    LEFT_EDGE,
    RIGHT_EDGE,
    PROGRESS,
    SYSTEM_BACK_EDGE,
}

enum class PlaybackPanelOrientation { PORTRAIT, LANDSCAPE }

enum class PlaybackPanelPlacement { BOTTOM_SHEET, SIDE_SHEET }

enum class PlaybackRotation { AUTO, PORTRAIT_UP, PORTRAIT_DOWN, LANDSCAPE_LEFT, LANDSCAPE_RIGHT }

data class PlaybackSettings(
    val speed: Float = 1f,
    val temporarySpeed: Float = 2f,
    val rotation: PlaybackRotation = PlaybackRotation.AUTO,
    val muted: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val heatmapEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val edgeTemporarySpeedEnabled: Boolean = true,
) {
    init {
        require(speed in 0.1f..5f)
        require(temporarySpeed in 0.1f..5f)
    }
}

data class LongPressPlaybackSnapshot(
    val panelOpen: Boolean,
    val panelOrientation: PlaybackPanelOrientation,
    val panelPlacement: PlaybackPanelPlacement,
    val settings: PlaybackSettings,
    val temporarySpeedSide: PlaybackTouchRegion?,
    val effectiveSpeed: Float,
    val longPressHapticPending: Boolean,
    val edgeSpeedHapticPending: Boolean,
)

/** Deterministic gesture arbitration for the playback settings panel and edge speed boost. */
class LongPressPlaybackSettingsStateMachine(
    orientation: PlaybackPanelOrientation = PlaybackPanelOrientation.PORTRAIT,
    settings: PlaybackSettings = PlaybackSettings(),
) {
    companion object {
        const val LONG_PRESS_MS = 500L
        const val EDGE_SPEED_MS = 350L
        const val LONG_PRESS_SLOP_DP = 12f
    }

    private var orientationValue = orientation
    private var settingsValue = settings
    private var panelOpenValue = false
    private var pointerRegion: PlaybackTouchRegion? = null
    private var pointerDownAtMs = 0L
    private var movedBeyondSlop = false
    private var directionLocked = false
    private var systemBackStarted = false
    private var longPressTriggered = false
    private var temporarySideValue: PlaybackTouchRegion? = null
    private var longPressHapticValue = false
    private var edgeHapticValue = false

    fun setOrientation(orientation: PlaybackPanelOrientation) {
        orientationValue = orientation
    }

    fun updateSettings(settings: PlaybackSettings) {
        settingsValue = settings
        if (temporarySideValue != null && !settings.edgeTemporarySpeedEnabled) temporarySideValue = null
    }

    fun onPointerDown(region: PlaybackTouchRegion, nowMs: Long) {
        pointerRegion = region
        pointerDownAtMs = nowMs
        movedBeyondSlop = false
        directionLocked = false
        systemBackStarted = region == PlaybackTouchRegion.SYSTEM_BACK_EDGE
        longPressTriggered = false
        longPressHapticValue = false
        edgeHapticValue = false
        temporarySideValue = null
    }

    fun onPointerMove(deltaX: Float, deltaY: Float) {
        if (pointerRegion == null) return
        if (kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()) > LONG_PRESS_SLOP_DP) {
            movedBeyondSlop = true
        }
    }

    /** Call with a monotonic event time; no wall clock is read by this state machine. */
    fun onTime(nowMs: Long) {
        val region = pointerRegion ?: return
        if (systemBackStarted || directionLocked || movedBeyondSlop || panelOpenValue) return
        val heldMs = (nowMs - pointerDownAtMs).coerceAtLeast(0L)
        when {
            region == PlaybackTouchRegion.CENTER && heldMs >= LONG_PRESS_MS && !longPressTriggered -> {
                longPressTriggered = true
                panelOpenValue = true
                longPressHapticValue = true
            }
            (region == PlaybackTouchRegion.LEFT_EDGE || region == PlaybackTouchRegion.RIGHT_EDGE) &&
                heldMs >= EDGE_SPEED_MS && settingsValue.edgeTemporarySpeedEnabled && !longPressTriggered -> {
                longPressTriggered = true
                temporarySideValue = region
                edgeHapticValue = true
            }
        }
    }

    fun onDirectionLock() {
        directionLocked = true
        cancelPendingGesture()
    }

    fun onProgressDragStart() {
        directionLocked = true
        cancelPendingGesture()
    }

    fun onSystemBackGestureStart() {
        systemBackStarted = true
        cancelPendingGesture()
    }

    fun onPointerUp() {
        temporarySideValue = null
        pointerRegion = null
        longPressTriggered = false
        movedBeyondSlop = false
        directionLocked = false
        systemBackStarted = false
    }

    fun dismissPanel() {
        panelOpenValue = false
    }

    fun consumeLongPressHaptic(): Boolean = longPressHapticValue.also { longPressHapticValue = false }

    fun consumeEdgeSpeedHaptic(): Boolean = edgeHapticValue.also { edgeHapticValue = false }

    fun snapshot(): LongPressPlaybackSnapshot {
        val speed = temporarySideValue?.let { settingsValue.temporarySpeed } ?: settingsValue.speed
        return LongPressPlaybackSnapshot(
            panelOpen = panelOpenValue,
            panelOrientation = orientationValue,
            panelPlacement = if (orientationValue == PlaybackPanelOrientation.PORTRAIT) {
                PlaybackPanelPlacement.BOTTOM_SHEET
            } else {
                PlaybackPanelPlacement.SIDE_SHEET
            },
            settings = settingsValue,
            temporarySpeedSide = temporarySideValue,
            effectiveSpeed = speed,
            longPressHapticPending = longPressHapticValue,
            edgeSpeedHapticPending = edgeHapticValue,
        )
    }

    private fun cancelPendingGesture() {
        longPressTriggered = false
        temporarySideValue = null
        longPressHapticValue = false
        edgeHapticValue = false
    }
}
