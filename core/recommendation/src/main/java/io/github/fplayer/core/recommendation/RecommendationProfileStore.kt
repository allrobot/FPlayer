package io.github.fplayer.core.recommendation

/** Small persistence boundary for the user action "reset recommendation profile". */
interface RecommendationProfileStore {
    fun current(): RecommendationProfile
    fun resetBehavior(atEpochMs: Long): RecommendationProfile
}

/** In-memory implementation used by previews/tests; a settings repository can persist the timestamp. */
class InMemoryRecommendationProfileStore : RecommendationProfileStore {
    private var profile = RecommendationProfile()

    override fun current(): RecommendationProfile = profile

    override fun resetBehavior(atEpochMs: Long): RecommendationProfile {
        require(atEpochMs >= 0L)
        val previous = profile.behaviorResetAtEpochMs
        require(previous == null || atEpochMs >= previous) { "PROFILE_RESET_NOT_MONOTONIC" }
        profile = RecommendationProfile(atEpochMs)
        return profile
    }
}
