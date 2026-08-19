package io.github.fplayer.core.script

import io.github.fplayer.core.device.AxisLimit
import io.github.fplayer.core.model.AxisId

data class ScriptAxisOutputRange(val minimum: Int = 0, val maximum: Int = 100) {
    init {
        require(minimum in 0..100) { "minimum must be in 0..100" }
        require(maximum in 0..100) { "maximum must be in 0..100" }
        require(minimum <= maximum) { "minimum must not exceed maximum" }
    }
}

data class ScriptOutputLimits(val perAxis: Map<AxisId, ScriptAxisOutputRange> = emptyMap()) {
    fun rangeFor(axis: AxisId): ScriptAxisOutputRange = perAxis[axis] ?: ScriptAxisOutputRange()
}

data class ManualAxisTarget(val axis: AxisId, val position: Int, val durationMs: Long = 250L) {
    init {
        require(durationMs > 0L) { "durationMs must be positive" }
    }
}

fun applyScriptRange(position: Int, range: ScriptAxisOutputRange): Int =
    position.coerceIn(range.minimum, range.maximum)

fun intersectAxisRanges(script: ScriptAxisOutputRange, device: AxisLimit): AxisLimit {
    val minimum = maxOf(script.minimum, device.minimum)
    val maximum = minOf(script.maximum, device.maximum)
    require(minimum <= maximum) { "NO_OUTPUT_RANGE" }
    return AxisLimit(minimum, maximum)
}
