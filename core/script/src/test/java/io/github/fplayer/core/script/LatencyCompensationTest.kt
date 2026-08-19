package io.github.fplayer.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LatencyCompensationTest {
    @Test
    fun `median RTT becomes bounded negative half RTT automatic offset`() {
        val estimator = LatencyEstimator(LatencyCompensationConfig(maximumAutomaticAdvanceMs = 250))
        listOf(80L, 100L, 120L).forEach { estimator.recordSample(LatencySample(0L, it)) }
        val result = estimator.estimate(1_000L)
        assertEquals(LatencyMeasurementState.MEASURED, result.state)
        assertEquals(100L, result.medianRoundTripMs)
        assertEquals(-50L, result.automaticOffsetMs)
    }

    @Test
    fun `invalid and expired samples never enable automatic compensation`() {
        val estimator = LatencyEstimator(LatencyCompensationConfig(expiryMs = 100L))
        assertThrows(IllegalArgumentException::class.java) {
            estimator.recordSample(LatencySample(10L, 9L))
        }
        assertEquals(LatencyMeasurementState.UNMEASURED, estimator.estimate(0L).state)
        estimator.recordSample(LatencySample(0L, 20L))
        assertEquals(LatencyMeasurementState.EXPIRED, estimator.estimate(121L).state)
        assertEquals(0L, estimator.estimate(121L).automaticOffsetMs)
    }

    @Test
    fun `manual signed offset is added to automatic value`() {
        val config = LatencyCompensationConfig(automaticEnabled = true, manualOffsetMs = 35L)
        val estimate = LatencyEstimate(LatencyMeasurementState.MEASURED, -50L, 100L, 3, 10L)
        assertEquals(-15L, effectiveOffsetMs(config, estimate))
        assertEquals(35L, effectiveOffsetMs(config.copy(automaticEnabled = false), estimate))
    }

    @Test
    fun `sample window trims oldest and lower middle is used for even median`() {
        val estimator = LatencyEstimator(LatencyCompensationConfig(sampleWindowSize = 3))
        listOf(10L, 40L, 30L, 20L).forEach { estimator.recordSample(LatencySample(0L, it)) }
        val result = estimator.estimate(1_000L)
        assertEquals(30L, result.medianRoundTripMs)
        assertEquals(3, result.sampleCount)
    }

    @Test
    fun `zero and single RTT are measured`() {
        val estimator = LatencyEstimator()
        estimator.recordSample(LatencySample(5L, 5L))
        val result = estimator.estimate(5L)
        assertEquals(0L, result.medianRoundTripMs)
        assertEquals(0L, result.automaticOffsetMs)
        assertEquals(1, result.sampleCount)
    }

    @Test
    fun `even median uses lower middle ordering`() {
        val estimator = LatencyEstimator(LatencyCompensationConfig(sampleWindowSize = 4))
        listOf(40L, 10L, 30L, 20L).forEach { estimator.recordSample(LatencySample(0L, it)) }
        assertEquals(20L, estimator.estimate(1_000L).medianRoundTripMs)
    }

    @Test
    fun `automatic advance clamps to configured maximum`() {
        val estimator = LatencyEstimator(LatencyCompensationConfig(maximumAutomaticAdvanceMs = 25L))
        estimator.recordSample(LatencySample(0L, 1000L))
        assertEquals(-25L, estimator.estimate(1000L).automaticOffsetMs)
    }

    @Test
    fun `unmeasured estimate is nullable and reset clears samples`() {
        val estimator = LatencyEstimator()
        val initial = estimator.estimate(0L)
        assertEquals(LatencyMeasurementState.UNMEASURED, initial.state)
        assertEquals(null, initial.medianRoundTripMs)
        assertEquals(null, initial.measuredAtMonotonicMs)
        assertEquals(LatencyMeasurementState.MEASURED, estimator.recordSample(LatencySample(0L, 20L)).state)
        assertEquals(LatencyMeasurementState.UNMEASURED, estimator.reset().state)
    }

    @Test
    fun `config defaults and bounds match stable contract`() {
        val config = LatencyCompensationConfig()
        assertEquals(true, config.automaticEnabled)
        assertEquals(0L, config.manualOffsetMs)
        assertEquals(250L, config.maximumAutomaticAdvanceMs)
        assertEquals(9, config.sampleWindowSize)
        assertEquals(10_000L, config.expiryMs)
        assertThrows(IllegalArgumentException::class.java) { LatencyCompensationConfig(maximumAutomaticAdvanceMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { LatencyCompensationConfig(sampleWindowSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { LatencyCompensationConfig(expiryMs = 0L) }
    }

    @Test
    fun `effective offset saturates signed overflow`() {
        val negativeAutomatic = LatencyEstimate(LatencyMeasurementState.MEASURED, -50L, 100L, 1, 0L)
        val positiveAutomatic = LatencyEstimate(LatencyMeasurementState.MEASURED, 50L, 100L, 1, 0L)
        assertEquals(Long.MIN_VALUE, effectiveOffsetMs(LatencyCompensationConfig(manualOffsetMs = Long.MIN_VALUE), negativeAutomatic))
        assertEquals(Long.MAX_VALUE, effectiveOffsetMs(LatencyCompensationConfig(manualOffsetMs = Long.MAX_VALUE), positiveAutomatic))
    }
}
