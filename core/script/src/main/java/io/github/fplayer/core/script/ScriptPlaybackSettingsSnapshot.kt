package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId

/** Immutable playback-facing projection of the script controls. */
data class ScriptPlaybackSettingsSnapshot(
    val latency: LatencyCompensationConfig = LatencyCompensationConfig(),
    val estimate: LatencyEstimate = LatencyEstimate(
        state = LatencyMeasurementState.UNMEASURED,
        automaticOffsetMs = 0L,
        medianRoundTripMs = null,
        sampleCount = 0,
        measuredAtMonotonicMs = null,
    ),
    val outputLimits: ScriptOutputLimits = ScriptOutputLimits(emptyMap()),
    val selectedManualAxis: AxisId = AxisId("L0"),
    val manualPosition: Int = 50,
) {
    init {
        require(manualPosition in 0..100) { "manualPosition must be within 0..100" }
    }
}
