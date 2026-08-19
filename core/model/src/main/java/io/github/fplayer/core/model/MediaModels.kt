package io.github.fplayer.core.model

@JvmInline
value class MediaId(val value: String)

@JvmInline
value class MediaLocator(val value: String)

enum class MediaSourceType {
    LOCAL,
    SMB,
}

data class MediaDimensions(
    val width: Int,
    val height: Int,
)

data class IndexedMedia(
    val id: MediaId,
    val locator: MediaLocator,
    val sourceType: MediaSourceType,
    val displayName: String,
    val parentPath: String,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val dimensions: MediaDimensions?,
    val modifiedAtEpochMs: Long?,
    val scriptSummary: ScriptSummary,
)

data class ScriptSummary(
    val actionCount: Int,
    val axes: Set<AxisId>,
) {
    val hasScript: Boolean get() = actionCount > 0
    val isMultiAxis: Boolean get() = axes.size > 1

    companion object {
        val None = ScriptSummary(actionCount = 0, axes = emptySet())
    }
}

@JvmInline
value class AxisId(val value: String) {
    init {
        require(value.matches(Regex("[A-Z][0-9]"))) { "Invalid TCode axis id: $value" }
    }
}
