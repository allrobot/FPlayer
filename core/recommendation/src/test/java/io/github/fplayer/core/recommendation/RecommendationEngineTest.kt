package io.github.fplayer.core.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `half life is exact and future activity does not gain weight`() {
        assertEquals(0.5, RecommendationEngine.decayFactor(21.0, 21.0), 1e-12)
        assertEquals(0.0, RecommendationEngine.decayFactor(0.0, 21.0) - 1.0, 1e-12)
    }

    @Test
    fun `like beats a thousand background loops and dislike is filtered`() {
        val liked = candidate("liked", signals = RecommendationSignals(liked = true))
        val loops = candidate("loops", signals = RecommendationSignals(backgroundLoopCount = 1000))
        val disliked = candidate("no", signals = RecommendationSignals(disliked = true))
        val result = RecommendationEngine().rank(listOf(loops, disliked, liked), now, 3, 7L)
        assertEquals(listOf("liked", "loops"), result.map { it.mediaId })
        assertFalse(result.any { it.mediaId == "no" })
    }

    @Test
    fun `ranking and exploration are deterministic for a generation`() {
        val items = (0 until 30).map { index ->
            candidate("m$index", folder = "folder-${index % 3}", modified = now - index * 1_000L)
        }
        val engine = RecommendationEngine()
        val first = engine.rank(items, now, 15, 1234L)
        val second = engine.rank(items.shuffled(), now, 15, 1234L)
        assertEquals(first, second)
        assertTrue(first.map { it.mediaId }.distinct().size == first.size)
        assertTrue(first.any { it.reasons.contains("尚未播放") })
    }

    @Test
    fun `cold start interleaves folders and is stable`() {
        val items = listOf(
            candidate("a1", folder = "a", modified = now),
            candidate("a2", folder = "a", modified = now - 1),
            candidate("b1", folder = "b", modified = now - 2),
            candidate("c1", folder = "c", modified = now - 3),
        )
        val result = RecommendationEngine().rank(items, now, 4, 5L)
        assertEquals(listOf("a1", "b1", "c1", "a2"), result.map { it.mediaId })
    }

    @Test
    fun `old fast skip is weaker than a recent fast skip`() {
        val recent = candidate("recent", signals = RecommendationSignals(skippedCount = 1), events = listOf(
            RecommendationEvent("FAST_SKIP", now - 60_000L)
        ))
        val old = candidate("old", signals = RecommendationSignals(skippedCount = 1), events = listOf(
            RecommendationEvent("FAST_SKIP", now - 120 * 86_400_000L)
        ))
        val result = RecommendationEngine().rank(listOf(recent, old), now, 2, 2L)
        val recentScore = result.first { it.mediaId == "recent" }.components.skipPenalty
        val oldScore = result.first { it.mediaId == "old" }.components.skipPenalty
        assertTrue(recentScore > oldScore)
    }

    @Test
    fun `mmr avoids stacking an identical folder and title cluster`() {
        val clustered = (0 until 5).map { index ->
            candidate("cluster$index", folder = "same", title = "same scene $index", modified = now - index)
        }
        val distinct = listOf(
            candidate("other-folder", folder = "other", title = "different ocean", modified = now - 20),
            candidate("third-folder", folder = "third", title = "different forest", modified = now - 21),
        )
        val result = RecommendationEngine().rank(clustered + distinct, now, 3, 17L)
        assertTrue(result.take(3).map { it.mediaId }.count { it.startsWith("cluster") } < 3)
    }

    @Test
    fun `profile reset keeps explicit favorite but removes old implicit watch`() {
        val favorite = candidate("favorite", signals = RecommendationSignals(favorite = true))
        val watched = candidate(
            "watched",
            signals = RecommendationSignals(
                coveragePermille = 950,
                lastPlayedAtEpochMs = now - 10 * 86_400_000L,
            )
        )
        val engine = RecommendationEngine()
        val before = engine.rank(listOf(favorite, watched), now, 2, 3L)
        val after = engine.rank(
            listOf(favorite, watched), now, 2, 3L,
            RecommendationProfile(behaviorResetAtEpochMs = now - 1_000L)
        )
        assertTrue(before.first { it.mediaId == "watched" }.components.effectiveWatch >
            after.first { it.mediaId == "watched" }.components.effectiveWatch)
        assertTrue(after.first { it.mediaId == "favorite" }.reasons.contains("收藏过"))
    }

    @Test
    fun `profile store reset is monotonic and does not touch media data`() {
        val store = InMemoryRecommendationProfileStore()
        assertEquals(null, store.current().behaviorResetAtEpochMs)
        assertEquals(10L, store.resetBehavior(10L).behaviorResetAtEpochMs)
        assertEquals(20L, store.resetBehavior(20L).behaviorResetAtEpochMs)
        assertTrue(runCatching { store.resetBehavior(19L) }.isFailure)
    }

    @Test
    fun `two thousand one candidates rank within bounded work`() {
        val items = (0..2_000).map { index ->
            candidate(
                id = "bench-$index",
                folder = "folder-${index % 41}",
                title = "title ${index % 113} item $index",
                modified = now - index * 60_000L,
                signals = if (index % 17 == 0) RecommendationSignals(coveragePermille = 500) else RecommendationSignals(),
            )
        }
        val started = System.nanoTime()
        val result = RecommendationEngine().rank(items, now, 80, 99L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(80, result.size)
        assertTrue("ranking took ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    private fun candidate(
        id: String,
        folder: String = "folder",
        title: String = "Title $id",
        modified: Long? = null,
        signals: RecommendationSignals = RecommendationSignals(),
        events: List<RecommendationEvent> = emptyList(),
    ) = RecommendationCandidate(
        mediaId = id,
        sourceId = "source",
        folderPath = folder,
        title = title,
        durationMs = 120_000L,
        modifiedAtEpochMs = modified,
        width = 1920,
        height = 1080,
        signals = signals,
        events = events,
    )
}
