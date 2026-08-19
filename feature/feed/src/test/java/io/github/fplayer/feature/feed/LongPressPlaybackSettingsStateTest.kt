package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressPlaybackSettingsStateTest {
    @Test fun centerLongPressOpensPanelAndRotationChangesPlacement() {
        val machine = LongPressPlaybackSettingsStateMachine()
        machine.onPointerDown(PlaybackTouchRegion.CENTER, 100L)
        machine.onTime(599L)
        assertFalse(machine.snapshot().panelOpen)
        machine.onTime(600L)
        assertTrue(machine.snapshot().panelOpen)
        assertEquals(PlaybackPanelPlacement.BOTTOM_SHEET, machine.snapshot().panelPlacement)
        assertTrue(machine.consumeLongPressHaptic())
        assertFalse(machine.consumeLongPressHaptic())
        machine.setOrientation(PlaybackPanelOrientation.LANDSCAPE)
        assertTrue(machine.snapshot().panelOpen)
        assertEquals(PlaybackPanelPlacement.SIDE_SHEET, machine.snapshot().panelPlacement)
    }

    @Test fun edgeHoldTemporarilySpeedsUpAndReleaseRestores() {
        val machine = LongPressPlaybackSettingsStateMachine(settings = PlaybackSettings(speed = 1f, temporarySpeed = 2f))
        machine.onPointerDown(PlaybackTouchRegion.LEFT_EDGE, 0L)
        machine.onTime(349L)
        assertEquals(1f, machine.snapshot().effectiveSpeed)
        machine.onTime(350L)
        assertEquals(2f, machine.snapshot().effectiveSpeed)
        assertEquals(PlaybackTouchRegion.LEFT_EDGE, machine.snapshot().temporarySpeedSide)
        assertTrue(machine.consumeEdgeSpeedHaptic())
        machine.onPointerUp()
        assertEquals(1f, machine.snapshot().effectiveSpeed)
        assertEquals(null, machine.snapshot().temporarySpeedSide)
    }

    @Test fun directionLockAndProgressCancelLongPressAndEdge() {
        val machine = LongPressPlaybackSettingsStateMachine()
        machine.onPointerDown(PlaybackTouchRegion.CENTER, 0L)
        machine.onDirectionLock()
        machine.onTime(1000L)
        assertFalse(machine.snapshot().panelOpen)
        machine.onPointerUp()
        machine.onPointerDown(PlaybackTouchRegion.RIGHT_EDGE, 0L)
        machine.onProgressDragStart()
        machine.onTime(1000L)
        assertEquals(null, machine.snapshot().temporarySpeedSide)
    }

    @Test fun systemBackEdgeWinsAndDisabledEdgeSpeedDoesNotOpenPanel() {
        val machine = LongPressPlaybackSettingsStateMachine(
            settings = PlaybackSettings(edgeTemporarySpeedEnabled = false),
        )
        machine.onPointerDown(PlaybackTouchRegion.RIGHT_EDGE, 0L)
        machine.onTime(1000L)
        assertEquals(1f, machine.snapshot().effectiveSpeed)
        machine.onPointerUp()
        machine.onPointerDown(PlaybackTouchRegion.SYSTEM_BACK_EDGE, 0L)
        machine.onTime(1000L)
        assertFalse(machine.snapshot().panelOpen)
    }

    @Test fun settingsUpdatePreservesOpenPanelAndClearsActiveEdgeWhenDisabled() {
        val machine = LongPressPlaybackSettingsStateMachine()
        machine.onPointerDown(PlaybackTouchRegion.LEFT_EDGE, 0L)
        machine.onTime(350L)
        machine.onPointerUp()
        machine.onPointerDown(PlaybackTouchRegion.CENTER, 1000L)
        machine.onTime(1500L)
        machine.updateSettings(PlaybackSettings(speed = 1.5f, edgeTemporarySpeedEnabled = false))
        assertTrue(machine.snapshot().panelOpen)
        assertEquals(1.5f, machine.snapshot().effectiveSpeed)
    }
}
