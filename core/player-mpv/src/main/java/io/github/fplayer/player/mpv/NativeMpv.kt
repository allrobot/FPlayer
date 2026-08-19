package io.github.fplayer.player.mpv

import android.content.Context
import android.view.Surface

internal interface MpvBridge {
    fun setEventListener(listener: ((MpvBridgeEvent) -> Unit)?)
    fun create(context: Context)
    fun initialize()
    fun destroy()
    fun prepare(locator: String, resumePositionMs: Long, requestId: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Double)
    fun positionMs(): Long
    fun durationMs(): Long
    fun speed(): Double
    fun isPlaying(): Boolean
    fun isBuffering(): Boolean
    fun attachSurface(surface: Surface)
    fun detachSurface()
}

internal data class MpvBridgeEvent(
    val requestId: Long,
    val type: Type,
    val errorCode: Int = 0,
) {
    enum class Type { PREPARED, COMPLETED, PREPARE_FAILED }
}

internal object NativeMpv : MpvBridge {
    @Volatile
    private var eventListener: ((MpvBridgeEvent) -> Unit)? = null

    init {
        System.loadLibrary("fplayer_mpv")
    }

    override fun setEventListener(listener: ((MpvBridgeEvent) -> Unit)?) {
        eventListener = listener
    }

    external override fun create(context: Context)
    external override fun initialize()
    external override fun destroy()
    external override fun prepare(locator: String, resumePositionMs: Long, requestId: Long)
    external override fun play()
    external override fun pause()
    external override fun seekTo(positionMs: Long)
    external override fun setSpeed(speed: Double)
    external override fun positionMs(): Long
    external override fun durationMs(): Long
    external override fun speed(): Double
    external override fun isPlaying(): Boolean
    external override fun isBuffering(): Boolean
    external override fun attachSurface(surface: Surface)
    external override fun detachSurface()

    @JvmStatic
    fun onNativeEvent(requestId: Long, eventType: Int, errorCode: Int) {
        val type = when (eventType) {
            EVENT_PREPARED -> MpvBridgeEvent.Type.PREPARED
            EVENT_COMPLETED -> MpvBridgeEvent.Type.COMPLETED
            EVENT_PREPARE_FAILED -> MpvBridgeEvent.Type.PREPARE_FAILED
            else -> return
        }
        eventListener?.invoke(MpvBridgeEvent(requestId, type, errorCode))
    }

    private const val EVENT_PREPARED = 1
    private const val EVENT_COMPLETED = 2
    private const val EVENT_PREPARE_FAILED = 3
}
