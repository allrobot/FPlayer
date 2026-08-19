package io.github.fplayer.core.recommendation

import io.github.fplayer.core.index.db.FPlayerIndexDatabase
/** Converts the current Room snapshot to scorer input. Callers own the IO dispatcher. */
class RecommendationSnapshotReader(private val database: FPlayerIndexDatabase) {
    fun read(): List<RecommendationCandidate> {
        val dao = database.indexDao()
        val sources = dao.sources().associateBy { it.id }
        val playback = dao.playbackSnapshot().associateBy { it.mediaId }
        val allEvents = dao.interactionSnapshot().groupBy { it.mediaId }
        val aggregates = dao.recommendationSnapshot().associateBy { it.mediaId }
        return sources.values.flatMap { source ->
            val folders = dao.currentFolders(source.id).associateBy { it.id }
            val scripts = dao.currentScriptSnapshot(source.id).groupBy { it.mediaId }
            dao.currentMediaSnapshot(source.id).map { media ->
                val state = playback[media.id]
                val aggregate = aggregates[media.id]
                RecommendationCandidate(
                    mediaId = media.id,
                    sourceId = media.sourceId,
                    folderPath = folders[media.folderId]?.normalizedPath ?: "",
                    title = media.normalizedTitle,
                    durationMs = media.durationMs,
                    modifiedAtEpochMs = media.modifiedAtEpochMs,
                    width = media.width,
                    height = media.height,
                    scriptAxes = scripts[media.id].orEmpty().mapNotNull { it.axis }.toSet(),
                    signals = RecommendationSignals(
                        uniqueForegroundPlayedMs = state?.uniqueForegroundPlayedMs ?: aggregate?.totalForegroundPlayedMs ?: 0L,
                        coveragePermille = state?.coveragePermille ?: 0,
                        completionCount = state?.completionCount ?: aggregate?.completionCount ?: 0,
                        manualReplayCount = state?.manualReplayCount ?: aggregate?.manualReplayCount ?: 0,
                        foregroundLoopCount = state?.foregroundLoopCount ?: aggregate?.foregroundLoopCount ?: 0,
                        backgroundLoopCount = state?.backgroundLoopCount ?: aggregate?.backgroundLoopCount ?: 0,
                        skippedCount = state?.skippedCount ?: aggregate?.skippedCount ?: 0,
                        lastPlayedAtEpochMs = state?.lastPlayedAtEpochMs,
                        liked = state?.liked ?: false,
                        favorite = state?.favorite ?: false,
                        disliked = state?.disliked ?: false,
                        decayedPositive = aggregate?.decayedPositive ?: 0.0,
                        decayedNegative = aggregate?.decayedNegative ?: 0.0,
                        aggregateUpdatedAtEpochMs = aggregate?.updatedAtEpochMs,
                    ),
                    events = allEvents[media.id].orEmpty().map { RecommendationEvent(it.type, it.occurredAtEpochMs) },
                )
            }
        }
    }
}
