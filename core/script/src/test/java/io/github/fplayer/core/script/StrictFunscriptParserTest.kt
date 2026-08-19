package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictFunscriptParserTest {
    private val parser = StrictFunscriptParser()

    @Test
    fun parsesSingleAxisAndIgnoresStandardMetadata() {
        val bundle = parse(
            """{"version":"1.0","metadata":{"title":"Synthetic"},"actions":[{"at":0,"pos":0},{"at":1250,"pos":100}]}""",
        )

        assertEquals(setOf(AxisId("L0")), bundle.tracks.keys)
        assertEquals(listOf(0L, 1250L), bundle.tracks.getValue(AxisId("L0")).actions.map { it.atMs })
        assertEquals(listOf(0, 100), bundle.tracks.getValue(AxisId("L0")).actions.map { it.position })
    }

    @Test
    fun parsesRootActionsAndEmbeddedAxes() {
        val bundle = parse(
            """{
                "actions":[{"at":0,"pos":10}],
                "axes":[
                    {"id":"R0","actions":[{"at":50,"pos":20}]},
                    {"id":"A2","actions":[{"at":75,"pos":30}]}
                ]
            }""".trimIndent(),
        )

        assertEquals(listOf("L0", "R0", "A2"), bundle.tracks.keys.map { it.value })
    }

    @Test
    fun parsesEmbeddedAxesWithoutRootActions() {
        val bundle = parse(
            """{"axes":[{"id":"L1","actions":[{"at":0,"pos":50}]}]}""",
        )

        assertEquals(setOf(AxisId("L1")), bundle.tracks.keys)
    }

    @Test
    fun reportsStableCodesForMalformedActions() {
        val cases = listOf(
            Case("", ScriptParseErrorCode.EMPTY_INPUT, "$"),
            Case("{", ScriptParseErrorCode.MALFORMED_JSON, "$"),
            Case("[]", ScriptParseErrorCode.INVALID_ROOT, "$"),
            Case("{}", ScriptParseErrorCode.MISSING_ACTIONS, "$.actions"),
            Case("{\"actions\":{}}", ScriptParseErrorCode.ACTIONS_NOT_ARRAY, "$.actions"),
            Case("{\"actions\":[]}", ScriptParseErrorCode.EMPTY_ACTIONS, "$.actions"),
            Case("{\"actions\":[0]}", ScriptParseErrorCode.INVALID_ACTION, "$.actions[0]"),
            Case("{\"actions\":[{\"pos\":1}]}", ScriptParseErrorCode.MISSING_AT, "$.actions[0].at"),
            Case("{\"actions\":[{\"at\":1.5,\"pos\":1}]}", ScriptParseErrorCode.NON_INTEGRAL_AT, "$.actions[0].at"),
            Case("{\"actions\":[{\"at\":-1,\"pos\":1}]}", ScriptParseErrorCode.NEGATIVE_AT, "$.actions[0].at"),
            Case("{\"actions\":[{\"at\":1}]}", ScriptParseErrorCode.MISSING_POS, "$.actions[0].pos"),
            Case("{\"actions\":[{\"at\":1,\"pos\":1.5}]}", ScriptParseErrorCode.NON_INTEGRAL_POS, "$.actions[0].pos"),
            Case("{\"actions\":[{\"at\":1,\"pos\":-1}]}", ScriptParseErrorCode.POS_OUT_OF_RANGE, "$.actions[0].pos"),
            Case("{\"actions\":[{\"at\":1,\"pos\":101}]}", ScriptParseErrorCode.POS_OUT_OF_RANGE, "$.actions[0].pos"),
            Case("{\"actions\":[{\"at\":2,\"pos\":1},{\"at\":1,\"pos\":2}]}", ScriptParseErrorCode.UNSORTED_AT, "$.actions[1].at"),
            Case("{\"actions\":[{\"at\":1,\"pos\":1},{\"at\":1,\"pos\":2}]}", ScriptParseErrorCode.DUPLICATE_AT, "$.actions[1].at"),
            Case("{\"actions\":[{\"at\":1,\"pos\":1},{\"at\":2,\"pos\":2},{\"at\":1,\"pos\":3}]}", ScriptParseErrorCode.DUPLICATE_AT, "$.actions[2].at"),
        )

        cases.forEach { case ->
            val error = parseFailure(case.json)
            assertEquals(case.code, error.code)
            assertEquals(case.path, error.jsonPath)
            assertEquals(SOURCE_NAME, error.sourceName)
        }
    }

    @Test
    fun rejectsInvalidUtf8WithoutEchoingInput() {
        val error = try {
            parser.parse(byteArrayOf(0xC3.toByte(), 0x28), SOURCE_NAME)
            throw AssertionError("Expected parsing to fail")
        } catch (expected: ScriptParseException) {
            expected
        }

        assertEquals(ScriptParseErrorCode.INVALID_UTF8, error.code)
        assertTrue(error.message.orEmpty().contains(SOURCE_NAME))
    }

    @Test
    fun reportsStableCodesForMalformedAxes() {
        val cases = listOf(
            Case("{\"axes\":{}}", ScriptParseErrorCode.AXES_NOT_ARRAY, "$.axes"),
            Case("{\"axes\":[]}", ScriptParseErrorCode.EMPTY_AXES, "$.axes"),
            Case("{\"axes\":[0]}", ScriptParseErrorCode.INVALID_AXIS, "$.axes[0]"),
            Case("{\"axes\":[{\"actions\":[{\"at\":0,\"pos\":0}]}]}", ScriptParseErrorCode.MISSING_AXIS_ID, "$.axes[0].id"),
            Case("{\"axes\":[{\"id\":\"l0\",\"actions\":[{\"at\":0,\"pos\":0}]}]}", ScriptParseErrorCode.INVALID_AXIS_ID, "$.axes[0].id"),
            Case("{\"axes\":[{\"id\":\"X9\",\"actions\":[{\"at\":0,\"pos\":0}]}]}", ScriptParseErrorCode.INVALID_AXIS_ID, "$.axes[0].id"),
            Case("{\"axes\":[{\"id\":\"R0\"}]}", ScriptParseErrorCode.MISSING_ACTIONS, "$.axes[0].actions"),
            Case(
                "{\"axes\":[{\"id\":\"R0\",\"actions\":[{\"at\":0,\"pos\":0}]},{\"id\":\"R0\",\"actions\":[{\"at\":1,\"pos\":1}]}]}",
                ScriptParseErrorCode.DUPLICATE_AXIS,
                "$.axes[1].id",
            ),
            Case(
                "{\"actions\":[{\"at\":0,\"pos\":0}],\"axes\":[{\"id\":\"L0\",\"actions\":[{\"at\":1,\"pos\":1}]}]}",
                ScriptParseErrorCode.ROOT_AXIS_CONFLICT,
                "$.axes[0].id",
            ),
        )

        cases.forEach { case ->
            val error = parseFailure(case.json)
            assertEquals(case.code, error.code)
            assertEquals(case.path, error.jsonPath)
        }
    }

    private fun parse(json: String): ScriptBundle = parser.parse(json.toByteArray(Charsets.UTF_8), SOURCE_NAME)

    private fun parseFailure(json: String): ScriptParseException = try {
        parse(json)
        throw AssertionError("Expected parsing to fail: $json")
    } catch (expected: ScriptParseException) {
        expected
    }

    private data class Case(
        val json: String,
        val code: ScriptParseErrorCode,
        val path: String,
    )

    private companion object {
        const val SOURCE_NAME = "synthetic.funscript"
    }
}
