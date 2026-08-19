package io.github.fplayer.feature.feed

import io.github.fplayer.core.index.MaterializedOrder
import io.github.fplayer.core.index.SortDirection
import io.github.fplayer.core.index.SortField
import io.github.fplayer.core.index.SortSpec
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.core.model.MediaLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionTest {
    private val a = FeedMedia(MediaId("a"), MediaLocator("file:///a"))
    private val b = FeedMedia(MediaId("b"), MediaLocator("file:///b"))
    private val c = FeedMedia(MediaId("c"), MediaLocator("file:///c"))

    @Test fun preparesThreeSlotsAndStartsOnlyCurrentAfterCallback() {
        val factory = RecordingFactory()
        val session = session(factory)
        factory.prepares.clear()
        session.select(1)
        assertEquals(listOf("PREVIOUS:a", "CURRENT:b", "NEXT:c"), factory.prepares.map { "${it.slot}:${it.item.id.value}" })
        assertTrue(factory.player(FeedSlot.CURRENT).calls.isEmpty())
        session.onPrepared(FeedSlot.CURRENT, b.id)
        assertEquals(listOf("play"), factory.player(FeedSlot.CURRENT).calls)
    }

    @Test fun stalePrepareCannotStartAfterFastSwipe() {
        val factory = RecordingFactory()
        val session = session(factory)
        session.select(0)
        val oldToken = session.callbackToken()
        session.select(2)
        session.onPrepared(FeedSlot.CURRENT, a.id, oldToken)
        assertTrue(factory.player(FeedSlot.CURRENT).calls.none { it == "play" })
        session.onPrepared(FeedSlot.CURRENT, c.id)
        assertEquals(listOf("play"), factory.player(FeedSlot.CURRENT).calls)
    }

    @Test fun orderChangeKeepsCurrentByIdAndRebuildsGeneration() {
        val factory = RecordingFactory()
        val session = session(factory)
        session.select(1)
        session.setOrder(order(listOf(c.id, b.id, a.id), 2))
        assertEquals(b.id, session.snapshot().current?.id)
        assertEquals(2L, session.snapshot().order?.generation)
    }

    @Test fun missingCurrentFallsBackAndDecodeFailureDoesNotPlayWrongItem() {
        val factory = RecordingFactory()
        val session = session(factory)
        session.select(1)
        session.setItems(listOf(a, c))
        assertEquals(a.id, session.snapshot().current?.id)
        session.onPrepareFailed(FeedSlot.CURRENT, a.id, "DECODE")
        assertFalse(factory.player(FeedSlot.CURRENT).calls.contains("play"))
    }

    @Test fun checkpointAndLoopArePersistedDeterministically() {
        val checkpoints = RecordingCheckpoints()
        val session = PlaybackSession(RecordingFactory(), checkpoints).also {
            it.setItems(listOf(a, b, c)); it.setOrder(order(listOf(a.id, b.id, c.id), 1)); it.select(0)
        }
        session.saveCheckpoint(-5)
        session.setLooping(true)
        session.onPrepared(FeedSlot.CURRENT, a.id)
        session.onCompleted()
        assertEquals(listOf("a:0"), checkpoints.saved)
        assertEquals(listOf("a"), checkpoints.completed)
    }

    @Test fun emptyOrderClearsSelectionWithoutThrowing() {
        val session = session(RecordingFactory())
        session.setOrder(order(emptyList(), 2))
        assertEquals(null, session.snapshot().selectedIndex)
        assertEquals(null, session.snapshot().current)
    }

    @Test fun queuePreparationWaitsForExplicitPlayAndKeepsInFlightGeneration() {
        val factory = RecordingFactory()
        val session = session(factory)
        val originalToken = session.callbackToken()

        session.select(0)
        assertEquals(originalToken, session.callbackToken())
        session.onPrepared(FeedSlot.CURRENT, a.id, originalToken)

        assertEquals(listOf("pause", "play"), factory.player(FeedSlot.CURRENT).calls)
    }

    @Test fun sameMediaIdWithNewLocatorIsPreparedAgain() {
        val factory = RecordingFactory()
        val session = session(factory)
        val updated = FeedMedia(a.id, MediaLocator("file:///a-new"))

        session.setItems(listOf(updated, b, c))

        assertEquals(updated, factory.prepares.last { it.slot == FeedSlot.CURRENT }.item)
    }

    @Test fun automaticLoopOnlyRecordsOneCompletionUntilSelectionChanges() {
        val checkpoints = RecordingCheckpoints()
        val session = PlaybackSession(RecordingFactory(), checkpoints).also {
            it.setItems(listOf(a, b, c)); it.setOrder(order(listOf(a.id, b.id, c.id), 1)); it.select(0)
            it.setLooping(true); it.onPrepared(FeedSlot.CURRENT, a.id)
        }

        repeat(10_000) {
            session.onCompleted()
            session.onPrepared(FeedSlot.CURRENT, a.id)
        }
        assertEquals(listOf("a"), checkpoints.completed)

        session.setLooping(false)
        session.onCompleted()
        session.onPrepared(FeedSlot.CURRENT, b.id)
        session.onCompleted()
        assertEquals(listOf("a", "b"), checkpoints.completed)
    }

    private fun session(factory: RecordingFactory) = PlaybackSession(factory).also {
        it.setItems(listOf(a, b, c)); it.setOrder(order(listOf(a.id, b.id, c.id), 1))
    }

    private fun order(ids: List<MediaId>, generation: Long) = MaterializedOrder("scope", SortSpec(SortField.TITLE, SortDirection.ASCENDING), generation, ids)
}

private class RecordingFactory : SlotPlayerFactory {
    data class Prepare(val slot: FeedSlot, val item: FeedMedia)
    val prepares = mutableListOf<Prepare>()
    private val players = mutableMapOf<FeedSlot, RecordingPlayer>()
    override fun create(slot: FeedSlot): SlotPlayer = RecordingPlayer().also { players[slot] = it }
    fun player(slot: FeedSlot) = players.getValue(slot)
    inner class RecordingPlayer : SlotPlayer {
        val calls = mutableListOf<String>()
        override fun prepare(item: FeedMedia, resumePositionMs: Long, generation: Long) {
            prepares += Prepare(players.entries.first { it.value === this }.key, item)
        }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override fun release() { calls += "release" }
    }
}

private class RecordingCheckpoints : PlaybackCheckpointStore {
    val saved = mutableListOf<String>(); val completed = mutableListOf<String>()
    override fun resumePositionMs(mediaId: MediaId) = 0L
    override fun saveResumePosition(mediaId: MediaId, positionMs: Long) { saved += "${mediaId.value}:$positionMs" }
    override fun markCompleted(mediaId: MediaId) { completed += mediaId.value }
}
