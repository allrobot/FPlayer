package io.github.fplayer.player.mpv

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.core.player.PlaybackRequest
import io.github.fplayer.core.player.PlayerEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibMpvPlayerInstrumentedTest {
    @Test
    fun syntheticVideoSupportsCorePlaybackLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = File(context.cacheDir, "synthetic.mp4")
        context.assets.open("synthetic.mp4").use { input ->
            media.outputStream().use(input::copyTo)
        }
        val player = LibMpvPlayer(context)
        try {
            player.prepare(
                PlaybackRequest(
                    mediaId = MediaId("synthetic"),
                    locator = MediaLocator(media.absolutePath),
                    resumePositionMs = 400,
                ),
            )
            player.play()
            await { player.snapshot().durationMs?.let { it >= 1_500 } == true }
            await { player.snapshot().positionMs > 450 }

            player.pause()
            await { !player.snapshot().isPlaying }
            player.seekTo(1_000)
            await { player.snapshot().positionMs in 850..1_250 }
            player.setSpeed(1.5)
            await { player.snapshot().speed == 1.5 }

            val snapshot = player.snapshot()
            assertEquals(MediaId("synthetic"), snapshot.mediaId)
            assertFalse(snapshot.isPlaying)
            assertTrue(snapshot.durationMs!! >= 1_500)
        } finally {
            player.release()
            player.release()
            media.delete()
        }
    }

    @Test
    fun nativePreparedAndCompletionEventsRetainRequestGenerationAcrossReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = File(context.cacheDir, "synthetic-events.mp4")
        context.assets.open("synthetic.mp4").use { input ->
            media.outputStream().use(input::copyTo)
        }
        val events = CopyOnWriteArrayList<PlayerEvent>()
        val player = LibMpvPlayer(context)
        player.setEventListener(events::add)
        try {
            player.prepare(PlaybackRequest(MediaId("synthetic"), MediaLocator(media.absolutePath), generation = 41))
            player.play()
            await { events.any { it is PlayerEvent.Completed && it.generation == 41L } }

            player.prepare(PlaybackRequest(MediaId("synthetic"), MediaLocator(media.absolutePath), generation = 42))
            player.play()
            await { events.any { it is PlayerEvent.Completed && it.generation == 42L } }

            assertTrue(events.any { it is PlayerEvent.Prepared && it.generation == 41L })
            assertTrue(events.any { it is PlayerEvent.Prepared && it.generation == 42L })
            assertEquals(2, events.count { it is PlayerEvent.Completed })
        } finally {
            player.release()
            media.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for player state", condition())
    }
}
