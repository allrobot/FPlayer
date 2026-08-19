package io.github.fplayer.android

import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.core.script.DefaultScriptMatcher
import io.github.fplayer.core.script.FunscriptParser
import io.github.fplayer.core.script.ScriptCandidate
import io.github.fplayer.core.script.ScriptMatchResult
import io.github.fplayer.core.script.ScriptMatcher
import io.github.fplayer.core.script.StrictFunscriptParser

/** Reads and matches the scripts committed for one indexed media item. */
class IndexedScriptResolver(
    private val read: (String) -> ByteArray?,
    private val parser: FunscriptParser = StrictFunscriptParser(),
    private val matcher: ScriptMatcher = DefaultScriptMatcher(),
) {
    constructor(read: (String) -> ByteArray?) : this(
        read = read,
        parser = StrictFunscriptParser(),
        matcher = DefaultScriptMatcher(),
    )

    data class IndexedScript(
        val locator: String,
        val normalizedBasename: String,
        val axis: String?,
    )

    fun resolve(mediaName: String, scripts: Collection<IndexedScript>): ScriptMatchResult {
        val candidates = scripts.mapNotNull { script ->
            val bytes = runCatching { read(script.locator) }.getOrNull() ?: return@mapNotNull null
            val bundle = runCatching {
                parser.parse(bytes, scriptFileName(script),)
            }.getOrNull() ?: return@mapNotNull null
            ScriptCandidate(
                locator = MediaLocator(script.locator),
                fileName = scriptFileName(script),
                bundle = bundle,
            )
        }
        return matcher.match(mediaName, candidates)
    }

    private fun scriptFileName(script: IndexedScript): String = buildString {
        append(script.normalizedBasename)
        script.axis?.let { append('.').append(it) }
        append(".funscript")
    }
}
