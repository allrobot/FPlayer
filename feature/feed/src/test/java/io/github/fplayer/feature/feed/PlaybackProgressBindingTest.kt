package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressBindingTest {
    @Test
    fun pointerUpSendsTheSubmittedPositionAndTokenToPlayback() {
        val requests = mutableListOf<Pair<Long, Long>>()
        val binding = PlaybackProgressBinding(
            durationMs = 1_000,
            onSeekRequested = { positionMs, token -> requests += positionMs to token },
        )

        binding.onPointerDown()
        binding.onPointerMove(0.6f)
        binding.onPointerUp()

        assertEquals(listOf(600L to 1L), requests)
        assertTrue(binding.snapshot().feedPagingBlocked)
    }

    @Test
    fun mediaClockUpdatesAndOnlyMatchingSeekConfirmationSettlesTheBinding() {
        val binding = PlaybackProgressBinding(1_000) { _, _ -> }

        binding.updateMediaClock(positionMs = 200, durationMs = 1_000)
        binding.onPointerDown()
        binding.onPointerMove(0.7f)
        binding.onPointerUp()

        assertFalse(binding.onSeekConfirmed(token = 2, positionMs = 700))
        assertTrue(binding.onSeekConfirmed(token = 1, positionMs = 700))
        assertEquals(700L, binding.snapshot().mediaPositionMs)
    }
}
