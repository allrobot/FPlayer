package io.github.fplayer.core.recommendation

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Deterministic, explainable single-user ranking. It has no clock, IO, or random state. */
class RecommendationEngine(
    private val settings: RecommendationSettings = RecommendationSettings(),
) {
    fun rank(
        candidates: List<RecommendationCandidate>,
        nowEpochMs: Long,
        limit: Int,
        generationSeed: Long,
        profile: RecommendationProfile = RecommendationProfile(),
    ): List<ScoredRecommendation> {
        require(nowEpochMs >= 0L)
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val unique = candidates.distinctBy { it.mediaId }
            .map { it.withProfile(profile) }
        val eligible = unique.filterNot { it.signals.disliked }
        if (eligible.isEmpty()) return emptyList()
        val hasHistory = eligible.any { hasBehavior(it) } || unique.any { hasBehavior(it) }
        val scored = if (hasHistory) score(eligible, nowEpochMs) else coldStart(eligible, generationSeed)
        val byId = eligible.associateBy { it.mediaId }
        val tokenCache = eligible.associate { it.mediaId to titleTokens(it.title) }
        val selected = mmr(scored, limit, byId, tokenCache)
        return applyExploration(selected, scored, limit, generationSeed)
            .take(limit)
    }

    private fun score(items: List<RecommendationCandidate>, now: Long): List<ScoredRecommendation> {
        val positive = items.filter { it.signals.liked || it.signals.favorite || hasMeaningfulWatch(it) }
        val itemTokens = items.associate { it.mediaId to titleTokens(it.title) }
        val folderCounts = positive.groupingBy { it.folderPath }.eachCount()
        val tokenCounts = positive.flatMap { itemTokens.getValue(it.mediaId).map { token -> token to 1 } }
            .groupingBy { it.first }.eachCount()
        val axisCounts = positive.flatMap { it.scriptAxes }.groupingBy { it }.eachCount()
        val maxFolder = max(1, folderCounts.values.maxOrNull() ?: 1)
        val maxToken = max(1, tokenCounts.values.maxOrNull() ?: 1)
        val maxAxis = max(1, axisCounts.values.maxOrNull() ?: 1)
        val recentItems = items.filter { it.signals.lastPlayedAtEpochMs?.let { played -> now - played in 0..2 * 86_400_000L } == true }
        return items.map { item ->
            val ageDecay = decay(item.signals.lastPlayedAtEpochMs, now)
            val explicit = settings.weights.explicitFeedback *
                ((if (item.signals.favorite) 2.8 else 0.0) + (if (item.signals.liked) 2.0 else 0.0))
            val folderAffinity = (folderCounts[item.folderPath] ?: 0).toDouble() / maxFolder
            val tokens = itemTokens.getValue(item.mediaId)
            val tokenAffinity = tokens.sumOf { (tokenCounts[it] ?: 0).toDouble() / maxToken }
                .let { if (tokens.isEmpty()) 0.0 else it / tokens.size }
            val axisAffinity = if (item.scriptAxes.isEmpty()) 0.0 else
                item.scriptAxes.sumOf { (axisCounts[it] ?: 0).toDouble() / maxAxis } / item.scriptAxes.size
            val content = settings.weights.contentAffinity *
                (0.45 * folderAffinity + 0.40 * tokenAffinity + 0.15 * axisAffinity)
            val durationCoverage = if (item.durationMs != null && item.durationMs > 0L) {
                item.signals.uniqueForegroundPlayedMs.toDouble() / item.durationMs.toDouble()
            } else 0.0
            val coverage = max(item.signals.coveragePermille.coerceIn(0, 1000) / 1000.0, durationCoverage.coerceIn(0.0, 1.0))
            val completionEvents = eventCount(item, "COMPLETION")
            val completion = 0.45 * ln(1.0 + max(item.signals.completionCount, completionEvents).coerceAtLeast(0).toDouble()) * ageDecay
            val watch = settings.weights.effectiveWatch * (0.65 * coverage + 0.25 * ageDecay) + completion
            val manualReplay = max(item.signals.manualReplayCount, eventCount(item, "MANUAL_REPLAY"))
            val foregroundLoops = max(item.signals.foregroundLoopCount, eventCount(item, "FOREGROUND_AUTO_LOOP"))
            val backgroundLoops = max(item.signals.backgroundLoopCount, eventCount(item, "BACKGROUND_AUTO_LOOP"))
            val replayRaw = ln(1.0 + manualReplay.coerceAtLeast(0).toDouble()) +
                0.25 * ln(1.0 + foregroundLoops.coerceAtLeast(0).toDouble()) +
                0.03 * ln(1.0 + backgroundLoops.coerceAtLeast(0).toDouble())
            val replay = settings.weights.replay * min(0.95, replayRaw)
            val novelty = settings.weights.novelty * if (coverage <= 0.0) 1.0 else 0.60 * (1.0 - ageDecay)
            val skipCount = max(item.signals.skippedCount, eventCount(item, "FAST_SKIP"))
            val skipEvents = item.events.filter { it.type == "FAST_SKIP" }
            val skipDecay = if (skipEvents.isEmpty()) {
                0.35 + 0.65 * ageDecay
            } else {
                (skipEvents.sumOf { decayAt(it.occurredAtEpochMs, now) } / skipEvents.size).coerceIn(0.0, 1.0)
            }
            val skip = settings.weights.skipPenalty * min(1.8, 0.35 * ln(1.0 + skipCount.coerceAtLeast(0).toDouble())) *
                (skipDecay / max(1.0, skipCount.toDouble())).coerceIn(0.0, 1.0)
            val sameRecentCluster = recentItems.any { recent ->
                recent.mediaId != item.mediaId && recent.folderPath == item.folderPath &&
                    titleJaccard(recent.title, item.title) >= 0.5
            }
            val recentRepeat = settings.weights.recentRepeatPenalty *
                if (sameRecentCluster || item.signals.lastPlayedAtEpochMs?.let { now - it < 2 * 86_400_000L } == true) 1.0 else 0.0
            val aggregate = item.signals.decayedPositive - item.signals.decayedNegative
            val relevance = explicit + content + watch + replay + novelty + aggregate - skip - recentRepeat
            val reasons = buildReasons(item, coverage, explicit, folderAffinity, novelty, recentRepeat)
            ScoredRecommendation(item.mediaId, relevance, ScoreComponents(
                explicit, content, watch, replay, novelty, skip, recentRepeat, relevance
            ), reasons)
        }.sortedWith(compareByDescending<ScoredRecommendation> { it.score }.thenBy { it.mediaId })
    }

    private fun coldStart(items: List<RecommendationCandidate>, seed: Long): List<ScoredRecommendation> {
        val buckets = items.groupBy { "${it.sourceId}\u0000${it.folderPath}" }
            .toSortedMap()
            .mapValues { (_, values) -> values.sortedWith(compareByDescending<RecommendationCandidate> { it.modifiedAtEpochMs ?: Long.MIN_VALUE }
                .thenBy { stableHash(seed, it.mediaId) }.thenBy { it.mediaId }) }
        val ordered = buildList {
            var index = 0
            while (true) {
                var added = false
                buckets.values.forEach { bucket -> if (index < bucket.size) { add(bucket[index]); added = true } }
                if (!added) break
                index++
            }
        }
        return ordered.mapIndexed { index, item ->
            val score = 0.20 - index * 1e-6
            ScoredRecommendation(item.mediaId, score, ScoreComponents(0.0, 0.0, 0.0, 0.0, 0.20, 0.0, 0.0, score),
                listOf("尚未播放", if (item.modifiedAtEpochMs != null) "最近新增" else "冷启动"))
        }
    }

    private fun mmr(
        scored: List<ScoredRecommendation>,
        limit: Int,
        candidates: Map<String, RecommendationCandidate>,
        tokenCache: Map<String, Set<String>>,
    ): List<ScoredRecommendation> {
        if (scored.size <= 1) return scored.take(limit)
        val selected = mutableListOf<ScoredRecommendation>()
        val maxScore = scored.maxOf { it.score }
        val minScore = scored.minOf { it.score }
        val range = max(1e-9, maxScore - minScore)
        while (selected.size < min(limit, scored.size)) {
            val next = scored.asSequence().filter { candidate -> selected.none { it.mediaId == candidate.mediaId } }
                .maxWithOrNull(compareBy<ScoredRecommendation> {
                    val relevance = (it.score - minScore) / range
                    val similarity = selected.maxOfOrNull { chosen ->
                        similarity(
                            candidates.getValue(it.mediaId),
                            candidates.getValue(chosen.mediaId),
                            tokenCache.getValue(it.mediaId),
                            tokenCache.getValue(chosen.mediaId),
                        )
                    } ?: 0.0
                    settings.mmrLambda * relevance - (1.0 - settings.mmrLambda) * similarity
                }.thenByDescending { it.score }.thenBy { it.mediaId }) ?: break
            selected += next
        }
        return selected
    }

    private fun applyExploration(
        selected: List<ScoredRecommendation>,
        all: List<ScoredRecommendation>,
        limit: Int,
        seed: Long,
    ): List<ScoredRecommendation> {
        if (selected.isEmpty()) return selected
        val unseen = all.filter { it.components.novelty > settings.weights.novelty * 0.9 }
            .sortedBy { stableHash(seed xor -7046029254386353131L, it.mediaId) }
        if (unseen.isEmpty()) return selected
        val slots = ceil(limit * settings.explorationFraction).toInt().coerceAtLeast(1)
        val interval = max(1, limit / slots)
        val result = selected.toMutableList()
        var explored = 0
        for (index in interval - 1 until result.size step interval) {
            if (explored >= slots) break
            val replacement = unseen.firstOrNull { candidate -> result.none { it.mediaId == candidate.mediaId } } ?: break
            result[index] = replacement
            explored++
        }
        return result
    }

    private fun similarity(
        a: RecommendationCandidate,
        b: RecommendationCandidate,
        leftTokens: Set<String>,
        rightTokens: Set<String>,
    ): Double {
        var score = 0.0
        if (a.folderPath == b.folderPath) score += 0.45
        if (leftTokens.isNotEmpty() || rightTokens.isNotEmpty()) {
            val union = (leftTokens + rightTokens).size
            if (union > 0) score += 0.35 * (leftTokens intersect rightTokens).size / union.toDouble()
        }
        if (durationBucket(a.durationMs) == durationBucket(b.durationMs)) score += 0.10
        if (a.scriptAxes.isNotEmpty() && a.scriptAxes == b.scriptAxes) score += 0.10
        return score.coerceIn(0.0, 1.0)
    }

    private fun durationBucket(durationMs: Long?): Int = when {
        durationMs == null -> -1
        durationMs < 60_000L -> 0
        durationMs < 300_000L -> 1
        durationMs < 1_200_000L -> 2
        else -> 3
    }

    private fun titleJaccard(left: String, right: String): Double {
        val a = titleTokens(left)
        val b = titleTokens(right)
        val union = (a + b).size
        return if (union == 0) 0.0 else (a intersect b).size.toDouble() / union
    }

    private fun eventCount(item: RecommendationCandidate, type: String): Int =
        item.events.count { it.type == type }

    private fun decayAt(timestamp: Long, now: Long): Double {
        if (timestamp > now) return 0.0
        return decayFactor((now - timestamp).toDouble() / 86_400_000.0, settings.halfLifeDays)
    }

    private fun buildReasons(item: RecommendationCandidate, coverage: Double, explicit: Double, folderAffinity: Double,
                             novelty: Double, recentRepeat: Double): List<String> = buildList {
        if (item.signals.favorite) add("收藏过") else if (item.signals.liked) add("喜欢过")
        if (folderAffinity >= 0.5) add("常看此文件夹")
        if (coverage >= settings.completionThreshold) add("看完过")
        if (novelty > settings.weights.novelty * 0.9) add("尚未播放")
        if (recentRepeat > 0.0) add("最近重复降低")
        if (isEmpty()) add("综合偏好")
    }

    private fun hasBehavior(item: RecommendationCandidate): Boolean =
        item.signals != RecommendationSignals() || item.events.isNotEmpty()

    private fun hasMeaningfulWatch(item: RecommendationCandidate): Boolean =
        item.signals.coveragePermille > 0 || item.signals.uniqueForegroundPlayedMs > 0L

    private fun decay(lastPlayed: Long?, now: Long): Double {
        if (lastPlayed == null || lastPlayed > now) return 0.0
        val ageDays = (now - lastPlayed).toDouble() / 86_400_000.0
        return decayFactor(ageDays, settings.halfLifeDays)
    }

    private fun titleTokens(title: String): Set<String> = title.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun stableHash(seed: Long, value: String): Long {
        var hash = seed xor -0x61c8864680b583ebL
        value.forEach { char ->
            hash = (hash xor char.code.toLong()) * -0x40A7B892E31B1A47L
            hash = hash xor (hash ushr 29)
        }
        return hash xor (hash ushr 32)
    }

    companion object {
        fun decayFactor(ageDays: Double, halfLifeDays: Double): Double {
            require(ageDays >= 0.0)
            require(halfLifeDays > 0.0)
            return exp(-ln(2.0) * ageDays / halfLifeDays)
        }
    }

    private fun RecommendationCandidate.withProfile(profile: RecommendationProfile): RecommendationCandidate {
        val resetAt = profile.behaviorResetAtEpochMs ?: return this
        val state = signals
        val keepState = state.lastPlayedAtEpochMs?.let { it >= resetAt } == true
        val keepAggregate = state.aggregateUpdatedAtEpochMs?.let { it >= resetAt } == true
        return copy(
            signals = state.copy(
                uniqueForegroundPlayedMs = if (keepState) state.uniqueForegroundPlayedMs else 0L,
                coveragePermille = if (keepState) state.coveragePermille else 0,
                completionCount = if (keepState) state.completionCount else 0,
                manualReplayCount = if (keepState) state.manualReplayCount else 0,
                foregroundLoopCount = if (keepState) state.foregroundLoopCount else 0,
                backgroundLoopCount = if (keepState) state.backgroundLoopCount else 0,
                skippedCount = if (keepState) state.skippedCount else 0,
                lastPlayedAtEpochMs = if (keepState) state.lastPlayedAtEpochMs else null,
                decayedPositive = if (keepAggregate) state.decayedPositive else 0.0,
                decayedNegative = if (keepAggregate) state.decayedNegative else 0.0,
                aggregateUpdatedAtEpochMs = if (keepAggregate) state.aggregateUpdatedAtEpochMs else null,
            ),
            events = events.filter { it.occurredAtEpochMs >= resetAt },
        )
    }
}
