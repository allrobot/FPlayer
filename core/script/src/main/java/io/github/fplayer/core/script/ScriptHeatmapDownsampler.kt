package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.ScriptAction
import kotlin.math.floor

data class HeatmapWindow(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0)
        require(endMs > startMs)
    }
}

data class HeatmapSample(
    val atMs: Long,
    val position: Int,
)

data class AxisHeatmap(
    val axis: AxisId,
    val samples: List<HeatmapSample>,
    val sourceActionCount: Int,
)

data class ScriptHeatmap(
    val window: HeatmapWindow,
    val axes: Map<AxisId, AxisHeatmap>,
)

object ScriptHeatmapDownsampler {
    fun downsample(
        bundle: ScriptBundle,
        window: HeatmapWindow,
        maxSamplesPerAxis: Int,
    ): ScriptHeatmap {
        require(maxSamplesPerAxis >= POINTS_PER_BUCKET)
        val axes = linkedMapOf<AxisId, AxisHeatmap>()
        bundle.tracks.toSortedMap(compareBy(AxisId::value)).forEach { (axis, track) ->
            axes[axis] = downsample(track, window, maxSamplesPerAxis)
        }
        return ScriptHeatmap(window, axes)
    }

    fun downsample(
        track: ScriptTrack,
        window: HeatmapWindow,
        maxSamples: Int,
    ): AxisHeatmap {
        require(maxSamples >= POINTS_PER_BUCKET)
        val actions = track.actions
        if (actions.isEmpty()) return AxisHeatmap(track.axis, emptyList(), sourceActionCount = 0)

        val fromIndex = lowerBound(actions, window.startMs)
        val toIndex = upperBound(actions, window.endMs)
        val sourceCount = toIndex - fromIndex
        if (sourceCount + 2 <= maxSamples) {
            val samples = buildList {
                addDistinct(boundary(track, window.startMs))
                for (index in fromIndex until toIndex) {
                    addDistinct(actions[index].toHeatmapSample())
                }
                addDistinct(boundary(track, window.endMs))
            }
            return AxisHeatmap(track.axis, samples, sourceCount)
        }

        val bucketCount = maxSamples / POINTS_PER_BUCKET
        val buckets = Array(bucketCount) { Bucket() }
        fun offer(sample: HeatmapSample) {
            val relative = sample.atMs - window.startMs
            val duration = window.endMs - window.startMs
            val fraction = relative.toDouble() / duration.toDouble()
            val index = floor(fraction * bucketCount).toInt().coerceIn(0, bucketCount - 1)
            buckets[index].offer(sample)
        }

        offer(boundary(track, window.startMs))
        for (index in fromIndex until toIndex) offer(actions[index].toHeatmapSample())
        offer(boundary(track, window.endMs))

        val samples = buildList(maxSamples) {
            buckets.forEach { bucket -> bucket.orderedPoints().forEach(::add) }
        }
        return AxisHeatmap(track.axis, samples, sourceCount)
    }

    private fun boundary(track: ScriptTrack, atMs: Long): HeatmapSample = HeatmapSample(
        atMs = atMs,
        position = requireNotNull(ScriptInterpolator.positionAt(track, atMs)),
    )

    private fun lowerBound(actions: List<ScriptAction>, targetMs: Long): Int {
        var low = 0
        var high = actions.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (actions[middle].atMs < targetMs) low = middle + 1 else high = middle
        }
        return low
    }

    private fun upperBound(actions: List<ScriptAction>, targetMs: Long): Int {
        var low = 0
        var high = actions.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (actions[middle].atMs <= targetMs) low = middle + 1 else high = middle
        }
        return low
    }

    private fun MutableList<HeatmapSample>.addDistinct(sample: HeatmapSample) {
        if (lastOrNull() != sample) add(sample)
    }

    private fun ScriptAction.toHeatmapSample() = HeatmapSample(atMs, position)

    private class Bucket {
        private var first: HeatmapSample? = null
        private var last: HeatmapSample? = null
        private var minimum: HeatmapSample? = null
        private var maximum: HeatmapSample? = null

        fun offer(sample: HeatmapSample) {
            if (first == null) first = sample
            last = sample
            if (minimum == null || sample.position < requireNotNull(minimum).position) minimum = sample
            if (maximum == null || sample.position > requireNotNull(maximum).position) maximum = sample
        }

        fun orderedPoints(): List<HeatmapSample> = listOfNotNull(first, minimum, maximum, last)
            .distinct()
            .sortedWith(compareBy(HeatmapSample::atMs, HeatmapSample::position))
    }

    private const val POINTS_PER_BUCKET = 4
}
