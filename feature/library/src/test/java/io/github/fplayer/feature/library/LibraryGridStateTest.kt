package io.github.fplayer.feature.library

import io.github.fplayer.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGridStateTest {
    @Test
    fun `two thousand items jump exactly to current item in both layouts`() {
        val state = stateWithItems(2_001)
        val current = MediaId("media-1999")

        assertEquals(1999, state.openGridFromFeed(current)?.visibleIndex)
        assertEquals(666, state.jumpToCurrent()?.rowIndex)

        state.setLayout(LibraryLayout.DOUBLE_COLUMN)
        assertEquals(999, state.jumpToCurrent()?.rowIndex)
    }

    @Test
    fun `script filter preserves stable order and reports hidden current item`() {
        val state = stateWithItems(20)
        state.openGridFromFeed(MediaId("media-11"))
        state.setContentFilter(LibraryContentFilter.SCRIPTED_VIDEOS)

        assertEquals((0 until 20 step 2).map { "media-$it" }, state.snapshot().visibleItems.map { it.mediaId.value })
        assertNull(state.jumpToCurrent())

        state.openGridFromFeed(MediaId("media-18"))
        assertEquals(9, state.jumpToCurrent()?.visibleIndex)
    }

    @Test
    fun `playback marks tile and home follows grid return contract`() {
        val state = stateWithItems(8)
        state.openGridFromFeed(MediaId("media-4"))
        assertTrue(state.openPlayback(MediaId("media-6")))
        state.markPlaybackProgress(MediaId("media-6"), 375)

        assertEquals(MediaId("media-6"), state.snapshot().justWatchedMediaId)
        assertEquals(375, state.snapshot().visibleItems[6].progressPermille)
        assertEquals(HomeSurface.FOLDER_GRID, state.onHomePressed())
        assertEquals(6, state.jumpToCurrent()?.visibleIndex)
        assertEquals(HomeSurface.DEFAULT_FEED, state.onHomePressed())
        assertEquals(HomeSurface.DEFAULT_FEED, state.onHomePressed())
    }

    @Test
    fun `replacement reindexes by ID instead of stale ordinal`() {
        val state = stateWithItems(6)
        state.openGridFromFeed(MediaId("media-4"))
        val reordered = state.snapshot().visibleItems.reversed()

        state.replace(folder(), reordered)

        assertEquals(1, state.jumpToCurrent()?.visibleIndex)
    }

    @Test
    fun `missing playback item is rejected without changing context`() {
        val state = stateWithItems(4)
        state.openGridFromFeed(MediaId("media-1"))

        assertFalse(state.openPlayback(MediaId("missing")))
        assertEquals(HomeSurface.FOLDER_GRID, state.snapshot().homeSurface)
        assertNull(state.jumpToJustWatched())
    }

    @Test
    fun `adaptive grid retains three phone columns and expands on tablet`() {
        assertEquals(3, LibraryGridStateMachine.adaptiveGridColumns(360))
        assertEquals(5, LibraryGridStateMachine.adaptiveGridColumns(800))
    }

    private fun stateWithItems(count: Int) = LibraryGridStateMachine(
        folder(),
        List(count) { index ->
            LibraryGridItem(
                mediaId = MediaId("media-$index"),
                title = "Video $index",
                folderName = "Folder",
                durationMs = 60_000L + index,
                progressPermille = 0,
                hasScript = index % 2 == 0,
            )
        },
    )

    private fun folder() = LibraryFolderHeader(
        id = "folder",
        displayName = "Folder",
        path = "/Folder",
        directMediaCount = 2_001,
        descendantMediaCount = 2_001,
    )
}
