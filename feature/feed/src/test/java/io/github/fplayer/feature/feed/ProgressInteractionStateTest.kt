package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressInteractionStateTest {
    @Test fun dragPreviewsWithoutChangingMediaUntilMatchingConfirmation() {
        val state = ProgressInteractionStateMachine(1000L, 100L)
        state.setPlaying(true)
        state.onPointerDown(); state.onPointerMove(0.6f)
        assertEquals(100L, state.snapshot().mediaPositionMs)
        assertEquals(600L, state.snapshot().previewPositionMs)
        val token = state.onPointerUp()!!
        assertFalse(state.onSeekConfirmed(token - 1, 500L))
        assertTrue(state.onSeekConfirmed(token, 600L))
        assertEquals(600L, state.snapshot().mediaPositionMs)
    }

    @Test fun endpointHapticsFireOncePerArrival() {
        val state = ProgressInteractionStateMachine(1000L)
        state.onPointerDown(); state.onPointerMove(0f)
        assertEquals(ProgressHapticEdge.START, state.consumeHapticEdge())
        state.onPointerMove(0f)
        assertEquals(ProgressHapticEdge.NONE, state.consumeHapticEdge())
        state.onPointerMove(1f)
        assertEquals(ProgressHapticEdge.END, state.consumeHapticEdge())
    }

    @Test fun settlingIgnoresPositionCallbacksAndBlocksFeedPaging() {
        val state = ProgressInteractionStateMachine(1000L)
        state.onPointerDown(); state.onPointerMove(0.5f)
        val token = state.onPointerUp()!!
        state.updateMediaPosition(50L)
        assertEquals(500L, state.snapshot().previewPositionMs)
        assertTrue(state.snapshot().feedPagingBlocked)
        assertTrue(state.onSeekConfirmed(token, 500L))
        assertFalse(state.snapshot().feedPagingBlocked)
    }

    @Test fun barAndHeatmapShareTheSameDragState() {
        val state = ProgressInteractionStateMachine(2000L, mode = ProgressInteractionMode.HEATMAP)
        state.onPointerDown(); state.onPointerMove(0.25f)
        assertEquals(ProgressInteractionMode.HEATMAP, state.snapshot().mode)
        assertEquals(500L, state.snapshot().previewPositionMs)
        state.setMode(ProgressInteractionMode.BAR)
        assertEquals(ProgressInteractionMode.BAR, state.snapshot().mode)
        assertEquals(500L, state.snapshot().previewPositionMs)
    }
}
