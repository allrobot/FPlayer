package io.github.fplayer.feature.feed

enum class ProgressInteractionMode { BAR, HEATMAP }

enum class ProgressInteractionPhase { PLAYING, PAUSED, PRESSED, DRAGGING, SETTLING }

enum class ProgressHapticEdge { NONE, START, END }

data class ProgressInteractionSnapshot(
    val mode: ProgressInteractionMode,
    val phase: ProgressInteractionPhase,
    val durationMs: Long,
    val mediaPositionMs: Long,
    val previewPositionMs: Long,
    val submittedPositionMs: Long?,
    val seekToken: Long,
    val hapticEdge: ProgressHapticEdge,
    val feedPagingBlocked: Boolean,
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else previewPositionMs.toFloat() / durationMs.toFloat()
}

/** Shared interaction state for the thin progress bar and script heatmap renderers. */
class ProgressInteractionStateMachine(
    durationMs: Long,
    positionMs: Long = 0L,
    mode: ProgressInteractionMode = ProgressInteractionMode.BAR,
) {
    private var durationValue = durationMs.coerceAtLeast(0L)
    private var mediaPositionValue = positionMs.coerceIn(0L, durationValue)
    private var previewPositionValue = mediaPositionValue
    private var submittedPositionValue: Long? = null
    private var phaseValue = ProgressInteractionPhase.PAUSED
    private var modeValue = mode
    private var seekTokenValue = 0L
    private var hapticValue = ProgressHapticEdge.NONE
    private var endpointValue = ProgressHapticEdge.NONE

    fun setMode(mode: ProgressInteractionMode) { modeValue = mode }

    fun setPlaying(playing: Boolean) {
        if (phaseValue == ProgressInteractionPhase.DRAGGING || phaseValue == ProgressInteractionPhase.SETTLING) return
        phaseValue = if (playing) ProgressInteractionPhase.PLAYING else ProgressInteractionPhase.PAUSED
    }

    fun updateMediaPosition(positionMs: Long) {
        if (phaseValue == ProgressInteractionPhase.SETTLING) return
        mediaPositionValue = positionMs.coerceIn(0L, durationValue)
        previewPositionValue = mediaPositionValue
    }

    fun updateDuration(durationMs: Long) {
        durationValue = durationMs.coerceAtLeast(0L)
        mediaPositionValue = mediaPositionValue.coerceIn(0L, durationValue)
        previewPositionValue = previewPositionValue.coerceIn(0L, durationValue)
    }

    fun onPointerDown() {
        if (phaseValue == ProgressInteractionPhase.SETTLING) return
        phaseValue = ProgressInteractionPhase.PRESSED
        submittedPositionValue = null
        hapticValue = ProgressHapticEdge.NONE
        endpointValue = ProgressHapticEdge.NONE
    }

    fun onPointerMove(progress: Float) {
        if (phaseValue != ProgressInteractionPhase.PRESSED && phaseValue != ProgressInteractionPhase.DRAGGING) return
        phaseValue = ProgressInteractionPhase.DRAGGING
        previewPositionValue = (progress.coerceIn(0f, 1f) * durationValue).toLong()
        val reached = when {
            previewPositionValue == 0L -> ProgressHapticEdge.START
            previewPositionValue == durationValue -> ProgressHapticEdge.END
            else -> ProgressHapticEdge.NONE
        }
        hapticValue = if (reached != ProgressHapticEdge.NONE && reached != endpointValue) reached else ProgressHapticEdge.NONE
        endpointValue = reached
    }

    /** Returns the new seek token, or null when no drag was active. */
    fun onPointerUp(): Long? {
        if (phaseValue != ProgressInteractionPhase.PRESSED && phaseValue != ProgressInteractionPhase.DRAGGING) return null
        seekTokenValue += 1
        submittedPositionValue = previewPositionValue
        phaseValue = ProgressInteractionPhase.SETTLING
        hapticValue = ProgressHapticEdge.NONE
        return seekTokenValue
    }

    /** Ignore callbacks from a seek submitted before the latest token. */
    fun onSeekConfirmed(token: Long, confirmedPositionMs: Long): Boolean {
        if (phaseValue != ProgressInteractionPhase.SETTLING || token != seekTokenValue) return false
        mediaPositionValue = confirmedPositionMs.coerceIn(0L, durationValue)
        previewPositionValue = mediaPositionValue
        submittedPositionValue = null
        phaseValue = ProgressInteractionPhase.PAUSED
        return true
    }

    fun consumeHapticEdge(): ProgressHapticEdge = hapticValue.also { hapticValue = ProgressHapticEdge.NONE }

    fun snapshot(): ProgressInteractionSnapshot = ProgressInteractionSnapshot(
        modeValue,
        phaseValue,
        durationValue,
        mediaPositionValue,
        previewPositionValue,
        submittedPositionValue,
        seekTokenValue,
        hapticValue,
        phaseValue == ProgressInteractionPhase.PRESSED ||
            phaseValue == ProgressInteractionPhase.DRAGGING ||
            phaseValue == ProgressInteractionPhase.SETTLING,
    )
}
