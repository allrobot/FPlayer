package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOverlayStateTest {
    @Test fun cleanModeTogglesWithoutChangingPlayback() {
        val state = PlaybackOverlayState(isPlaying = true)
        val clean = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.ToggleCleanScreen)
        assertEquals(PlaybackOverlayMode.CLEAN, clean.mode)
        assertTrue(clean.isPlaying)
        assertEquals(PlaybackOverlayMode.NORMAL, PlaybackOverlayReducer.reduce(clean, PlaybackOverlayAction.ToggleCleanScreen).mode)
    }

    @Test fun speedCyclesThroughStableValues() {
        var state = PlaybackOverlayState(speed = 1f)
        state = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.CycleSpeed)
        assertEquals(2f, state.speed)
        repeat(5) { state = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.CycleSpeed) }
        assertEquals(1f, state.speed)
    }

    @Test fun deviceStopIsVisibleAndDoesNotPauseMedia() {
        val state = PlaybackOverlayState(isPlaying = true, deviceConnected = true)
        val stopped = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.StopDevice)
        assertTrue(stopped.deviceStopped)
        assertTrue(stopped.isPlaying)
        assertFalse(stopped.isFavorite)
    }

    @Test fun reactionsAreIndependentToggles() {
        var state = PlaybackOverlayState()
        state = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.ToggleFavorite)
        state = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.ToggleBookmark)
        state = PlaybackOverlayReducer.reduce(state, PlaybackOverlayAction.ToggleDislike)
        assertTrue(state.isFavorite && state.isBookmarked && state.isDisliked)
    }
}
