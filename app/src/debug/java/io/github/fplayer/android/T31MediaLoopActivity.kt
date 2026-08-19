package io.github.fplayer.android

import android.util.Log
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.feature.feed.FeedMedia
import java.io.File

/** Debug-only entry point for T31 native background-loop verification. */
class T31MediaLoopActivity : MainActivity() {
    override fun onPlaybackBinderConnected(binder: PlaybackService.LocalBinder) {
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_STATUS -> reportStatus(binder)
            MODE_CLEANUP -> cleanup(binder)
            else -> startSyntheticLoop(binder)
        }
    }

    private fun startSyntheticLoop(binder: PlaybackService.LocalBinder) {
        val mediaFile = File(cacheDir, SYNTHETIC_FILE)
        assets.open(SYNTHETIC_ASSET).use { input ->
            mediaFile.outputStream().use(input::copyTo)
        }
        val mediaId = MediaId(SYNTHETIC_MEDIA_ID)
        binder.replaceQueue(listOf(FeedMedia(mediaId, MediaLocator(mediaFile.absolutePath))), mediaId)
        binder.setLooping(true)
        enableBackgroundPlayback(binder)
        binder.selectMedia(mediaId)
        Log.i(TAG, "synthetic-loop-started")
    }

    private fun reportStatus(binder: PlaybackService.LocalBinder) {
        val status = binder.diagnostics()
        Log.i(
            TAG,
            "prepared=${status.preparedEvents} completed=${status.completedEvents} " +
                "failed=${status.failedEvents} playing=${status.isPlaying} positionMs=${status.positionMs}",
        )
        finish()
    }

    private fun cleanup(binder: PlaybackService.LocalBinder) {
        binder.exitBackgroundPlayback()
        val removed = File(cacheDir, SYNTHETIC_FILE).let { !it.exists() || it.delete() }
        Log.i(TAG, "synthetic-loop-cleanup removed=$removed")
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_STATUS = "status"
        const val MODE_CLEANUP = "cleanup"
        private const val TAG = "FPlayerT31"
        private const val SYNTHETIC_ASSET = "synthetic.mp4"
        private const val SYNTHETIC_FILE = "t31-synthetic.mp4"
        private const val SYNTHETIC_MEDIA_ID = "t31-synthetic"
    }
}
