package io.github.fplayer.feature.feed

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.ScriptAction
import io.github.fplayer.core.script.HeatmapWindow
import io.github.fplayer.core.script.ScriptBundle
import io.github.fplayer.core.script.ScriptHeatmapDownsampler
import io.github.fplayer.core.script.ScriptTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressHeatmapRendererTest {
    private fun heatmap() = ScriptHeatmapDownsampler.downsample(
        ScriptBundle(
            mapOf(
                AxisId("R0") to ScriptTrack(
                    AxisId("R0"),
                    listOf(ScriptAction(0, 20), ScriptAction(500, 70), ScriptAction(1_000, 40)),
                ),
                AxisId("L0") to ScriptTrack(
                    AxisId("L0"),
                    listOf(ScriptAction(0, 10), ScriptAction(1_000, 90)),
                ),
            ),
        ),
        HeatmapWindow(0, 1_000),
        maxSamplesPerAxis = 8,
    )

    @Test
    fun mapsAxesInStableOrderAndClipsToWindow() {
        val rendered = ProgressHeatmapRenderer.build(
            heatmap(),
            ProgressInteractionStateMachine(1_000, 500, ProgressInteractionMode.HEATMAP).snapshot(),
        )
        assertEquals(ProgressInteractionMode.HEATMAP, rendered.mode)
        assertEquals(listOf("L0", "R0"), rendered.traces.map(HeatmapRenderTrace::axis))
        rendered.traces.forEach { trace ->
            assertTrue(trace.points.all { it.x in 0f..1f && it.y in 0f..1f })
        }
        assertTrue(rendered.traces.map { it.lineStyle }.distinct().size > 1)
    }

    @Test
    fun missingHeatmapFallsBackToBarMode() {
        val rendered = ProgressHeatmapRenderer.build(
            null,
            ProgressInteractionStateMachine(2_000, 500).snapshot(),
        )
        assertEquals(ProgressInteractionMode.BAR, rendered.mode)
        assertEquals(0.25f, rendered.progress, 0.001f)
        assertTrue(rendered.traces.isEmpty())
    }

    @Test
    fun progressPointerCommitsOnlyOnPointerUpAndBlocksFeed() {
        val progress = ProgressInteractionStateMachine(1_000)
        progress.onPointerDown()
        progress.onPointerMove(0.6f)
        val dragging = progress.snapshot()
        assertTrue(dragging.feedPagingBlocked)
        assertEquals(null, dragging.submittedPositionMs)
        val token = progress.onPointerUp()
        assertEquals(1L, token)
        assertEquals(600L, progress.snapshot().submittedPositionMs)
        assertTrue(progress.snapshot().feedPagingBlocked)
    }

    @Test
    fun overlayLayoutKeepsStableTouchGeometryAcrossModes() {
        val layout = PlaybackOverlayLayout()
        assertTrue(layout.controlTouchTargetDp >= 48)
        assertEquals(11, layout.visibleControls(PlaybackOverlayMode.NORMAL))
        assertEquals(4, layout.visibleControls(PlaybackOverlayMode.CLEAN))
    }
}
