package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.core.model.ScriptAction

data class ScriptTrack(
    val axis: AxisId,
    val actions: List<ScriptAction>,
)

data class ScriptBundle(
    val tracks: Map<AxisId, ScriptTrack>,
) {
    init {
        require(tracks.isNotEmpty()) { "A script bundle must contain at least one track" }
        require(tracks.all { (axis, track) -> axis == track.axis }) {
            "Script track keys must match their axis"
        }
    }
}

enum class ScriptParseErrorCode {
    EMPTY_INPUT,
    INVALID_UTF8,
    MALFORMED_JSON,
    INVALID_ROOT,
    MISSING_ACTIONS,
    ACTIONS_NOT_ARRAY,
    EMPTY_ACTIONS,
    INVALID_ACTION,
    MISSING_AT,
    NON_INTEGRAL_AT,
    NEGATIVE_AT,
    MISSING_POS,
    NON_INTEGRAL_POS,
    POS_OUT_OF_RANGE,
    UNSORTED_AT,
    DUPLICATE_AT,
    AXES_NOT_ARRAY,
    EMPTY_AXES,
    INVALID_AXIS,
    MISSING_AXIS_ID,
    INVALID_AXIS_ID,
    DUPLICATE_AXIS,
    ROOT_AXIS_CONFLICT,
}

class ScriptParseException(
    val code: ScriptParseErrorCode,
    val sourceName: String,
    val jsonPath: String,
    detail: String,
    cause: Throwable? = null,
) : IllegalArgumentException("$sourceName: $jsonPath: ${code.name}: $detail", cause)

data class ScriptCandidate(
    val locator: MediaLocator,
    val fileName: String,
    val bundle: ScriptBundle,
)

enum class ScriptMatchDiagnosticCode {
    AMBIGUOUS_AXIS,
    INCOMPATIBLE_AXIS_CONTENT,
}

data class ScriptMatchDiagnostic(
    val code: ScriptMatchDiagnosticCode,
    val axis: AxisId?,
    val selected: MediaLocator?,
    val rejected: List<MediaLocator>,
)

data class ScriptMatchResult(
    val bundle: ScriptBundle?,
    val sources: Map<AxisId, MediaLocator>,
    val diagnostics: List<ScriptMatchDiagnostic>,
)

enum class MissingScriptBehavior {
    USE_SELECTED_SCRIPT,
    RANDOM_AXIS,
    LINEAR_CYCLE,
    DO_NOTHING,
}

interface FunscriptParser {
    @Throws(ScriptParseException::class)
    fun parse(bytes: ByteArray, sourceName: String): ScriptBundle
}

interface ScriptMatcher {
    fun match(mediaName: String, candidates: Collection<ScriptCandidate>): ScriptMatchResult
}
