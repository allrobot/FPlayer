package io.github.fplayer.core.script

import kotlin.math.roundToInt

object ScriptInterpolator {
    fun positionAt(track: ScriptTrack, scriptTimeMs: Long): Int? {
        if (scriptTimeMs < 0 || track.actions.isEmpty()) return null
        val actions = track.actions
        if (scriptTimeMs <= actions.first().atMs) return actions.first().position
        if (scriptTimeMs >= actions.last().atMs) return actions.last().position

        var low = 0
        var high = actions.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            if (actions[middle].atMs <= scriptTimeMs) low = middle else high = middle
        }

        val from = actions[low]
        val to = actions[high]
        val progress = (scriptTimeMs - from.atMs).toDouble() / (to.atMs - from.atMs).toDouble()
        return (from.position + (to.position - from.position) * progress)
            .roundToInt()
            .coerceIn(0, 100)
    }
}
