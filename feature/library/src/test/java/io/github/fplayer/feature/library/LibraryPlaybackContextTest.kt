package io.github.fplayer.feature.library

import io.github.fplayer.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaybackContextTest {
    @Test
    fun folderPlaybackReturnsToFolderThenDefaultFeed() {
        val state = LibraryGridStateMachine(
            LibraryFolderHeader("folder", "Synthetic", "logical", 2, 2),
            listOf(
                LibraryGridItem(MediaId("one"), "One", "Synthetic", 1_000, 0, true),
                LibraryGridItem(MediaId("two"), "Two", "Synthetic", 1_000, 0, false),
            ),
        )
        assertTrue(state.openGridFromFeed(MediaId("one")) != null)
        assertTrue(state.openPlayback(MediaId("two")))
        assertEquals(HomeSurface.FOLDER_GRID, state.onHomePressed())
        assertEquals(MediaId("two"), state.snapshot().currentMediaId)
        assertEquals(HomeSurface.DEFAULT_FEED, state.onHomePressed())
    }

    @Test
    fun scriptedFilterNeverExposesUnscriptedMedia() {
        val state = LibraryGridStateMachine(
            LibraryFolderHeader("folder", "Synthetic", "logical", 2, 2),
            listOf(
                LibraryGridItem(MediaId("script"), "Script", "Synthetic", null, 0, true),
                LibraryGridItem(MediaId("plain"), "Plain", "Synthetic", null, 0, false),
            ),
        )
        state.setContentFilter(LibraryContentFilter.SCRIPTED_VIDEOS)
        assertEquals(listOf(MediaId("script")), state.snapshot().visibleItems.map { it.mediaId })
    }
}
