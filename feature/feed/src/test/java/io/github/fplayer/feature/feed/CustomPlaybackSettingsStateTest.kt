package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingSettingsStore(initial: List<String>? = null) : CustomPlaybackSettingsStore {
    var ids: List<String>? = initial
    var writes = 0

    override fun readSelectedIds(): List<String>? = ids

    override fun writeSelectedIds(selectedIds: List<String>) {
        ids = selectedIds
        writes += 1
    }
}

class CustomPlaybackSettingsStateTest {
    private val items = listOf(
        CustomPlaybackSettingItem("speed", "Speed"),
        CustomPlaybackSettingItem("rotation", "Rotation"),
        CustomPlaybackSettingItem("subtitles", "Subtitles"),
        CustomPlaybackSettingItem("heatmap", "Heatmap"),
    )

    @Test fun addRemoveAndAccessibilityActionsPersistCommittedOrder() {
        val store = RecordingSettingsStore()
        val state = CustomPlaybackSettingsStateMachine(items, initiallySelectedIds = listOf("speed"), store = store)
        assertTrue(state.addAvailable("rotation"))
        assertEquals(listOf("speed", "rotation"), state.snapshot().selected.map { it.id })
        assertTrue(state.performAccessibilityAction(CustomSettingsAccessibilityAction.MOVE_UP, 1))
        assertEquals(listOf("rotation", "speed"), state.snapshot().selected.map { it.id })
        assertTrue(state.performAccessibilityAction(CustomSettingsAccessibilityAction.REMOVE, 0))
        assertEquals(listOf("speed"), state.snapshot().selected.map { it.id })
        assertTrue(store.writes >= 3)
    }

    @Test fun dragShowsFullWidthInsertionLineAndCommitsOnlyOnDrop() {
        val state = CustomPlaybackSettingsStateMachine(items, initiallySelectedIds = listOf("speed", "rotation"))
        assertTrue(state.onDragStart(CustomSettingsSection.SELECTED, 0))
        assertTrue(state.onDragMove(CustomSettingsSection.SELECTED, 2, pointerY = 120f))
        assertEquals(listOf("speed", "rotation"), state.snapshot().selected.map { it.id })
        assertEquals(CustomSettingsInsertionLine(CustomSettingsSection.SELECTED, 2), state.snapshot().insertionLine)
        assertTrue(state.onDrop())
        assertEquals(listOf("rotation", "speed"), state.snapshot().selected.map { it.id })
        assertEquals(null, state.snapshot().insertionLine)
    }

    @Test fun rowMidpointChoosesInsertionLineAndNoOpDropDoesNotPersist() {
        val store = RecordingSettingsStore(listOf("speed", "rotation"))
        val state = CustomPlaybackSettingsStateMachine(items, store = store)
        assertTrue(state.onDragStart(CustomSettingsSection.SELECTED, 0))
        assertTrue(state.onDragMove(27f, viewportTop = 0f, viewportBottom = 400f))
        assertEquals(CustomSettingsInsertionLine(CustomSettingsSection.SELECTED, 0), state.snapshot().insertionLine)
        assertTrue(state.onDragMove(29f, viewportTop = 0f, viewportBottom = 400f))
        assertEquals(CustomSettingsInsertionLine(CustomSettingsSection.SELECTED, 1), state.snapshot().insertionLine)
        assertTrue(state.onDrop())
        assertEquals(listOf("speed", "rotation"), state.snapshot().selected.map { it.id })
        assertEquals(0, store.writes)
    }

    @Test fun crossSectionDropAddsOrRemovesWithoutChangingRowHeight() {
        val state = CustomPlaybackSettingsStateMachine(items, initiallySelectedIds = listOf("speed"))
        assertTrue(state.onDragStart(CustomSettingsSection.AVAILABLE, 0))
        assertTrue(state.onDragMove(CustomSettingsSection.SELECTED, 1))
        assertTrue(state.onDrop())
        assertEquals(listOf("speed", "rotation"), state.snapshot().selected.map { it.id })
        assertEquals(56f, state.snapshot().rowHeightPx, 0f)

        assertTrue(state.onDragStart(CustomSettingsSection.SELECTED, 0))
        assertTrue(state.onDragMove(CustomSettingsSection.AVAILABLE, 0))
        assertTrue(state.onDrop())
        assertEquals(listOf("rotation"), state.snapshot().selected.map { it.id })
    }

    @Test fun edgeAutoScrollIsTieredAndCancelRestoresCommittedOrder() {
        val state = CustomPlaybackSettingsStateMachine(items, initiallySelectedIds = listOf("speed", "rotation"))
        assertTrue(state.onDragStart(CustomSettingsSection.SELECTED, 0))
        assertTrue(state.onDragMove(10f, viewportTop = 0f, viewportBottom = 400f))
        assertEquals(-720f, state.snapshot().drag!!.autoScrollVelocityPxPerSecond, 0f)
        assertTrue(state.onDragMove(60f, viewportTop = 0f, viewportBottom = 400f))
        assertEquals(-180f, state.snapshot().drag!!.autoScrollVelocityPxPerSecond, 0f)
        state.onDragCancel()
        assertEquals(listOf("speed", "rotation"), state.snapshot().selected.map { it.id })
        assertFalse(state.snapshot().drag != null)
    }

    @Test fun rotationDropsPreviewAndNewInstanceRestoresEmptyPersistedSelection() {
        val store = RecordingSettingsStore(listOf("speed"))
        val state = CustomPlaybackSettingsStateMachine(items, initiallySelectedIds = listOf("rotation"), store = store)
        assertEquals(listOf("speed"), state.snapshot().selected.map { it.id })
        assertTrue(state.onDragStart(CustomSettingsSection.SELECTED, 0))
        state.onRotation()
        assertEquals(null, state.snapshot().insertionLine)
        store.ids = emptyList()
        val restored = CustomPlaybackSettingsStateMachine(items, initiallySelectedIds = listOf("speed"), store = store)
        assertTrue(restored.snapshot().selected.isEmpty())
    }
}
