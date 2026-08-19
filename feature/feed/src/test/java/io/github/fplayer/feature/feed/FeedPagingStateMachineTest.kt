package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPagingStateMachineTest {
    @Test fun locksVerticalDirectionAndRequiresSettleBeforeActiveChanges() {
        val pager = FeedPagingStateMachine(3, 1000f)
        pager.onDown()
        pager.onMove(10f, -300f)
        assertEquals(FeedGestureDirection.VERTICAL, pager.snapshot().direction)
        pager.onUp(0f, -100f)
        assertEquals(FeedGesturePhase.SETTLING, pager.snapshot().phase)
        assertEquals(0, pager.snapshot().pageIndex)
        assertEquals(1, pager.snapshot().settleTarget)
        pager.onSettleProgress(0.94f)
        assertEquals(0, pager.snapshot().pageIndex)
        pager.onSettleProgress(0.95f)
        assertEquals(1, pager.snapshot().pageIndex)
        assertEquals(FeedPagingOutcome.PAGE_CHANGED, pager.consumeOutcome())
    }

    @Test fun belowThresholdBouncesWithoutHalfPage() {
        val pager = FeedPagingStateMachine(2, 1000f)
        pager.onDown(); pager.onMove(0f, -100f); pager.onUp()
        assertEquals(FeedGesturePhase.SETTLING, pager.snapshot().phase)
        assertEquals(0, pager.snapshot().settleTarget)
        pager.onSettleProgress(1f)
        assertEquals(0, pager.snapshot().pageIndex)
        assertEquals(FeedPagingOutcome.NONE, pager.consumeOutcome())
    }

    @Test fun velocityNeedsMinimumDistanceAndCanAdvance() {
        val pager = FeedPagingStateMachine(2, 1000f)
        pager.onDown(); pager.onMove(0f, -30f); pager.onUp(0f, -1200f)
        assertEquals(1, pager.snapshot().settleTarget)
        pager.onSettleProgress(1f)
        assertEquals(1, pager.snapshot().pageIndex)
    }

    @Test fun velocityWithoutMinimumDistanceDoesNotAdvance() {
        val pager = FeedPagingStateMachine(2, 1000f)
        pager.onDown(); pager.onMove(0f, -10f); pager.onUp(0f, -2000f)
        assertEquals(0, pager.snapshot().settleTarget)
    }

    @Test fun horizontalLeftOpensGridAndHorizontalLockCannotBecomeVertical() {
        val pager = FeedPagingStateMachine(2, 1000f)
        pager.onDown(); pager.onMove(-250f, -100f)
        assertEquals(FeedGestureDirection.HORIZONTAL, pager.snapshot().direction)
        pager.onMove(-250f, -900f)
        pager.onUp(-100f, -2000f)
        assertEquals(FeedPagingOutcome.OPEN_GRID, pager.consumeOutcome())
    }

    @Test fun boundaryDampsAndReportsBoundaryInsteadOfCycling() {
        val pager = FeedPagingStateMachine(2, 1000f)
        pager.onDown(); pager.onMove(0f, 900f); pager.onUp(0f, 1500f)
        assertEquals(FeedPagingOutcome.BOUNDARY, pager.consumeOutcome())
        assertEquals(0, pager.snapshot().settleTarget)
        pager.onSettleProgress(1f)
        assertEquals(0, pager.snapshot().pageIndex)
    }

    @Test fun repeatedFastSwipesOnlyCommitAfterEachSettle() {
        val pager = FeedPagingStateMachine(4, 1000f)
        pager.onDown(); pager.onMove(0f, -900f); pager.onUp()
        assertEquals(1, pager.snapshot().settleTarget)
        pager.onSettleProgress(1f)
        pager.onDown(); pager.onMove(0f, -900f); pager.onUp()
        assertEquals(2, pager.snapshot().settleTarget)
        assertEquals(1, pager.snapshot().pageIndex)
    }
}
