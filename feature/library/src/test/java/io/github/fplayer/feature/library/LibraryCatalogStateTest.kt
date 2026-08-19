package io.github.fplayer.feature.library

import io.github.fplayer.core.index.SortDirection
import io.github.fplayer.core.index.SortField
import io.github.fplayer.core.index.SortSpec
import io.github.fplayer.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogStateTest {
    @Test
    fun `three album views keep folder counts and exclude empty folders`() {
        val state = LibraryCatalogStateMachine(input())
        assertEquals(listOf("folder-empty", "folder-a"), state.snapshot().folders.map { it.id })

        state.setAlbumView(LibraryAlbumView.VIDEO_FOLDERS)
        assertEquals(listOf("folder-a"), state.snapshot().folders.map { it.id })

        state.setAlbumView(LibraryAlbumView.ALL_VIDEOS)
        assertEquals(3, state.snapshot().media.size)
    }

    @Test
    fun `drawer collections use the same indexed playback flags`() {
        val state = LibraryCatalogStateMachine(input())
        state.setCollection(LibraryCollection.HISTORY)
        assertEquals(listOf("a", "b"), ids(state.snapshot().media))
        state.setCollection(LibraryCollection.LIKED)
        assertEquals(listOf("a"), ids(state.snapshot().media))
        state.setCollection(LibraryCollection.FAVORITES)
        assertEquals(listOf("b"), ids(state.snapshot().media))
        state.setCollection(LibraryCollection.DISLIKED)
        assertEquals(listOf("c"), ids(state.snapshot().media))
    }

    @Test
    fun `folder drill down filters media and can return to album root`() {
        val state = LibraryCatalogStateMachine(input())
        assertTrue(state.openFolder("folder-a"))
        assertEquals("folder-a", state.snapshot().selectedFolder?.id)
        assertEquals(listOf("a", "b", "c"), ids(state.snapshot().media))
        state.closeFolder()
        assertNull(state.snapshot().selectedFolder)
        assertEquals(LibraryAlbumView.ALL_FOLDERS, state.snapshot().albumView)
        assertFalse(state.openFolder("missing"))
    }

    @Test
    fun `sorting and search are deterministic with an explicit empty state`() {
        val state = LibraryCatalogStateMachine(input())
        state.setAlbumView(LibraryAlbumView.ALL_VIDEOS)
        state.setSort(SortSpec(SortField.DURATION, SortDirection.DESCENDING))
        assertEquals(listOf("c", "b", "a"), ids(state.snapshot().media))
        state.setSearchQuery("Alpha")
        assertEquals(listOf("a"), ids(state.snapshot().media))
        state.setSearchQuery("no such media")
        assertTrue(state.snapshot().media.isEmpty())
    }

    @Test
    fun `delete always requires a separate confirmation`() {
        val state = LibraryCatalogStateMachine(input())
        state.setAlbumView(LibraryAlbumView.ALL_VIDEOS)
        assertTrue(state.requestDelete(MediaId("a")))
        assertEquals(MediaId("a"), state.snapshot().pendingDeleteMediaId)
        assertEquals(MediaId("a"), state.confirmDelete())
        assertNull(state.snapshot().pendingDeleteMediaId)
        assertFalse(state.requestDelete(MediaId("missing")))
    }

    @Test
    fun `resolution sort keeps missing metadata last in both directions`() {
        val state = LibraryCatalogStateMachine(input())
            .also { it.setAlbumView(LibraryAlbumView.ALL_VIDEOS) }
        state.setSort(SortSpec(SortField.RESOLUTION, SortDirection.DESCENDING))
        assertEquals(listOf("a", "b", "c"), ids(state.snapshot().media))
        state.replace(input().copy(media = input().media.map {
            if (it.mediaId == MediaId("b")) it.copy(width = null) else it
        }))
        assertEquals(listOf("a", "c", "b"), ids(state.snapshot().media))
    }

    private fun ids(media: List<LibraryCatalogMedia>) = media.map { it.mediaId.value }

    private fun input() = LibraryCatalogInput(
        folders = listOf(
            LibraryCatalogFolder("folder-a", "source", "Folder A", "/a", 2, 2),
            LibraryCatalogFolder("folder-empty", "source", "Empty", "/empty", 0, 0),
        ),
        media = listOf(
            media("a", "Alpha", 1_000L, history = 10L, liked = true),
            media("b", "Beta", 2_000L, history = 20L, favorite = true),
            media("c", "Gamma", 3_000L, disliked = true),
        ),
    )

    private fun media(
        id: String,
        title: String,
        duration: Long,
        history: Long? = null,
        liked: Boolean = false,
        favorite: Boolean = false,
        disliked: Boolean = false,
    ) = LibraryCatalogMedia(
        mediaId = MediaId(id),
        sourceId = "source",
        folderId = "folder-a",
        title = title,
        normalizedTitle = title.lowercase(),
        path = "/a/$title.mp4",
        folderName = "Folder A",
        durationMs = duration,
        sizeBytes = null,
        modifiedAtEpochMs = null,
        width = 1920,
        height = 1080,
        probeStatus = "READY",
        progressPermille = 0,
        lastPlayedAtEpochMs = history,
        hasScript = id != "c",
        liked = liked,
        favorite = favorite,
        disliked = disliked,
    )
}
