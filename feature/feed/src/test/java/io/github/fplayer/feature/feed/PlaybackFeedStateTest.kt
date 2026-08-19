package io.github.fplayer.feature.feed

import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFeedStateTest {
    private val items = listOf(
        PlaybackFeedItem(FeedMedia(MediaId("a"), MediaLocator("file:///a")), "A"),
        PlaybackFeedItem(FeedMedia(MediaId("b"), MediaLocator("file:///b")), "B"),
        PlaybackFeedItem(FeedMedia(MediaId("c"), MediaLocator("file:///c")), "C"),
    )

    @Test
    fun belowThresholdReturnsToCurrentWithoutPromotion() {
        val reducer = PlaybackFeedReducer(1_000f)
        var state = reducer.reduce(PlaybackFeedState(), FeedPagingEvent.ReplaceItems(items))
        state = reducer.reduce(state, FeedPagingEvent.Down)
        state = reducer.reduce(state, FeedPagingEvent.Move(0f, -100f))
        state = reducer.reduce(state, FeedPagingEvent.Up())
        assertEquals(0, state.activeIndex)
        assertEquals(0, state.paging.settleTarget)
        state = reducer.reduce(state, FeedPagingEvent.Settle(1f))
        assertEquals(0, state.activeIndex)
        assertEquals(0, state.settledIndex)
    }

    @Test
    fun promotionHappensOnlyAfterSettleCompletionAndLeavesOneActiveItem() {
        val reducer = PlaybackFeedReducer(1_000f)
        var state = reducer.reduce(PlaybackFeedState(), FeedPagingEvent.ReplaceItems(items))
        state = reducer.reduce(state, FeedPagingEvent.Down)
        state = reducer.reduce(state, FeedPagingEvent.Move(0f, -300f))
        state = reducer.reduce(state, FeedPagingEvent.Up())
        assertEquals(0, state.activeIndex)
        assertEquals(1, state.paging.settleTarget)
        state = reducer.reduce(state, FeedPagingEvent.Settle(0.94f))
        assertEquals(0, state.activeIndex)
        state = reducer.reduce(state, FeedPagingEvent.Settle(0.95f))
        assertEquals(1, state.activeIndex)
        assertEquals(1, state.settledIndex)
        assertEquals(1, state.items.indices.count { it == state.activeIndex })
    }

    @Test
    fun repeatedSwipesNeverExposeTwoActiveItemsAndHorizontalOpenDoesNotChangeMedia() {
        val reducer = PlaybackFeedReducer(1_000f)
        var state = reducer.reduce(PlaybackFeedState(), FeedPagingEvent.ReplaceItems(items))
        state = reducer.reduce(state, FeedPagingEvent.Down)
        state = reducer.reduce(state, FeedPagingEvent.Move(0f, -900f))
        state = reducer.reduce(state, FeedPagingEvent.Up())
        state = reducer.reduce(state, FeedPagingEvent.Settle(1f))
        assertEquals(1, state.activeIndex)
        state = reducer.reduce(state, FeedPagingEvent.Down)
        state = reducer.reduce(state, FeedPagingEvent.Move(-300f, -100f))
        state = reducer.reduce(state, FeedPagingEvent.Up(velocityX = -1_200f))
        assertEquals(1, state.activeIndex)
        assertEquals(FeedPagingOutcome.OPEN_GRID, state.paging.outcome)
        assertTrue(state.items.indices.contains(state.activeIndex))
        assertFalse(state.items.indices.count { it == state.activeIndex } > 1)
    }

    @Test
    fun sessionSelectionFollowsOnlySettledPromotion() {
        val factory = object : SlotPlayerFactory {
            override fun create(slot: FeedSlot): SlotPlayer = object : SlotPlayer {
                override fun prepare(item: FeedMedia, resumePositionMs: Long, generation: Long) = Unit
                override fun play() = Unit
                override fun pause() = Unit
                override fun seekTo(positionMs: Long) = Unit
                override fun release() = Unit
            }
        }
        val session = PlaybackSession(factory)
        val reducer = PlaybackFeedReducer(1_000f, session)
        var state = reducer.reduce(PlaybackFeedState(), FeedPagingEvent.ReplaceItems(items))
        assertNotEquals(null, state.session)
        state = reducer.reduce(state, FeedPagingEvent.Down)
        state = reducer.reduce(state, FeedPagingEvent.Move(0f, -300f))
        state = reducer.reduce(state, FeedPagingEvent.Up())
        assertEquals(MediaId("a"), session.snapshot().current?.id)
        reducer.reduce(state, FeedPagingEvent.Settle(1f))
        assertEquals(MediaId("b"), session.snapshot().current?.id)
    }
}
