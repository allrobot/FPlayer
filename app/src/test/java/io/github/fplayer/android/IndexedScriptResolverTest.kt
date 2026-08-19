package io.github.fplayer.android

import io.github.fplayer.core.model.AxisId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class IndexedScriptResolverTest {
    @Test
    fun byteReaderSupportsContentLocalAndInjectedSmbWithoutExposingLocator() {
        val local = Files.createTempFile("fplayer-script", ".funscript")
        Files.write(local, byteArrayOf(1, 2, 3))
        try {
            val reader = IndexedScriptByteReader(
                openContent = { locator ->
                    assertEquals("content://script/1", locator)
                    ByteArrayInputStream(byteArrayOf(4, 5))
                },
                openSmb = { locator ->
                    assertEquals("smb://logical-source/script.funscript", locator)
                    ByteArrayInputStream(byteArrayOf(6, 7))
                },
            )

            assertEquals(listOf<Byte>(4, 5), reader("content://script/1")?.toList())
            assertEquals(listOf<Byte>(1, 2, 3), reader(local.toUri().toString())?.toList())
            assertEquals(listOf<Byte>(6, 7), reader("smb://logical-source/script.funscript")?.toList())
        } finally {
            Files.deleteIfExists(local)
        }
    }

    @Test
    fun byteReaderRejectsUnsupportedAndOversizedLocatorsWithoutReadingCredentials() {
        var smbCalls = 0
        val reader = IndexedScriptByteReader(
            openContent = { ByteArrayInputStream(ByteArray(16)) },
            openSmb = { smbCalls += 1; ByteArrayInputStream(ByteArray(16)) },
            maxBytes = 8,
        )

        assertEquals(null, reader("https://example.invalid/script.funscript"))
        assertEquals(null, reader("content://too-large"))
        assertEquals(0, smbCalls)
    }

    @Test
    fun resolvesIndexedCanonicalScriptIntoMatchedBundle() {
        val json = """{"actions":[{"at":0,"pos":10},{"at":1000,"pos":90}]}"""
        val resolver = IndexedScriptResolver { locator ->
            assertEquals("content://script/1", locator)
            json.toByteArray(Charsets.UTF_8)
        }

        val result = resolver.resolve(
            mediaName = "clip.mp4",
            scripts = listOf(
                IndexedScriptResolver.IndexedScript(
                    locator = "content://script/1",
                    normalizedBasename = "clip",
                    axis = "L0",
                ),
            ),
        )

        assertNotNull(result.bundle)
        assertEquals(setOf(AxisId("L0")), result.bundle?.tracks?.keys)
        assertEquals(90, result.bundle?.tracks?.getValue(AxisId("L0"))?.actions?.last()?.position)
    }

    @Test
    fun skipsUnreadableIndexedScriptWithoutThrowing() {
        val resolver = IndexedScriptResolver { null }

        val result = resolver.resolve(
            mediaName = "clip.mp4",
            scripts = listOf(
                IndexedScriptResolver.IndexedScript("content://missing", "clip", "L0"),
            ),
        )

        assertEquals(null, result.bundle)
    }
}
