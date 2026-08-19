package io.github.fplayer.feature.feed

import io.github.fplayer.core.index.MaterializedOrder
import io.github.fplayer.core.index.SortDirection
import io.github.fplayer.core.index.SortField
import io.github.fplayer.core.index.SortSpec
import io.github.fplayer.core.model.MediaId

/** Metadata rendered by the feed; playback ownership remains in [PlaybackSession]. */
data class PlaybackFeedItem(
    val media: FeedMedia,
    val title: String = "",
    val folder: String = "",
    val summary: String = "",
)

data class PlaybackFeedState(
    val items: List<PlaybackFeedItem> = emptyList(),
    val activeIndex: Int? = null,
    val settledIndex: Int? = null,
    val session: PlaybackSessionSnapshot? = null,
    val paging: FeedPagingSnapshot = FeedPagingSnapshot(
        pageIndex = 0,
        pageCount = 0,
        phase = FeedGesturePhase.IDLE,
        direction = FeedGestureDirection.NONE,
        offsetPx = 0f,
        settleTarget = null,
        outcome = FeedPagingOutcome.NONE,
    ),
)

sealed interface FeedPagingEvent {
    data object Down : FeedPagingEvent
    data class Move(val dxPx: Float, val dyPx: Float) : FeedPagingEvent
    data class Up(val velocityX: Float = 0f, val velocityY: Float = 0f) : FeedPagingEvent
    data class Settle(val progress: Float) : FeedPagingEvent
    data class ReplaceItems(
        val items: List<PlaybackFeedItem>,
        val selectedMediaId: MediaId? = null,
    ) : FeedPagingEvent
}

/**
 * Pure feed coordinator. It promotes an item only after the paging machine reaches its settle
 * completion threshold. A supplied session is selected on that same serialized transition.
 */
class PlaybackFeedReducer(
    pageExtentPx: Float,
    private val sessionController: PlaybackSession? = null,
    private val config: FeedPagingConfig = FeedPagingConfig(),
) {
    private val pager = FeedPagingStateMachine(0, pageExtentPx, config)

    fun reduce(state: PlaybackFeedState, event: FeedPagingEvent): PlaybackFeedState {
        var next = state
        when (event) {
            FeedPagingEvent.Down -> pager.onDown()
            is FeedPagingEvent.Move -> pager.onMove(event.dxPx, event.dyPx)
            is FeedPagingEvent.Up -> pager.onUp(event.velocityX, event.velocityY)
            is FeedPagingEvent.Settle -> pager.onSettleProgress(event.progress)
            is FeedPagingEvent.ReplaceItems -> {
                val active = event.items.indexOfFirst { it.media.id == event.selectedMediaId }
                    .takeIf { it >= 0 }
                    ?: event.items.indices.firstOrNull()
                pager.setPageCount(event.items.size, active ?: 0)
                if (sessionController != null) {
                    val ids = event.items.map { it.media.id }
                    sessionController.setItems(event.items.map { it.media })
                    if (ids.isNotEmpty()) {
                        sessionController.setOrder(
                            MaterializedOrder(
                                scopeId = "feed",
                                sortSpec = SortSpec(SortField.TITLE, SortDirection.ASCENDING),
                                generation = 1L,
                                mediaIds = ids,
                            ),
                        )
                        if (event.selectedMediaId != null) active?.let(sessionController::select)
                    }
                }
                next = state.copy(
                    items = event.items,
                    activeIndex = active,
                    settledIndex = active,
                )
            }
        }

        val snapshot = pager.snapshot()
        val outcome = pager.consumeOutcome()
        when (outcome) {
            FeedPagingOutcome.PAGE_CHANGED -> {
                val promoted = snapshot.pageIndex.takeIf { it in next.items.indices }
                if (promoted != null) sessionController?.select(promoted)
                next = next.copy(activeIndex = promoted, settledIndex = promoted)
            }
            FeedPagingOutcome.NONE,
            FeedPagingOutcome.OPEN_GRID,
            FeedPagingOutcome.BOUNDARY,
            -> Unit
        }
        return next.copy(
            session = sessionController?.snapshot() ?: next.session,
            paging = pager.snapshot().copy(outcome = outcome),
        )
    }
}
