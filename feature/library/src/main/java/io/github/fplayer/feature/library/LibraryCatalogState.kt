package io.github.fplayer.feature.library

import io.github.fplayer.core.index.SortDirection
import io.github.fplayer.core.index.SortField
import io.github.fplayer.core.index.SortSpec
import io.github.fplayer.core.model.MediaId
import java.text.Normalizer
import java.util.Locale

enum class LibraryAlbumView { ALL_FOLDERS, VIDEO_FOLDERS, ALL_VIDEOS }

enum class LibraryCollection { ALL, HISTORY, LIKED, FAVORITES, DISLIKED }

data class LibraryCatalogFolder(
    val id: String,
    val sourceId: String,
    val displayName: String,
    val path: String,
    val directMediaCount: Int,
    val descendantMediaCount: Int,
)

data class LibraryCatalogMedia(
    val mediaId: MediaId,
    val sourceId: String,
    val folderId: String,
    val title: String,
    val normalizedTitle: String,
    val path: String,
    val folderName: String,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val modifiedAtEpochMs: Long?,
    val width: Int?,
    val height: Int?,
    val probeStatus: String,
    val progressPermille: Int,
    val lastPlayedAtEpochMs: Long?,
    val hasScript: Boolean,
    val liked: Boolean,
    val favorite: Boolean,
    val disliked: Boolean,
)

data class LibraryCatalogInput(
    val folders: List<LibraryCatalogFolder>,
    val media: List<LibraryCatalogMedia>,
)

data class LibraryCatalogSnapshot(
    val albumView: LibraryAlbumView,
    val collection: LibraryCollection,
    val layout: LibraryLayout,
    val sortSpec: SortSpec,
    val searchQuery: String,
    val folders: List<LibraryCatalogFolder>,
    val media: List<LibraryCatalogMedia>,
    val selectedFolder: LibraryCatalogFolder?,
    val currentMediaId: MediaId?,
    val pendingDeleteMediaId: MediaId?,
)

