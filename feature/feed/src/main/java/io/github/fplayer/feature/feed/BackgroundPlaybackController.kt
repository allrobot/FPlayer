package io.github.fplayer.feature.feed

/** Small lifecycle coordinator shared by the Android service and deterministic tests. */
enum class BackgroundPlaybackState { IDLE, PLAYING, PAUSED }

interface BackgroundPlaybackCallbacks {
    fun pauseMedia()
    fun resumeMedia()
    fun stopDevices()
    fun stopPlayback()

    /** Lifecycle hooks are intentionally optional so JVM tests can model Android failures. */
    fun acquirePlaybackResources() = Unit
    fun releasePlaybackResources() = Unit
}

class BackgroundPlaybackController(
    private val callbacks: BackgroundPlaybackCallbacks,
) {
    var state: BackgroundPlaybackState = BackgroundPlaybackState.IDLE
        private set
    var backgroundPlaybackEnabled: Boolean = false
        private set
    var devicesActive: Boolean = false
        private set
    var activityVisible: Boolean = false
        private set
    var forceStopped: Boolean = false
        private set

    private var resumeWhenVisible = false
    private var resourcesHeld = false
    private var stopPlaybackSent = false

    fun startPlayback() {
        if (forceStopped || state == BackgroundPlaybackState.PLAYING) return
        stopPlaybackSent = false
        state = BackgroundPlaybackState.PLAYING
        resumeWhenVisible = true
        holdResources()
        callbacks.resumeMedia()
    }

    fun pausePlayback() {
        if (state == BackgroundPlaybackState.PLAYING) callbacks.pauseMedia()
        state = BackgroundPlaybackState.PAUSED
        resumeWhenVisible = false
        releaseResources()
    }

    fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        backgroundPlaybackEnabled = enabled
        if (!enabled && !activityVisible) {
            pausePlayback()
            stopDevices()
        }
    }

    fun setDevicesActive(active: Boolean) {
        devicesActive = active
    }

    fun onActivityVisibilityChanged(visible: Boolean) {
        activityVisible = visible
        if (!visible) {
            if (!backgroundPlaybackEnabled && state == BackgroundPlaybackState.PLAYING) {
                pausePlayback()
                resumeWhenVisible = true
                stopDevices()
            }
        } else if (resumeWhenVisible && state == BackgroundPlaybackState.PAUSED && !forceStopped) {
            startPlayback()
        }
    }

    fun stopDevices() {
        if (!devicesActive) return
        devicesActive = false
        callbacks.stopDevices()
    }

    fun exitBackgroundPlayback() {
        backgroundPlaybackEnabled = false
        resumeWhenVisible = false
        pausePlayback()
        stopDevices()
        state = BackgroundPlaybackState.IDLE
        stopPlaybackOnce()
        releaseResources()
    }

    fun onServiceDestroyed() {
        stopDevices()
        stopPlaybackOnce()
        state = BackgroundPlaybackState.IDLE
        resumeWhenVisible = false
        releaseResources()
    }

    private fun stopPlaybackOnce() {
        if (!stopPlaybackSent) {
            stopPlaybackSent = true
            callbacks.stopPlayback()
        }
    }

    /** force-stop does not promise callbacks; this method is for deterministic fault-injection tests. */
    fun onForceStop() {
        forceStopped = true
        backgroundPlaybackEnabled = false
        activityVisible = false
        state = BackgroundPlaybackState.IDLE
        resumeWhenVisible = false
        releaseResources()
    }

    private fun holdResources() {
        if (!resourcesHeld) {
            resourcesHeld = true
            callbacks.acquirePlaybackResources()
        }
    }

    private fun releaseResources() {
        if (resourcesHeld) {
            resourcesHeld = false
            callbacks.releasePlaybackResources()
        }
    }
}
