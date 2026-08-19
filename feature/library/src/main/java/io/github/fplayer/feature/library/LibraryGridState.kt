package io.github.fplayer.feature.library

import io.github.fplayer.core.model.MediaId

enum class LibraryLayout { GRID, DOUBLE_COLUMN }

enum class LibraryContentFilter { ALL_VIDEOS, SCRIPTED_VIDEOS }

enum class HomeSurface { DEFAULT_FEED, FOLDER_GRID, FOLDER_PLAYBACK }

data class LibraryFolderHeader(
    val id: String,
    val displayName: String,
    val path: String,
    val directMediaCount: Int,
    val descendantMediaCount: Int,
) {
    init {
        require(directMediaCount >= 0)
        require(descendantMediaCount >= directMediaCount)
    }
}

data class LibraryGridItem(
    val mediaId: MediaId,
    val title: String,
    val folderName: String,
    val durationMs: Long?,
    val progressPermille: Int,
    val hasScript: Boolean,
) {
    init {
        require(durationMs == null || durationMs >= 0L)
        require(progressPermille in 0..1000)
    }
}

data class LibraryJumpTarget(
    val mediaId: MediaId,
    val visibleIndex: Int,
    val rowIndex: Int,
)

data class LibraryGridSnapshot(
    val folder: LibraryFolderHeader,
    val layout: LibraryLayout,
    val contentFilter: LibraryContentFilter,
    val visibleItems: List<LibraryGridItem>,
    val currentMediaId: MediaId?,
    val justWatchedMediaId: MediaId?,
    val homeSurface: HomeSurface,
)

/**
 * Stable folder-grid state shared by touch, keyboard, accessibility, and Feed navigation.
 * The index is rebuilt only when the source/filter changes; jumps are exact ID lookups.
 */
class LibraryGridStateMachine(
    folder: LibraryFolderHeader,
    items: List<LibraryGridItem>,
    layout: LibraryLayout = LibraryLayout.GRID,
    contentFilter: LibraryContentFilter = LibraryContentFilter.ALL_VIDEOS,
) {
    private var folderValue = folder
    private var sourceItems = validateUnique(items)
    private var layoutValue = layout
    private var filterValue = contentFilter
    private var currentMediaIdValue: MediaId? = null
    private var justWatchedMediaIdValue: MediaId? = null
    private var homeSurfaceValue = HomeSurface.DEFAULT_FEED
    private var visibleItemsValue: List<LibraryGridItem> = emptyList()
    private var visibleIndexById: Map<MediaId, Int> = emptyMap()

    init { rebuildVisibleItems() }

    fun setLayout(layout: LibraryLayout) {
        layoutValue = layout
    }

    fun setContentFilter(filter: LibraryContentFilter) {
        if (filterValue == filter) return
        filterValue = filter
        rebuildVisibleItems()
    }

    fun replace(folder: LibraryFolderHeader, items: List<LibraryGridItem>) {
        folderValue = folder
        sourceItems = validateUnique(items)
        rebuildVisibleItems()
    }

    fun openGridFromFeed(currentMediaId: MediaId?): LibraryJumpTarget? {
        currentMediaIdValue = currentMediaId
        homeSurfaceValue = HomeSurface.FOLDER_GRID
        return jumpTarget(currentMediaId)
    }

    fun openPlayback(mediaId: MediaId): Boolean {
        if (visibleIndexById[mediaId] == null) return false
        currentMediaIdValue = mediaId
        justWatchedMediaIdValue = mediaId
        homeSurfaceValue = HomeSurface.FOLDER_PLAYBACK
        return true
    }

    fun markPlaybackProgress(mediaId: MediaId, progressPermille: Int) {
        require(progressPermille in 0..1000)
        sourceItems = sourceItems.map { item ->
            if (item.mediaId == mediaId) item.copy(progressPermille = progressPermille) else item
        }
        if (sourceItems.any { it.mediaId == mediaId }) justWatchedMediaIdValue = mediaId
        rebuildVisibleItems()
    }

    /** First Home returns folder playback to its grid; the next Home returns the default Feed. */
    fun onHomePressed(): HomeSurface {
        homeSurfaceValue = when (homeSurfaceValue) {
            HomeSurface.FOLDER_PLAYBACK -> HomeSurface.FOLDER_GRID
            HomeSurface.FOLDER_GRID -> HomeSurface.DEFAULT_FEED
            HomeSurface.DEFAULT_FEED -> HomeSurface.DEFAULT_FEED
        }
        return homeSurfaceValue
    }

    fun jumpToCurrent(columns: Int = columnsForLayout(layoutValue)): LibraryJumpTarget? =
        jumpTarget(currentMediaIdValue, columns)

    fun jumpToJustWatched(columns: Int = columnsForLayout(layoutValue)): LibraryJumpTarget? =
        jumpTarget(justWatchedMediaIdValue, columns)

    fun snapshot(): LibraryGridSnapshot = LibraryGridSnapshot(
        folderValue,
        layoutValue,
        filterValue,
        visibleItemsValue,
        currentMediaIdValue,
        justWatchedMediaIdValue,
        homeSurfaceValue,
    )

    private fun jumpTarget(
        mediaId: MediaId?,
        columns: Int = columnsForLayout(layoutValue),
    ): LibraryJumpTarget? {
        require(columns > 0)
        if (mediaId == null) return null
        val index = visibleIndexById[mediaId] ?: return null
        return LibraryJumpTarget(mediaId, index, index / columns)
    }

    private fun rebuildVisibleItems() {
        visibleItemsValue = when (filterValue) {
            LibraryContentFilter.ALL_VIDEOS -> sourceItems
            LibraryContentFilter.SCRIPTED_VIDEOS -> sourceItems.filter(LibraryGridItem::hasScript)
        }
        visibleIndexById = visibleItemsValue.mapIndexed { index, item -> item.mediaId to index }.toMap()
    }

    private fun validateUnique(items: List<LibraryGridItem>): List<LibraryGridItem> {
        require(items.map(LibraryGridItem::mediaId).toSet().size == items.size) {
            "DUPLICATE_MEDIA_ID"
        }
        return items.toList()
    }

    companion object {
        fun columnsForLayout(layout: LibraryLayout): Int = when (layout) {
            LibraryLayout.GRID -> 3
            LibraryLayout.DOUBLE_COLUMN -> 2
        }

        fun adaptiveGridColumns(viewportWidthDp: Int, minimumTileWidthDp: Int = 152): Int {
            require(viewportWidthDp > 0)
            require(minimumTileWidthDp > 0)
            return maxOf(3, viewportWidthDp / minimumTileWidthDp)
        }
    }
}
