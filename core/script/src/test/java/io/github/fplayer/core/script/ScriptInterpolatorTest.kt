package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.ScriptAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptInterpolatorTest {
    private val track = ScriptTrack(
        axis = AxisId("L0"),
        actions = listOf(
            ScriptAction(100, 20),
            ScriptAction(300, 80),
            ScriptAction(500, 40),
        ),
    )

    @Test
    fun returnsNullForNegativeTimeAndEmptyTrack() {
        assertNull(ScriptInterpolator.positionAt(track, -1))
        assertNull(ScriptInterpolator.positionAt(track.copy(actions = emptyList()), 0))
    }

    @Test
    fun holdsFirstAndLastPositionsOutsideActionRange() {
        assertEquals(20, ScriptInterpolator.positionAt(track, 0))
        assertEquals(20, ScriptInterpolator.positionAt(track, 100))
        assertEquals(40, ScriptInterpolator.positionAt(track, 500))
        assertEquals(40, ScriptInterpolator.positionAt(track, 5_000))
    }

    @Test
    fun interpolatesRisingAndFallingSegments() {
        assertEquals(35, ScriptInterpolator.positionAt(track, 150))
        assertEquals(50, ScriptInterpolator.positionAt(track, 200))
        assertEquals(60, ScriptInterpolator.positionAt(track, 400))
    }

    @Test
    fun roundsHalfPositionsDeterministically() {
        val half = ScriptTrack(
            axis = AxisId("R0"),
            actions = listOf(ScriptAction(0, 0), ScriptAction(200, 1)),
        )

        assertEquals(1, ScriptInterpolator.positionAt(half, 100))
    }
}
