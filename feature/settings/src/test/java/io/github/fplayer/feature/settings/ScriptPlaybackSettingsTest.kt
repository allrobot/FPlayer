package io.github.fplayer.feature.settings

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.script.LatencyEstimate
import io.github.fplayer.core.script.LatencyMeasurementState
import io.github.fplayer.core.script.ScriptAxisOutputRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPlaybackSettingsTest {
    @Test
    fun `defaults enable automatic latency and use unrestricted axis output`() {
        val state = ScriptPlaybackSettingsState()

        assertTrue(state.latency.automaticEnabled)
        assertEquals(0L, state.latency.manualOffsetMs)
        assertEquals(ScriptAxisOutputRange(0, 100), state.outputLimits.rangeFor(AxisId("L0")))
        assertEquals(ScriptAxisOutputRange(0, 100), state.outputLimits.rangeFor(AxisId("R2")))
        assertEquals(AxisId("L0"), state.selectedManualAxis)
        assertEquals(50, state.manualPosition)
    }

    @Test
    fun `complete signed offsets are accepted and constrained to the UI range`() {
        val negative = reduce(ScriptPlaybackSettingsState(), ScriptPlaybackSettingsAction.SetManualOffsetText("-125"))
        val positive = reduce(negative, ScriptPlaybackSettingsAction.SetManualOffsetText("+240"))
        val low = reduce(positive, ScriptPlaybackSettingsAction.SetManualOffsetText("-10001"))
        val high = reduce(low, ScriptPlaybackSettingsAction.SetManualOffsetText("10001"))

        assertEquals(-125L, negative.latency.manualOffsetMs)
        assertEquals(240L, positive.latency.manualOffsetMs)
        assertEquals(-10_000L, low.latency.manualOffsetMs)
        assertEquals(10_000L, high.latency.manualOffsetMs)
    }

    @Test
    fun `incomplete fractional non numeric and overflowing offsets are ignored`() {
        val initial = ScriptPlaybackSettingsState().copy(
            latency = ScriptPlaybackSettingsState().latency.copy(manualOffsetMs = 75L),
        )

        listOf("-", "1.5", "abc", " 25", "9223372036854775808").forEach { text ->
            assertEquals(
                75L,
                reduce(initial, ScriptPlaybackSettingsAction.SetManualOffsetText(text)).latency.manualOffsetMs,
            )
        }
    }

    @Test
    fun `axis endpoints are clamped reordered and do not change the selected axis`() {
        val initial = reduce(
            ScriptPlaybackSettingsState(),
            ScriptPlaybackSettingsAction.SetManualAxis(AxisId("R1")),
        )
        val configured = reduce(
            initial,
            ScriptPlaybackSettingsAction.SetAxisRange(AxisId("L0"), minimum = 120, maximum = -5),
        )

        assertEquals(ScriptAxisOutputRange(0, 100), configured.outputLimits.rangeFor(AxisId("L0")))
        assertEquals(AxisId("R1"), configured.selectedManualAxis)

        val inverted = reduce(
            configured,
            ScriptPlaybackSettingsAction.SetAxisRange(AxisId("R1"), minimum = 80, maximum = 20),
        )
        assertEquals(ScriptAxisOutputRange(20, 80), inverted.outputLimits.rangeFor(AxisId("R1")))
    }

    @Test
    fun `manual position follows the selected axis range before target creation`() {
        val axis = AxisId("R0")
        val ranged = reduce(
            reduce(ScriptPlaybackSettingsState(), ScriptPlaybackSettingsAction.SetManualAxis(axis)),
            ScriptPlaybackSettingsAction.SetAxisRange(axis, minimum = 30, maximum = 60),
        )
        val low = reduce(ranged, ScriptPlaybackSettingsAction.SetManualPosition(5))
        val high = reduce(low, ScriptPlaybackSettingsAction.SetManualPosition(95))

        assertEquals(30, low.manualPosition)
        assertEquals(60, high.manualPosition)
        assertEquals(axis, high.manualAxisTarget().axis)
        assertEquals(60, high.manualAxisTarget().position)
        assertEquals(250L, high.manualAxisTarget().durationMs)
    }

    @Test
    fun `changing manual axis clamps the existing position to its range`() {
        val r0 = AxisId("R0")
        val ranged = reduce(
            ScriptPlaybackSettingsState(manualPosition = 90),
            ScriptPlaybackSettingsAction.SetAxisRange(r0, minimum = 10, maximum = 40),
        )

        val selected = reduce(ranged, ScriptPlaybackSettingsAction.SetManualAxis(r0))

        assertEquals(r0, selected.selectedManualAxis)
        assertEquals(40, selected.manualPosition)
    }

    @Test
    fun `disabled automatic latency keeps measurements visible but excludes them from effective offset`() {
        val estimate = LatencyEstimate(
            state = LatencyMeasurementState.MEASURED,
            automaticOffsetMs = -50L,
            medianRoundTripMs = 100L,
            sampleCount = 3,
            measuredAtMonotonicMs = 9_000L,
        )
        val measured = ScriptPlaybackSettingsState(
            latency = ScriptPlaybackSettingsState().latency.copy(manualOffsetMs = 35L),
            estimate = estimate,
        )
        val disabled = reduce(measured, ScriptPlaybackSettingsAction.SetAutomaticLatency(false))

        assertFalse(disabled.latency.automaticEnabled)
        assertEquals(estimate, disabled.estimate)
        assertEquals(LatencyMeasurementState.MEASURED, disabled.diagnostics().state)
        assertEquals(3, disabled.diagnostics().sampleCount)
        assertEquals(100L, disabled.diagnostics().medianRoundTripMs)
        assertEquals(35L, disabled.diagnostics().effectiveOffsetMs)
    }

    @Test
    fun `reset clears only latency measurement diagnostics`() {
        val state = ScriptPlaybackSettingsState(
            latency = ScriptPlaybackSettingsState().latency.copy(manualOffsetMs = -20L),
            estimate = LatencyEstimate(LatencyMeasurementState.EXPIRED, 0L, 80L, 4, 200L),
        )

        val reset = reduce(state, ScriptPlaybackSettingsAction.ResetLatency)

        assertEquals(-20L, reset.latency.manualOffsetMs)
        assertEquals(LatencyMeasurementState.UNMEASURED, reset.estimate.state)
        assertEquals(0, reset.estimate.sampleCount)
        assertNull(reset.estimate.medianRoundTripMs)
        assertNull(reset.estimate.measuredAtMonotonicMs)
    }
}
