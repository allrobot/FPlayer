package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.ScriptAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptHeatmapDownsamplerTest {
    @Test
    fun preservesAllPointsAndExactWindowBoundariesWhenUnderBudget() {
        val track = track(actions = listOf(action(0, 0), action(100, 100), action(200, 0)))

        val result = ScriptHeatmapDownsampler.downsample(track, HeatmapWindow(50, 150), maxSamples = 8)

        assertEquals(
            listOf(HeatmapSample(50, 50), HeatmapSample(100, 100), HeatmapSample(150, 50)),
            result.samples,
        )
        assertEquals(1, result.sourceActionCount)
    }

    @Test
    fun longScriptOutputIsBoundedAndPreservesNarrowExtrema() {
        val actions = (0..100_000).map { index ->
            val position = when (index) {
                12_345 -> 100
                54_321 -> 0
                else -> 50
            }
            action(index.toLong(), position)
        }
        val result = ScriptHeatmapDownsampler.downsample(
            track(actions = actions),
            HeatmapWindow(0, 100_000),
            maxSamples = 256,
        )

        assertTrue(result.samples.size <= 256)
        assertTrue(HeatmapSample(12_345, 100) in result.samples)
        assertTrue(HeatmapSample(54_321, 0) in result.samples)
        assertEquals(100_001, result.sourceActionCount)
    }

    @Test
    fun zoomedWindowRecoversLocalDetailAndTimeMapping() {
        val track = track(
            actions = (0..10_000).map { index ->
                action(index.toLong() * 10, if (index % 7 == 0) 90 else 10)
            },
        )
        val window = HeatmapWindow(40_000, 41_000)
        val result = ScriptHeatmapDownsampler.downsample(track, window, maxSamples = 64)

        assertEquals(window.startMs, result.samples.first().atMs)
        assertEquals(window.endMs, result.samples.last().atMs)
        assertTrue(result.samples.all { it.atMs in window.startMs..window.endMs })
        assertTrue(result.samples.any { it.position == 90 })
        assertTrue(result.samples.any { it.position == 10 })
    }

    @Test
    fun adjacentDraggedWindowsShareExactBoundarySample() {
        val track = track(actions = listOf(action(0, 0), action(1_000, 100), action(2_000, 0)))
        val first = ScriptHeatmapDownsampler.downsample(track, HeatmapWindow(250, 1_250), 16)
        val second = ScriptHeatmapDownsampler.downsample(track, HeatmapWindow(1_250, 1_750), 16)

        assertEquals(first.samples.last(), second.samples.first())
        assertEquals(HeatmapSample(1_250, 75), first.samples.last())
    }

    @Test
    fun multiAxisOutputIsSortedAndBoundedPerAxis() {
        val l0 = track("L0", (0..1_000).map { action(it.toLong(), it % 101) })
        val r0 = track("R0", (0..1_000).map { action(it.toLong(), 100 - (it % 101)) })
        val bundle = ScriptBundle(linkedMapOf(AxisId("R0") to r0, AxisId("L0") to l0))

        val result = ScriptHeatmapDownsampler.downsample(bundle, HeatmapWindow(0, 1_000), 32)

        assertEquals(listOf("L0", "R0"), result.axes.keys.map { it.value })
        assertTrue(result.axes.values.all { it.samples.size <= 32 })
    }

    @Test
    fun sparseWindowStillHasInterpolatedStartAndEnd() {
        val track = track(actions = listOf(action(0, 20), action(10_000, 80)))

        val result = ScriptHeatmapDownsampler.downsample(track, HeatmapWindow(4_000, 6_000), 8)

        assertEquals(listOf(HeatmapSample(4_000, 44), HeatmapSample(6_000, 56)), result.samples)
        assertEquals(0, result.sourceActionCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBudgetTooSmallToPreserveBucketExtrema() {
        ScriptHeatmapDownsampler.downsample(track(actions = listOf(action(0, 0))), HeatmapWindow(0, 1), 3)
    }

    private fun track(
        axis: String = "L0",
        actions: List<ScriptAction>,
    ) = ScriptTrack(AxisId(axis), actions)

    private fun action(atMs: Long, position: Int) = ScriptAction(atMs, position)
}
