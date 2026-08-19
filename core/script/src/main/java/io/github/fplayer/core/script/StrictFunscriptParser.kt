package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.ScriptAction
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class StrictFunscriptParser(
    private val json: Json = Json,
) : FunscriptParser {
    override fun parse(bytes: ByteArray, sourceName: String): ScriptBundle {
        val text = decode(bytes, sourceName)
        if (text.isBlank()) {
            fail(ScriptParseErrorCode.EMPTY_INPUT, sourceName, "$", "input is empty")
        }

        val root = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            fail(ScriptParseErrorCode.MALFORMED_JSON, sourceName, "$", "invalid JSON", error)
        } catch (error: IllegalArgumentException) {
            fail(ScriptParseErrorCode.MALFORMED_JSON, sourceName, "$", "invalid JSON", error)
        }
        if (root !is JsonObject) {
            fail(ScriptParseErrorCode.INVALID_ROOT, sourceName, "$", "root must be an object")
        }

        val tracks = linkedMapOf<AxisId, ScriptTrack>()
        root["actions"]?.let { actionsElement ->
            tracks[L0] = parseTrack(L0, actionsElement, sourceName, "$.actions")
        }

        root["axes"]?.let { axesElement ->
            val axes = axesElement as? JsonArray
                ?: fail(ScriptParseErrorCode.AXES_NOT_ARRAY, sourceName, "$.axes", "axes must be an array")
            if (axes.isEmpty()) {
                fail(ScriptParseErrorCode.EMPTY_AXES, sourceName, "$.axes", "axes must not be empty")
            }
            axes.forEachIndexed { index, element ->
                val path = "$.axes[$index]"
                val axisObject = element as? JsonObject
                    ?: fail(ScriptParseErrorCode.INVALID_AXIS, sourceName, path, "axis must be an object")
                val axis = parseAxis(axisObject["id"], sourceName, "$path.id")
                if (tracks.containsKey(axis)) {
                    val code = if (axis == L0 && root.containsKey("actions")) {
                        ScriptParseErrorCode.ROOT_AXIS_CONFLICT
                    } else {
                        ScriptParseErrorCode.DUPLICATE_AXIS
                    }
                    fail(code, sourceName, "$path.id", "axis ${axis.value} is declared more than once")
                }
                val actions = axisObject["actions"]
                    ?: fail(ScriptParseErrorCode.MISSING_ACTIONS, sourceName, "$path.actions", "actions is required")
                tracks[axis] = parseTrack(axis, actions, sourceName, "$path.actions")
            }
        }

        if (tracks.isEmpty()) {
            fail(ScriptParseErrorCode.MISSING_ACTIONS, sourceName, "$.actions", "actions or axes is required")
        }
        return ScriptBundle(tracks.toMap())
    }

    private fun decode(bytes: ByteArray, sourceName: String): String {
        if (bytes.isEmpty()) {
            fail(ScriptParseErrorCode.EMPTY_INPUT, sourceName, "$", "input is empty")
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            fail(ScriptParseErrorCode.INVALID_UTF8, sourceName, "$", "input is not valid UTF-8", error)
        }
    }

    private fun parseAxis(element: JsonElement?, sourceName: String, path: String): AxisId {
        if (element == null) {
            fail(ScriptParseErrorCode.MISSING_AXIS_ID, sourceName, path, "axis id is required")
        }
        val primitive = element as? JsonPrimitive
        val value = primitive?.takeIf { it.isString }?.content
        if (value == null || value !in SUPPORTED_AXIS_NAMES) {
            fail(ScriptParseErrorCode.INVALID_AXIS_ID, sourceName, path, "unsupported axis id")
        }
        return AxisId(value)
    }

    private fun parseTrack(
        axis: AxisId,
        element: JsonElement,
        sourceName: String,
        path: String,
    ): ScriptTrack {
        val actions = element as? JsonArray
            ?: fail(ScriptParseErrorCode.ACTIONS_NOT_ARRAY, sourceName, path, "actions must be an array")
        if (actions.isEmpty()) {
            fail(ScriptParseErrorCode.EMPTY_ACTIONS, sourceName, path, "actions must not be empty")
        }

        var previousAt: Long? = null
        val seenTimestamps = mutableSetOf<Long>()
        val parsed = actions.mapIndexed { index, actionElement ->
            val actionPath = "$path[$index]"
            val action = actionElement as? JsonObject
                ?: fail(ScriptParseErrorCode.INVALID_ACTION, sourceName, actionPath, "action must be an object")
            val at = integral(action["at"], sourceName, "$actionPath.at", isTime = true)
            val pos = integral(action["pos"], sourceName, "$actionPath.pos", isTime = false)
            if (at < 0) {
                fail(ScriptParseErrorCode.NEGATIVE_AT, sourceName, "$actionPath.at", "at must be non-negative")
            }
            if (pos !in 0..100) {
                fail(ScriptParseErrorCode.POS_OUT_OF_RANGE, sourceName, "$actionPath.pos", "pos must be in 0..100")
            }
            if (!seenTimestamps.add(at)) {
                fail(ScriptParseErrorCode.DUPLICATE_AT, sourceName, "$actionPath.at", "timestamp is duplicated")
            }
            previousAt?.let { previous ->
                if (at < previous) {
                    fail(ScriptParseErrorCode.UNSORTED_AT, sourceName, "$actionPath.at", "timestamps must be strictly increasing")
                }
            }
            previousAt = at
            ScriptAction(atMs = at, position = pos.toInt())
        }
        return ScriptTrack(axis = axis, actions = parsed)
    }

    private fun integral(
        element: JsonElement?,
        sourceName: String,
        path: String,
        isTime: Boolean,
    ): Long {
        if (element == null) {
            val code = if (isTime) ScriptParseErrorCode.MISSING_AT else ScriptParseErrorCode.MISSING_POS
            fail(code, sourceName, path, "value is required")
        }
        val primitive = element as? JsonPrimitive
        val value = primitive?.takeUnless { it.isString }?.content?.toLongOrNull()
        if (value == null) {
            val code = if (isTime) ScriptParseErrorCode.NON_INTEGRAL_AT else ScriptParseErrorCode.NON_INTEGRAL_POS
            fail(code, sourceName, path, "value must be an integer")
        }
        return value
    }

    private fun fail(
        code: ScriptParseErrorCode,
        sourceName: String,
        path: String,
        detail: String,
        cause: Throwable? = null,
    ): Nothing = throw ScriptParseException(code, sourceName, path, detail, cause)

    private companion object {
        val L0 = AxisId("L0")
        val SUPPORTED_AXIS_NAMES = setOf("L0", "L1", "L2", "R0", "R1", "R2", "V0", "V1", "A0", "A1", "A2")
    }
}
