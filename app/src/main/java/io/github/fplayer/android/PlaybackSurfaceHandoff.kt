package io.github.fplayer.android

/** Keeps surface ownership alive until the host has explicitly handed it back. */
class PlaybackSurfaceHandoff(
    private val detach: () -> Unit,
) {
    private var attached = false
    private var hostStopped = false

    val hasAttachedSurface: Boolean
        get() = attached

    fun recordAttachment(succeeded: Boolean): Boolean {
        if (!succeeded) return false
        if (hostStopped) {
            detach()
            return true
        }
        attached = true
        return succeeded
    }

    fun onHostStopped() {
        hostStopped = true
        detachIfAttached()
    }

    fun onHostStarted() {
        hostStopped = false
    }

    fun onSurfaceDestroyed() {
        detachIfAttached()
    }

    private fun detachIfAttached() {
        if (!attached) return
        attached = false
        detach()
    }
}
