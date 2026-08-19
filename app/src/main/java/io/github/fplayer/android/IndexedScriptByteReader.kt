package io.github.fplayer.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI

/** Bounded reader for indexed scripts; credentials and locator text never enter diagnostics. */
class IndexedScriptByteReader(
    private val openContent: (String) -> InputStream?,
    private val openSmb: ((String) -> InputStream?)? = null,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : (String) -> ByteArray? {
    init {
        require(maxBytes > 0) { "SCRIPT_MAX_BYTES_INVALID" }
    }

    override fun invoke(locator: String): ByteArray? {
        val uri = runCatching { URI(locator) }.getOrNull() ?: return null
        val stream = runCatching {
            when (uri.scheme?.lowercase()) {
                "content" -> openContent(locator)
                "file" -> uri.path?.let { File(it).inputStream() }
                "smb" -> openSmb?.invoke(locator)
                else -> null
            }
        }.getOrNull() ?: return null
        return stream.use { readBounded(it) }
    }

    private fun readBounded(stream: InputStream): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) return output.toByteArray()
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        private const val DEFAULT_BUFFER_SIZE = 16 * 1024
    }
}
