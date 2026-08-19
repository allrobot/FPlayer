package io.github.fplayer.feature.feed

/** Persistence boundary for the user's custom playback-setting order. */
interface CustomPlaybackSettingsStore {
    /** Null means no saved value; an empty list is a committed empty selection. */
    fun readSelectedIds(): List<String>?

    fun writeSelectedIds(selectedIds: List<String>)
}

data class CustomPlaybackSettingItem(
    val id: String,
    val label: String = id,
)

enum class CustomSettingsSection { SELECTED, AVAILABLE }

data class CustomSettingsDrag(
    val sourceSection: CustomSettingsSection,
    val sourceIndex: Int,
    val targetSection: CustomSettingsSection,
    val targetIndex: Int,
    val pointerY: Float,
    val autoScrollVelocityPxPerSecond: Float,
)

data class CustomPlaybackSettingsSnapshot(
    val selected: List<CustomPlaybackSettingItem>,
    val available: List<CustomPlaybackSettingItem>,
    val rowHeightPx: Float,
    val drag: CustomSettingsDrag?,
    val insertionLine: CustomSettingsInsertionLine?,
    val persistedRevision: Long,
)

data class CustomSettingsInsertionLine(
    val section: CustomSettingsSection,
    /** Full-width line before this row; list.size means the line after the final row. */
    val index: Int,
)

enum class CustomSettingsAccessibilityAction { MOVE_UP, MOVE_DOWN, ADD, REMOVE }

/**
 * Platform-neutral state for the two-section custom playback settings editor.
 * Dragging only updates a preview. The store is written after a drop or an
 * accessibility action, so cancellation and rotation can restore the last commit.
 */
