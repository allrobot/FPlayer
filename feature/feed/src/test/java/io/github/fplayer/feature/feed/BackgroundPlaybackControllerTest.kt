package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackControllerTest {
    @Test fun hiddenWithoutBackgroundPausesAndStopsDevices() {
        val callbacks = RecordingCallbacks()
        val controller = BackgroundPlaybackController(callbacks)
        controller.setDevicesActive(true)
        controller.startPlayback()
        controller.onActivityVisibilityChanged(false)
        assertEquals(BackgroundPlaybackState.PAUSED, controller.state)
        assertEquals(listOf("acquire", "resume", "pause", "release", "devices"), callbacks.events)
    }

    @Test fun rotationKeepsServiceStateAndDoesNotDuplicateResume() {
        val callbacks = RecordingCallbacks()
        val controller = BackgroundPlaybackController(callbacks)
        controller.setBackgroundPlaybackEnabled(true)
        controller.startPlayback()
        controller.onActivityVisibilityChanged(false)
        controller.onActivityVisibilityChanged(true)
        assertEquals(BackgroundPlaybackState.PLAYING, controller.state)
        assertEquals(1, callbacks.events.count { it == "resume" })
        assertEquals(1, callbacks.events.count { it == "acquire" })
    }

    @Test fun notificationPauseReleasesResourcesAndPlayReacquires() {
        val callbacks = RecordingCallbacks()
        val controller = BackgroundPlaybackController(callbacks)
        controller.startPlayback()
        controller.pausePlayback()
        controller.startPlayback()
        assertEquals(listOf("acquire", "resume", "pause", "release", "acquire", "resume"), callbacks.events)
    }

    @Test fun serviceDestroyIsIdempotentForResources() {
        val callbacks = RecordingCallbacks()
        val controller = BackgroundPlaybackController(callbacks)
        controller.startPlayback()
        controller.onServiceDestroyed()
        controller.onServiceDestroyed()
        assertEquals(1, callbacks.events.count { it == "release" })
        assertEquals(1, callbacks.events.count { it == "stop" })
        assertFalse(controller.forceStopped)
    }

    @Test fun forceStopTerminatesFutureCommandsWithoutClaimingCleanup() {
        val callbacks = RecordingCallbacks()
        val controller = BackgroundPlaybackController(callbacks)
        controller.startPlayback()
        controller.onForceStop()
        controller.startPlayback()
        assertTrue(controller.forceStopped)
        assertEquals(1, callbacks.events.count { it == "resume" })
        assertEquals(1, callbacks.events.count { it == "release" })
    }

    @Test fun repeatedBackgroundCommandsDoNotDuplicatePlaybackOrResources() {
        val callbacks = RecordingCallbacks()
        val controller = BackgroundPlaybackController(callbacks)
        controller.setBackgroundPlaybackEnabled(true)
        repeat(10_000) {
            controller.startPlayback()
            controller.onActivityVisibilityChanged(false)
        }
        assertEquals(BackgroundPlaybackState.PLAYING, controller.state)
        assertEquals(1, callbacks.events.count { it == "resume" })
        assertEquals(1, callbacks.events.count { it == "acquire" })
        assertEquals(0, callbacks.events.count { it == "release" })
    }

    private class RecordingCallbacks : BackgroundPlaybackCallbacks {
        val events = mutableListOf<String>()
        override fun pauseMedia() { events += "pause" }
        override fun resumeMedia() { events += "resume" }
        override fun stopDevices() { events += "devices" }
        override fun stopPlayback() { events += "stop" }
        override fun acquirePlaybackResources() { events += "acquire" }
        override fun releasePlaybackResources() { events += "release" }
    }
}
