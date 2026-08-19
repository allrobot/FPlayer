package io.github.fplayer.feature.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekCoordinatorTest {
    @Test
    fun confirmationRequiresEnginePositionAndMatchingToken() {
        val coordinator = PlaybackSeekCoordinator(toleranceMs = 20)
        assertEquals(SeekConfirmationResult.Pending, coordinator.submit(1L, 600L))
        assertEquals(SeekConfirmationResult.Pending, coordinator.observe(1L, 500L))
        assertEquals(SeekConfirmationResult.Failed(2L, "STALE_SEEK_TOKEN"), coordinator.observe(2L, 600L))
        assertEquals(SeekConfirmationResult.Confirmed(1L, 610L), coordinator.observe(1L, 610L))
    }

    @Test
    fun failedSeekClearsOnlyTheMatchingRequest() {
        val coordinator = PlaybackSeekCoordinator()
        coordinator.submit(3L, 100L)
        assertEquals(SeekConfirmationResult.Failed(3L, "ENGINE_REJECTED"), coordinator.fail(3L, "ENGINE_REJECTED"))
        assertEquals(SeekConfirmationResult.Failed(3L, "NO_PENDING_SEEK"), coordinator.observe(3L, 100L))
    }
}