class CustomPlaybackSettingsStateMachine(
    allItems: List<CustomPlaybackSettingItem>,
    initiallySelectedIds: List<String> = emptyList(),
    private val store: CustomPlaybackSettingsStore? = null,
    private val rowHeightPx: Float = 56f,
) {
    companion object {
        const val EDGE_SCROLL_ZONE_PX = 72f
        const val EDGE_SCROLL_SLOW_PX_PER_SECOND = 180f
        const val EDGE_SCROLL_FAST_PX_PER_SECOND = 720f
    }

    init {
        require(rowHeightPx > 0f)
        require(allItems.map { it.id }.distinct().size == allItems.size) { "setting ids must be unique" }
    }

    private val itemById = allItems.associateBy { it.id }
    private var selectedIdsValue = restoreSelected(initiallySelectedIds)
    private var dragValue: CustomSettingsDrag? = null
    private var insertionLineValue: CustomSettingsInsertionLine? = null
    private var revisionValue = 0L

    private fun restoreSelected(fallback: List<String>): MutableList<String> {
        val source = store?.readSelectedIds() ?: fallback
        return source.filter { it in itemById }.distinct().toMutableList()
    }

    fun addAvailable(id: String): Boolean {
        if (id !in itemById || id in selectedIdsValue) return false
        selectedIdsValue += id
        commit()
        return true
    }

    fun removeSelected(id: String): Boolean {
        if (!selectedIdsValue.remove(id)) return false
        commit()
        return true
    }

    fun moveSelectedUp(index: Int): Boolean = moveSelected(index, index - 1)

    fun moveSelectedDown(index: Int): Boolean = moveSelected(index, index + 1)

    fun performAccessibilityAction(action: CustomSettingsAccessibilityAction, index: Int): Boolean = when (action) {
        CustomSettingsAccessibilityAction.MOVE_UP -> moveSelectedUp(index)
        CustomSettingsAccessibilityAction.MOVE_DOWN -> moveSelectedDown(index)
        CustomSettingsAccessibilityAction.ADD -> availableAt(index)?.let { addAvailable(it.id) } ?: false
        CustomSettingsAccessibilityAction.REMOVE -> selectedAt(index)?.let { removeSelected(it.id) } ?: false
    }

    fun onDragStart(section: CustomSettingsSection, index: Int): Boolean {
        val size = sectionItems(section).size
        if (index !in 0 until size) return false
        dragValue = CustomSettingsDrag(section, index, section, index, 0f, 0f)
        insertionLineValue = CustomSettingsInsertionLine(section, index)
        return true
    }

    /**
     * Updates the insertion line and edge auto-scroll speed. The target index is
     * calculated from stable row heights and is never committed here.
     */
    fun onDragMove(pointerY: Float, viewportTop: Float, viewportBottom: Float): Boolean {
        val drag = dragValue ?: return false
        if (!pointerY.isFinite() || !viewportTop.isFinite() || !viewportBottom.isFinite() || viewportBottom <= viewportTop) return false
        val targetSection = drag.targetSection
        val size = sectionItems(targetSection).size
        val rawIndex = ((pointerY - viewportTop + rowHeightPx / 2f) / rowHeightPx).toInt().coerceIn(0, size)
        val velocity = edgeScrollVelocity(pointerY, viewportTop, viewportBottom)
        val updated = drag.copy(targetSection = targetSection, targetIndex = rawIndex, pointerY = pointerY, autoScrollVelocityPxPerSecond = velocity)
        dragValue = updated
        insertionLineValue = CustomSettingsInsertionLine(targetSection, rawIndex)
        return true
    }

    fun onDragMove(targetSection: CustomSettingsSection, targetIndex: Int, pointerY: Float = 0f, autoScrollVelocityPxPerSecond: Float = 0f): Boolean {
        val drag = dragValue ?: return false
        val size = sectionItems(targetSection).size
        val index = targetIndex.coerceIn(0, size)
        dragValue = drag.copy(targetSection = targetSection, targetIndex = index, pointerY = pointerY, autoScrollVelocityPxPerSecond = autoScrollVelocityPxPerSecond)
        insertionLineValue = CustomSettingsInsertionLine(targetSection, index)
        return true
    }

    fun onDrop(): Boolean {
        val drag = dragValue ?: return false
        val source = sectionItems(drag.sourceSection).getOrNull(drag.sourceIndex)?.id ?: return cancelDragAndReturnFalse()
        val previousSelectedIds = selectedIdsValue.toList()
        val destination = sectionItems(drag.targetSection).toMutableList()
        if (drag.sourceSection == drag.targetSection) {
            destination.removeAt(drag.sourceIndex)
            val adjustedIndex = if (drag.targetIndex > drag.sourceIndex) drag.targetIndex - 1 else drag.targetIndex
            destination.add(adjustedIndex.coerceIn(0, destination.size), itemById.getValue(source))
        } else {
            destination.add(drag.targetIndex.coerceIn(0, destination.size), itemById.getValue(source))
        }
        when (drag.targetSection) {
            CustomSettingsSection.SELECTED -> {
                selectedIdsValue = destination.map { it.id }.toMutableList()
            }
            CustomSettingsSection.AVAILABLE -> {
                val movedSelected = drag.sourceSection == CustomSettingsSection.SELECTED
                if (movedSelected) selectedIdsValue.remove(source)
            }
        }
        clearDrag()
        if (selectedIdsValue != previousSelectedIds) commit()
        return true
    }

    fun onDragCancel() { clearDrag() }

    /** Rotation must not persist an in-flight preview. */
    fun onRotation() { clearDrag() }

    fun snapshot(): CustomPlaybackSettingsSnapshot {
        val selected = selectedIdsValue.map { itemById.getValue(it) }
        val selectedSet = selectedIdsValue.toSet()
        val available = itemById.values.filter { it.id !in selectedSet }
        return CustomPlaybackSettingsSnapshot(selected, available, rowHeightPx, dragValue, insertionLineValue, revisionValue)
    }

    private fun moveSelected(from: Int, to: Int): Boolean {
        if (from !in selectedIdsValue.indices || to !in selectedIdsValue.indices) return false
        selectedIdsValue.add(to, selectedIdsValue.removeAt(from))
        commit()
        return true
    }

    private fun sectionItems(section: CustomSettingsSection): List<CustomPlaybackSettingItem> = when (section) {
        CustomSettingsSection.SELECTED -> selectedIdsValue.map { itemById.getValue(it) }
        CustomSettingsSection.AVAILABLE -> itemById.values.filter { it.id !in selectedIdsValue }
    }

    private fun selectedAt(index: Int) = sectionItems(CustomSettingsSection.SELECTED).getOrNull(index)
    private fun availableAt(index: Int) = sectionItems(CustomSettingsSection.AVAILABLE).getOrNull(index)

    private fun edgeScrollVelocity(y: Float, top: Float, bottom: Float): Float {
        if (y <= top) return -EDGE_SCROLL_FAST_PX_PER_SECOND
        if (y >= bottom) return EDGE_SCROLL_FAST_PX_PER_SECOND
        val topDistance = (y - top).coerceAtLeast(0f)
        val bottomDistance = (bottom - y).coerceAtLeast(0f)
        return when {
            topDistance <= EDGE_SCROLL_ZONE_PX && topDistance <= bottomDistance -> -scrollSpeed(topDistance)
            bottomDistance <= EDGE_SCROLL_ZONE_PX -> scrollSpeed(bottomDistance)
            else -> 0f
        }
    }

    private fun scrollSpeed(distance: Float): Float = if (distance <= EDGE_SCROLL_ZONE_PX / 2f) {
        EDGE_SCROLL_FAST_PX_PER_SECOND
    } else EDGE_SCROLL_SLOW_PX_PER_SECOND

    private fun commit() {
        revisionValue += 1
        store?.writeSelectedIds(selectedIdsValue.toList())
    }

    private fun clearDrag() {
        dragValue = null
        insertionLineValue = null
    }

    private fun cancelDragAndReturnFalse(): Boolean {
        clearDrag()
        return false
    }
}
