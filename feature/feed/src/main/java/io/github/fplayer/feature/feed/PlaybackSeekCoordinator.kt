package io.github.fplayer.feature.feed

sealed interface SeekConfirmationResult {
    data class Confirmed(val token: Long, val positionMs: Long) : SeekConfirmationResult
    data class Failed(val token: Long, val reason: String) : SeekConfirmationResult
    data object Pending : SeekConfirmationResult
}

/** Validates seek acknowledgements at the playback boundary, independent of Compose polling. */
class PlaybackSeekCoordinator(
    private val toleranceMs: Long = 250L,
) {
    private data class Pending(val token: Long, val requestedPositionMs: Long)

    private var pending: Pending? = null

    init { require(toleranceMs >= 0L) }

    fun submit(token: Long, requestedPositionMs: Long): SeekConfirmationResult {
        require(token > 0L)
        require(requestedPositionMs >= 0L)
        pending = Pending(token, requestedPositionMs)
        return SeekConfirmationResult.Pending
    }

    fun observe(token: Long, actualPositionMs: Long): SeekConfirmationResult {
        val request = pending ?: return SeekConfirmationResult.Failed(token, "NO_PENDING_SEEK")
        if (request.token != token) return SeekConfirmationResult.Failed(token, "STALE_SEEK_TOKEN")
        if (actualPositionMs < 0L) return SeekConfirmationResult.Failed(token, "INVALID_POSITION")
        if (kotlin.math.abs(actualPositionMs - request.requestedPositionMs) > toleranceMs) {
            return SeekConfirmationResult.Pending
        }
        pending = null
        return SeekConfirmationResult.Confirmed(token, actualPositionMs)
    }

    fun fail(token: Long, reason: String): SeekConfirmationResult {
        if (pending?.token != token) return SeekConfirmationResult.Failed(token, "STALE_SEEK_TOKEN")
        pending = null
        return SeekConfirmationResult.Failed(token, reason)
    }
}
