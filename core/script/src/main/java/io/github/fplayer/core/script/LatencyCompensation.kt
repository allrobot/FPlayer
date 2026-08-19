package io.github.fplayer.core.script

data class LatencyCompensationConfig(
    val automaticEnabled: Boolean = true,
    val manualOffsetMs: Long = 0L,
    val maximumAutomaticAdvanceMs: Long = 250L,
    val sampleWindowSize: Int = 9,
    val expiryMs: Long = 10_000L,
) {
    init {
        require(maximumAutomaticAdvanceMs > 0) { "maximumAutomaticAdvanceMs must be positive" }
        require(sampleWindowSize > 0) { "sampleWindowSize must be positive" }
        require(expiryMs > 0) { "expiryMs must be positive" }
    }
}

data class LatencySample(
    val sentAtMonotonicMs: Long,
    val receivedAtMonotonicMs: Long,
) {
    val roundTripMs: Long
        get() = receivedAtMonotonicMs - sentAtMonotonicMs
}

enum class LatencyMeasurementState { UNMEASURED, MEASURING, MEASURED, EXPIRED }

data class LatencyEstimate(
    val state: LatencyMeasurementState,
    val automaticOffsetMs: Long,
    val medianRoundTripMs: Long?,
    val sampleCount: Int,
    val measuredAtMonotonicMs: Long?,
)

class LatencyEstimator(private val config: LatencyCompensationConfig = LatencyCompensationConfig()) {
    private val samples = ArrayDeque<LatencySample>()

    fun recordSample(sample: LatencySample): LatencyEstimate {
        require(sample.sentAtMonotonicMs >= 0) { "sentAtMonotonicMs must be non-negative" }
        require(sample.receivedAtMonotonicMs >= sample.sentAtMonotonicMs) {
            "receivedAtMonotonicMs must be at or after sentAtMonotonicMs"
        }
        val roundTripMs = try {
            Math.subtractExact(sample.receivedAtMonotonicMs, sample.sentAtMonotonicMs)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("roundTripMs exceeds configured maximum")
        }
        samples.addLast(sample)
        while (samples.size > config.sampleWindowSize) samples.removeFirst()
        return estimate(sample.receivedAtMonotonicMs)
    }

    fun estimate(nowMonotonicMs: Long): LatencyEstimate {
        require(nowMonotonicMs >= 0) { "nowMonotonicMs must be non-negative" }
        if (samples.isEmpty()) return unmeasured()
        val measuredAt = samples.maxOf { it.receivedAtMonotonicMs }
        val sorted = samples.map { it.roundTripMs }.sorted()
        val median = sorted[(sorted.size - 1) / 2]
        if (nowMonotonicMs - measuredAt > config.expiryMs) {
            return LatencyEstimate(LatencyMeasurementState.EXPIRED, 0L, median, sorted.size, measuredAt)
        }
        val automatic = (-median / 2L).coerceIn(-config.maximumAutomaticAdvanceMs, 0L)
        return LatencyEstimate(LatencyMeasurementState.MEASURED, automatic, median, sorted.size, measuredAt)
    }

    fun reset(): LatencyEstimate {
        samples.clear()
        return unmeasured()
    }

    private fun unmeasured() = LatencyEstimate(LatencyMeasurementState.UNMEASURED, 0L, null, 0, null)

}

fun effectiveOffsetMs(config: LatencyCompensationConfig, estimate: LatencyEstimate): Long {
    val automatic = if (config.automaticEnabled && estimate.state == LatencyMeasurementState.MEASURED) {
        estimate.automaticOffsetMs
    } else {
        0L
    }
    return try {
        Math.addExact(config.manualOffsetMs, automatic)
    } catch (_: ArithmeticException) {
        if (config.manualOffsetMs >= 0L) Long.MAX_VALUE else Long.MIN_VALUE
    }
}
