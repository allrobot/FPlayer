package io.github.fplayer.core.recommendation

/** A local, already-normalized signal read from the index. */
data class RecommendationSignals(
    val uniqueForegroundPlayedMs: Long = 0L,
    val coveragePermille: Int = 0,
    val completionCount: Int = 0,
    val manualReplayCount: Int = 0,
    val foregroundLoopCount: Int = 0,
    val backgroundLoopCount: Int = 0,
    val skippedCount: Int = 0,
    val lastPlayedAtEpochMs: Long? = null,
    val liked: Boolean = false,
    val favorite: Boolean = false,
    val disliked: Boolean = false,
    val decayedPositive: Double = 0.0,
    val decayedNegative: Double = 0.0,
    val aggregateUpdatedAtEpochMs: Long? = null,
)

data class RecommendationEvent(
    val type: String,
    val occurredAtEpochMs: Long,
)

/** Metadata needed by the scorer. No locator or source credentials are retained here. */
data class RecommendationCandidate(
    val mediaId: String,
    val sourceId: String,
    val folderPath: String,
    val title: String,
    val durationMs: Long?,
    val modifiedAtEpochMs: Long?,
    val width: Int?,
    val height: Int?,
    val scriptAxes: Set<String> = emptySet(),
    val signals: RecommendationSignals = RecommendationSignals(),
    val events: List<RecommendationEvent> = emptyList(),
)

data class RecommendationWeights(
    val explicitFeedback: Double = 1.0,
    val contentAffinity: Double = 0.9,
    val effectiveWatch: Double = 1.1,
    val replay: Double = 1.0,
    val novelty: Double = 0.8,
    val skipPenalty: Double = 0.75,
    val recentRepeatPenalty: Double = 0.55,
)

data class RecommendationSettings(
    val halfLifeDays: Double = 21.0,
    val completionThreshold: Double = 0.90,
    val mmrLambda: Double = 0.72,
    val explorationFraction: Double = 0.08,
    val weights: RecommendationWeights = RecommendationWeights(),
    val algorithmVersion: String = "t29-v1",
) {
    init {
        require(halfLifeDays > 0.0)
        require(completionThreshold in 0.5..1.0)
        require(mmrLambda in 0.5..0.9)
        require(explorationFraction in 0.05..0.10)
    }
}

data class RecommendationProfile(
    val behaviorResetAtEpochMs: Long? = null,
)

data class ScoreComponents(
    val explicitFeedback: Double,
    val contentAffinity: Double,
    val effectiveWatch: Double,
    val replayBoost: Double,
    val novelty: Double,
    val skipPenalty: Double,
    val recentRepeatPenalty: Double,
    val relevance: Double,
)

data class ScoredRecommendation(
    val mediaId: String,
    val score: Double,
    val components: ScoreComponents,
    val reasons: List<String>,
)
