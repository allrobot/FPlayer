package io.github.fplayer.core.index

import io.github.fplayer.core.model.IndexedMedia
import io.github.fplayer.core.model.MediaId

enum class SortField {
    TITLE,
    MODIFIED_AT,
    LAST_PLAYED_AT,
    STATUS,
    DURATION,
    SIZE,
    RESOLUTION,
    PATH,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}

data class SortSpec(
    val field: SortField,
    val direction: SortDirection,
)

data class MaterializedOrder(
    val scopeId: String,
    val sortSpec: SortSpec,
    val generation: Long,
    val mediaIds: List<MediaId>,
)

interface MediaIndex {
    fun find(id: MediaId): IndexedMedia?
    fun ordered(scopeId: String, sortSpec: SortSpec): MaterializedOrder
    fun invalidate(scopeId: String)
}
