package io.github.fplayer.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSurfaceHandoffTest {
    @Test
    fun stopBeforeSurfaceDestroyedDetachesAttachedSurfaceExactlyOnce() {
        var detachCount = 0
        val handoff = PlaybackSurfaceHandoff { detachCount += 1 }

        assertTrue(handoff.recordAttachment(true))
        handoff.onHostStopped()
        handoff.onSurfaceDestroyed()

        assertEquals(1, detachCount)
        assertFalse(handoff.hasAttachedSurface)
    }

    @Test
    fun failedAttachmentDoesNotDetachLater() {
        var detachCount = 0
        val handoff = PlaybackSurfaceHandoff { detachCount += 1 }

        assertFalse(handoff.recordAttachment(false))
        handoff.onHostStopped()
        handoff.onSurfaceDestroyed()

        assertEquals(0, detachCount)
    }

    @Test
    fun attachmentThatArrivesAfterHostStopIsDetachedImmediately() {
        var detachCount = 0
        val handoff = PlaybackSurfaceHandoff { detachCount += 1 }

        handoff.onHostStopped()
        assertTrue(handoff.recordAttachment(true))

        assertEquals(1, detachCount)
        assertFalse(handoff.hasAttachedSurface)
    }

    @Test
    fun repeatedLateAttachmentsAfterHostStopDetachOnlyOnce() {
        var detachCount = 0
        val handoff = PlaybackSurfaceHandoff { detachCount += 1 }

        handoff.onHostStopped()
        assertTrue(handoff.recordAttachment(true))
        assertTrue(handoff.recordAttachment(true))

        assertEquals(1, detachCount)
        assertFalse(handoff.hasAttachedSurface)
    }
}
