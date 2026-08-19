package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.core.model.ScriptAction
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultScriptMatcherTest {
    private val matcher = DefaultScriptMatcher()

    @Test
    fun mapsCanonicalAndAliasSuffixesCaseInsensitively() {
        val expected = linkedMapOf(
            "L0" to "L0", "stroke" to "L0", "up" to "L0",
            "L1" to "L1", "surge" to "L1", "forward" to "L1",
            "L2" to "L2", "sway" to "L2", "left" to "L2",
            "R0" to "R0", "twist" to "R0", "yaw" to "R0",
            "R1" to "R1", "roll" to "R1", "R2" to "R2", "pitch" to "R2",
            "V0" to "V0", "vib" to "V0", "V1" to "V1", "pump" to "V1",
            "A0" to "A0", "valve" to "A0", "A1" to "A1", "suck" to "A1",
            "A2" to "A2", "lube" to "A2",
        )

        expected.forEach { (suffix, axisName) ->
            val candidate = candidate("Folder/Clip.${suffix.uppercase(Locale.ROOT)}.FUNSCRIPT", "source-$suffix")
            val result = matcher.match("clip.MP4", listOf(candidate))
            assertEquals(setOf(AxisId(axisName)), result.bundle?.tracks?.keys)
        }
    }

    @Test
    fun preservesAllTracksFromUnnamedEmbeddedBundle() {
        val embedded = ScriptBundle(
            linkedMapOf(
                AxisId("L0") to track("L0", 10),
                AxisId("R0") to track("R0", 20),
            ),
        )

        val result = matcher.match("clip.mp4", listOf(candidate("clip.funscript", "embedded", embedded)))

        assertEquals(listOf("L0", "R0"), result.bundle?.tracks?.keys?.map { it.value })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun treatsRecognizedSuffixAsMediaNameWhenStrippedBaseDoesNotMatch() {
        val result = matcher.match("clip.twist.mp4", listOf(candidate("clip.twist.funscript", "same-name")))

        assertEquals(setOf(AxisId("L0")), result.bundle?.tracks?.keys)
    }

    @Test
    fun canonicalSuffixWinsAliasAndUnnamedWithStableDiagnostic() {
        val candidates = listOf(
            candidate("clip.funscript", "unnamed"),
            candidate("clip.twist.funscript", "alias"),
            candidate("clip.R0.funscript", "canonical"),
        )

        val forward = matcher.match("clip.mp4", candidates)
        val reverse = matcher.match("clip.mp4", candidates.reversed())

        assertEquals(MediaLocator("canonical"), forward.sources[AxisId("R0")])
        assertEquals(forward, reverse)
        val diagnostic = forward.diagnostics.single { it.axis == AxisId("R0") }
        assertEquals(ScriptMatchDiagnosticCode.AMBIGUOUS_AXIS, diagnostic.code)
        assertEquals(MediaLocator("canonical"), diagnostic.selected)
        assertEquals(listOf(MediaLocator("alias")), diagnostic.rejected)
    }

    @Test
    fun samePriorityAmbiguityUsesLocaleRootNameThenLocatorOrdering() {
        val result = matcher.match(
            "clip.mp4",
            listOf(
                candidate("CLIP.YAW.funscript", "z-source"),
                candidate("clip.yaw.funscript", "a-source"),
            ),
        )

        assertEquals(MediaLocator("z-source"), result.sources[AxisId("R0")])
        assertEquals(listOf(MediaLocator("a-source")), result.diagnostics.single().rejected)
    }

    @Test
    fun explicitSuffixRemapsSingleRootTrack() {
        val result = matcher.match("clip.mp4", listOf(candidate("clip.suck.funscript", "suction")))

        val track = result.bundle?.tracks?.get(AxisId("A1"))
        assertEquals(AxisId("A1"), track?.axis)
        assertEquals(50, track?.actions?.single()?.position)
    }

    @Test
    fun rejectsSuffixedFileWithEmbeddedMultiAxisContent() {
        val embedded = ScriptBundle(
            mapOf(
                AxisId("L0") to track("L0", 10),
                AxisId("R0") to track("R0", 20),
            ),
        )
        val result = matcher.match("clip.mp4", listOf(candidate("clip.twist.funscript", "invalid", embedded)))

        assertNull(result.bundle)
        assertTrue(result.sources.isEmpty())
        assertEquals(ScriptMatchDiagnosticCode.INCOMPATIBLE_AXIS_CONTENT, result.diagnostics.single().code)
        assertEquals(MediaLocator("invalid"), result.diagnostics.single().rejected.single())
    }

    @Test
    fun ignoresDifferentBasenamesAndNonFunscriptFiles() {
        val result = matcher.match(
            "clip.mp4",
            listOf(
                candidate("other.funscript", "other"),
                candidate("clip.twist.json", "json"),
                candidate("clip.unknown.funscript", "unknown"),
            ),
        )

        assertNull(result.bundle)
        assertTrue(result.sources.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    private fun candidate(
        fileName: String,
        locator: String,
        bundle: ScriptBundle = ScriptBundle(mapOf(AxisId("L0") to track("L0", 50))),
    ) = ScriptCandidate(MediaLocator(locator), fileName, bundle)

    private fun track(axis: String, position: Int) = ScriptTrack(
        axis = AxisId(axis),
        actions = listOf(ScriptAction(atMs = 0, position = position)),
    )
}