/** Pure catalog state used by the album destination, Feed drawer, and search surface. */
class LibraryCatalogStateMachine(
    input: LibraryCatalogInput,
    sortSpec: SortSpec = SortSpec(SortField.TITLE, SortDirection.ASCENDING),
) {
    private var inputValue = validate(input)
    private var albumViewValue = LibraryAlbumView.ALL_FOLDERS
    private var collectionValue = LibraryCollection.ALL
    private var layoutValue = LibraryLayout.GRID
    private var sortSpecValue = sortSpec
    private var searchQueryValue = ""
    private var selectedFolderIdValue: String? = null
    private var currentMediaIdValue: MediaId? = null
    private var pendingDeleteMediaIdValue: MediaId? = null

    fun replace(input: LibraryCatalogInput) {
        inputValue = validate(input)
        if (currentMediaIdValue !in inputValue.media.map(LibraryCatalogMedia::mediaId)) {
            currentMediaIdValue = null
        }
        if (pendingDeleteMediaIdValue !in inputValue.media.map(LibraryCatalogMedia::mediaId)) {
            pendingDeleteMediaIdValue = null
        }
        if (selectedFolderIdValue !in inputValue.folders.map(LibraryCatalogFolder::id)) {
            selectedFolderIdValue = null
        }
    }

    fun setAlbumView(view: LibraryAlbumView) {
        albumViewValue = view
        if (view != LibraryAlbumView.ALL_VIDEOS) selectedFolderIdValue = null
    }

    fun setCollection(collection: LibraryCollection) {
        collectionValue = collection
        if (collection != LibraryCollection.ALL) selectedFolderIdValue = null
    }

    fun setLayout(layout: LibraryLayout) { layoutValue = layout }

    fun setSort(sortSpec: SortSpec) { sortSpecValue = sortSpec }

    fun setSearchQuery(query: String) { searchQueryValue = query.trim().take(MAX_QUERY_LENGTH) }

    fun openFolder(folderId: String): Boolean {
        if (inputValue.folders.none { it.id == folderId }) return false
        selectedFolderIdValue = folderId
        albumViewValue = LibraryAlbumView.ALL_VIDEOS
        collectionValue = LibraryCollection.ALL
        return true
    }

    fun closeFolder() {
        selectedFolderIdValue = null
        albumViewValue = LibraryAlbumView.ALL_FOLDERS
    }

    fun selectMedia(mediaId: MediaId): Boolean {
        if (visibleMedia().none { it.mediaId == mediaId }) return false
        currentMediaIdValue = mediaId
        return true
    }

    fun requestDelete(mediaId: MediaId): Boolean {
        if (visibleMedia().none { it.mediaId == mediaId }) return false
        pendingDeleteMediaIdValue = mediaId
        return true
    }

    fun cancelDelete() { pendingDeleteMediaIdValue = null }

    /** Returns the confirmed target; persistence/deletion remains an explicit caller action. */
    fun confirmDelete(): MediaId? = pendingDeleteMediaIdValue.also { pendingDeleteMediaIdValue = null }

    fun snapshot(): LibraryCatalogSnapshot = LibraryCatalogSnapshot(
        albumView = albumViewValue,
        collection = collectionValue,
        layout = layoutValue,
        sortSpec = sortSpecValue,
        searchQuery = searchQueryValue,
        folders = visibleFolders(),
        media = visibleMedia(),
        selectedFolder = inputValue.folders.firstOrNull { it.id == selectedFolderIdValue },
        currentMediaId = currentMediaIdValue,
        pendingDeleteMediaId = pendingDeleteMediaIdValue,
    )

    private fun visibleFolders(): List<LibraryCatalogFolder> {
        if (albumViewValue == LibraryAlbumView.ALL_VIDEOS || collectionValue != LibraryCollection.ALL) {
            return emptyList()
        }
        val query = normalizedSearchQuery()
        return inputValue.folders.asSequence()
            .filter { albumViewValue == LibraryAlbumView.ALL_FOLDERS || it.descendantMediaCount > 0 }
            .filter { query.isEmpty() || matchesQuery(query, searchable(it.displayName, it.path)) }
            .sortedWith(compareBy<LibraryCatalogFolder>({ normalize(it.displayName) }, { normalize(it.path) }, { it.id }))
            .toList()
    }

    private fun visibleMedia(): List<LibraryCatalogMedia> {
        val query = normalizedSearchQuery()
        return inputValue.media.asSequence()
            .filter { selectedFolderIdValue == null || it.folderId == selectedFolderIdValue }
            .filter { matchesCollection(it) }
            .filter { query.isEmpty() || matchesQuery(query, searchable(it.title, it.folderName, it.path)) }
            .sortedWith(mediaComparator(sortSpecValue))
            .toList()
    }

    private fun matchesCollection(media: LibraryCatalogMedia): Boolean = when (collectionValue) {
        LibraryCollection.ALL -> true
        LibraryCollection.HISTORY -> media.lastPlayedAtEpochMs != null
        LibraryCollection.LIKED -> media.liked
        LibraryCollection.FAVORITES -> media.favorite
        LibraryCollection.DISLIKED -> media.disliked
    }

    private fun normalizedSearchQuery(): String = normalize(searchQueryValue)

    private fun validate(input: LibraryCatalogInput): LibraryCatalogInput {
        require(input.media.map(LibraryCatalogMedia::mediaId).toSet().size == input.media.size) {
            "DUPLICATE_MEDIA_ID"
        }
        require(input.folders.map(LibraryCatalogFolder::id).toSet().size == input.folders.size) {
            "DUPLICATE_FOLDER_ID"
        }
        return input.copy(folders = input.folders.toList(), media = input.media.toList())
    }

    companion object {
        const val MAX_QUERY_LENGTH = 120

        private fun searchable(vararg values: String): String = values.joinToString("\u0000") { normalize(it) }

        private fun matchesQuery(query: String, haystack: String): Boolean =
            query.split(Regex("\\s+")).filter(String::isNotEmpty).all(haystack::contains)

        private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)

        private fun mediaComparator(spec: SortSpec): Comparator<LibraryCatalogMedia> {
            val primary = Comparator<LibraryCatalogMedia> { left, right ->
                val result = when (spec.field) {
                    SortField.TITLE -> left.normalizedTitle.compareTo(right.normalizedTitle)
                    SortField.MODIFIED_AT -> compareNullable(left.modifiedAtEpochMs, right.modifiedAtEpochMs)
                    SortField.LAST_PLAYED_AT -> compareNullable(left.lastPlayedAtEpochMs, right.lastPlayedAtEpochMs)
                    SortField.STATUS -> statusRank(left.probeStatus).compareTo(statusRank(right.probeStatus))
                    SortField.DURATION -> compareNullable(left.durationMs, right.durationMs)
                    SortField.SIZE -> compareNullable(left.sizeBytes, right.sizeBytes)
                    SortField.RESOLUTION -> compareResolution(left, right)
                    SortField.PATH -> normalize(left.path).compareTo(normalize(right.path))
                }
                if (spec.direction == SortDirection.DESCENDING &&
                    kotlin.math.abs(result) != MISSING_VALUE_ORDER
                ) -result else result
            }
            return primary
                .thenBy { normalize(it.title) }
                .thenBy { normalize(it.path) }
                .thenBy { it.mediaId.value }
        }

        private fun compareResolution(left: LibraryCatalogMedia, right: LibraryCatalogMedia): Int {
            val leftMissing = left.width == null || left.height == null
            val rightMissing = right.width == null || right.height == null
            if (leftMissing || rightMissing) {
                return when {
                    leftMissing && rightMissing -> 0
                    leftMissing -> MISSING_VALUE_ORDER
                    else -> -MISSING_VALUE_ORDER
                }
            }
            val leftWidth = checkNotNull(left.width)
            val leftHeight = checkNotNull(left.height)
            val rightWidth = checkNotNull(right.width)
            val rightHeight = checkNotNull(right.height)
            val leftArea = leftWidth.toLong() * leftHeight
            val rightArea = rightWidth.toLong() * rightHeight
            return compareNullable(leftArea, rightArea).takeIf { it != 0 }
                ?: compareNullable(leftWidth, rightWidth).takeIf { it != 0 }
                ?: compareNullable(leftHeight, rightHeight)
        }

        private fun <T : Comparable<T>> compareNullable(left: T?, right: T?): Int = when {
            left == null && right == null -> 0
            left == null -> MISSING_VALUE_ORDER
            right == null -> -MISSING_VALUE_ORDER
            else -> left.compareTo(right)
        }

        private fun statusRank(status: String): Int = when (status) {
            "READY" -> 0
            "UNPROBED" -> 1
            "FAILED" -> 2
            else -> 3
        }

        private const val MISSING_VALUE_ORDER = 1_000_000
    }
}
