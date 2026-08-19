package io.github.fplayer.feature.feed

import io.github.fplayer.core.script.AxisHeatmap
import io.github.fplayer.core.script.ScriptHeatmap

enum class HeatmapLineStyle { SOLID, DASHED, DOTTED }

data class HeatmapRenderPoint(
    val atMs: Long,
    val position: Int,
    val x: Float,
    val y: Float,
)

data class HeatmapRenderTrace(
    val axis: String,
    val points: List<HeatmapRenderPoint>,
    val luminance: Float,
    val lineStyle: HeatmapLineStyle,
)

data class ProgressHeatmapRenderModel(
    val mode: ProgressInteractionMode,
    val progress: Float,
    val traces: List<HeatmapRenderTrace>,
)

/** Pure renderer model. It never changes script actions, limits, scheduling, or device output. */
object ProgressHeatmapRenderer {
    fun build(
        heatmap: ScriptHeatmap?,
        snapshot: ProgressInteractionSnapshot,
    ): ProgressHeatmapRenderModel {
        if (heatmap == null || heatmap.axes.isEmpty()) {
            return ProgressHeatmapRenderModel(
                mode = ProgressInteractionMode.BAR,
                progress = snapshot.progress.coerceIn(0f, 1f),
                traces = emptyList(),
            )
        }
        val axes = heatmap.axes.toSortedMap(compareBy { it.value }).values.toList()
        val denominator = (heatmap.window.endMs - heatmap.window.startMs).toFloat()
        val traces = axes.mapIndexed { index, axis ->
            HeatmapRenderTrace(
                axis = axis.axis.value,
                points = axis.samples
                    .asSequence()
                    .filter { it.atMs in heatmap.window.startMs..heatmap.window.endMs }
                    .map { sample ->
                        HeatmapRenderPoint(
                            atMs = sample.atMs,
                            position = sample.position,
                            x = ((sample.atMs - heatmap.window.startMs) / denominator).coerceIn(0f, 1f),
                            y = (1f - sample.position / 100f).coerceIn(0f, 1f),
                        )
                    }
                    .toList(),
                luminance = (0.58f + (index % 3) * 0.14f).coerceIn(0f, 1f),
                lineStyle = HeatmapLineStyle.entries[index % HeatmapLineStyle.entries.size],
            )
        }
        return ProgressHeatmapRenderModel(
            mode = ProgressInteractionMode.HEATMAP,
            progress = snapshot.progress.coerceIn(0f, 1f),
            traces = traces,
        )
    }
}
