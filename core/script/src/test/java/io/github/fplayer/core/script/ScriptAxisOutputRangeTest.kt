package io.github.fplayer.core.script

import io.github.fplayer.core.device.AxisLimit
import io.github.fplayer.core.model.AxisId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScriptAxisOutputRangeTest {
    @Test
    fun `script range clamps target and intersection never widens device range`() {
        val script = ScriptAxisOutputRange(20, 80)
        assertEquals(20, applyScriptRange(0, script))
        assertEquals(80, applyScriptRange(100, script))
        assertEquals(AxisLimit(40, 60), intersectAxisRanges(script, AxisLimit(40, 60)))
        assertEquals(AxisLimit(40, 60), intersectAxisRanges(ScriptAxisOutputRange(0, 100), AxisLimit(40, 60)))
    }

    @Test
    fun `range defaults cover every supported axis and reject inverted values`() {
        assertEquals(ScriptAxisOutputRange(), ScriptOutputLimits(emptyMap()).rangeFor(AxisId("L0")))
        assertThrows(IllegalArgumentException::class.java) { ScriptAxisOutputRange(80, 20) }
    }

    @Test
    fun `manual target preserves raw position and duration for scheduler clamping`() {
        val raw = ManualAxisTarget(AxisId("L0"), position = 120, durationMs = 900L)
        assertEquals(120, raw.position)
        assertEquals(900L, raw.durationMs)
        assertEquals(250L, ManualAxisTarget(AxisId("L0"), 50).durationMs)
    }

    @Test
    fun `manual target rejects non-positive duration`() {
        assertThrows(IllegalArgumentException::class.java) { ManualAxisTarget(AxisId("L0"), 50, 0L) }
        assertThrows(IllegalArgumentException::class.java) { ManualAxisTarget(AxisId("L0"), 50, -1L) }
    }

    @Test
    fun `empty intersection reports deterministic output range error`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            intersectAxisRanges(ScriptAxisOutputRange(0, 10), AxisLimit(20, 30))
        }
        assertEquals("NO_OUTPUT_RANGE", error.message)
    }
}
