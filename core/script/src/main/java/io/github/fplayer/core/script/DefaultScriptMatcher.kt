package io.github.fplayer.core.script

import io.github.fplayer.core.model.AxisId
import java.util.Locale

class DefaultScriptMatcher : ScriptMatcher {
    override fun match(
        mediaName: String,
        candidates: Collection<ScriptCandidate>,
    ): ScriptMatchResult {
        val mediaStem = stem(fileName(mediaName))
        val diagnostics = mutableListOf<ScriptMatchDiagnostic>()
        val contributions = mutableListOf<Contribution>()

        candidates.sortedWith(CANDIDATE_COMPARATOR).forEach { candidate ->
            val nameMatch = classify(candidate.fileName, mediaStem) ?: return@forEach
            if (nameMatch.axis == null) {
                candidate.bundle.tracks.forEach { (axis, track) ->
                    contributions += Contribution(axis, track, candidate, UNNAMED_PRIORITY)
                }
                return@forEach
            }

            val onlyTrack = candidate.bundle.tracks.values.singleOrNull()
            if (onlyTrack == null || (onlyTrack.axis != L0 && onlyTrack.axis != nameMatch.axis)) {
                diagnostics += ScriptMatchDiagnostic(
                    code = ScriptMatchDiagnosticCode.INCOMPATIBLE_AXIS_CONTENT,
                    axis = nameMatch.axis,
                    selected = null,
                    rejected = listOf(candidate.locator),
                )
                return@forEach
            }
            contributions += Contribution(
                axis = nameMatch.axis,
                track = onlyTrack.copy(axis = nameMatch.axis),
                candidate = candidate,
                priority = nameMatch.priority,
            )
        }

        val selectedTracks = linkedMapOf<AxisId, ScriptTrack>()
        val selectedSources = linkedMapOf<AxisId, io.github.fplayer.core.model.MediaLocator>()
        contributions.groupBy { it.axis }.toSortedMap(compareBy(AxisId::value)).forEach { (axis, matches) ->
            val ordered = matches.sortedWith(CONTRIBUTION_COMPARATOR)
            val selected = ordered.first()
            selectedTracks[axis] = selected.track
            selectedSources[axis] = selected.candidate.locator
            if (ordered.size > 1) {
                diagnostics += ScriptMatchDiagnostic(
                    code = ScriptMatchDiagnosticCode.AMBIGUOUS_AXIS,
                    axis = axis,
                    selected = selected.candidate.locator,
                    rejected = ordered.drop(1).map { it.candidate.locator },
                )
            }
        }

        return ScriptMatchResult(
            bundle = selectedTracks.takeIf { it.isNotEmpty() }?.let(::ScriptBundle),
            sources = selectedSources,
            diagnostics = diagnostics,
        )
    }

    private fun classify(scriptName: String, mediaStem: String): NameMatch? {
        val name = fileName(scriptName)
        if (!name.endsWith(FUNSCRIPT_EXTENSION, ignoreCase = true)) return null
        val scriptStem = name.dropLast(FUNSCRIPT_EXTENSION.length)
        val suffixSeparator = scriptStem.lastIndexOf('.')
        if (suffixSeparator >= 0) {
            val suffix = scriptStem.substring(suffixSeparator + 1).lowercase(Locale.ROOT)
            val rule = SUFFIX_RULES[suffix]
            if (rule != null) {
                val base = scriptStem.substring(0, suffixSeparator)
                if (base.equals(mediaStem, ignoreCase = true)) return rule
            }
        }
        return NameMatch(axis = null, priority = UNNAMED_PRIORITY)
            .takeIf { scriptStem.equals(mediaStem, ignoreCase = true) }
    }

    private fun fileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')

    private fun stem(value: String): String {
        val separator = value.lastIndexOf('.')
        return if (separator > 0) value.substring(0, separator) else value
    }

    private data class NameMatch(
        val axis: AxisId?,
        val priority: Int,
    )

    private data class Contribution(
        val axis: AxisId,
        val track: ScriptTrack,
        val candidate: ScriptCandidate,
        val priority: Int,
    )

    private companion object {
        const val FUNSCRIPT_EXTENSION = ".funscript"
        const val UNNAMED_PRIORITY = 100
        val L0 = AxisId("L0")

        val AXIS_ALIASES = linkedMapOf(
            "L0" to listOf("stroke", "up"),
            "L1" to listOf("surge", "forward"),
            "L2" to listOf("sway", "left"),
            "R0" to listOf("twist", "yaw"),
            "R1" to listOf("roll"),
            "R2" to listOf("pitch"),
            "V0" to listOf("vib"),
            "V1" to listOf("pump"),
            "A0" to listOf("valve"),
            "A1" to listOf("suck"),
            "A2" to listOf("lube"),
        )
        val SUFFIX_RULES: Map<String, NameMatch> = buildMap {
            AXIS_ALIASES.forEach { (axisName, aliases) ->
                val axis = AxisId(axisName)
                put(axisName.lowercase(Locale.ROOT), NameMatch(axis, priority = 0))
                aliases.forEachIndexed { index, alias ->
                    put(alias.lowercase(Locale.ROOT), NameMatch(axis, priority = index + 1))
                }
            }
        }
        val CANDIDATE_COMPARATOR = compareBy<ScriptCandidate>(
            { it.fileName.lowercase(Locale.ROOT) },
            ScriptCandidate::fileName,
            { it.locator.value },
        )
        val CONTRIBUTION_COMPARATOR = compareBy<Contribution>(
            Contribution::priority,
            { it.candidate.fileName.lowercase(Locale.ROOT) },
            { it.candidate.fileName },
            { it.candidate.locator.value },
        )
    }
}
