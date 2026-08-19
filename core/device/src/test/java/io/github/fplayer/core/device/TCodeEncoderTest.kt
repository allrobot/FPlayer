package io.github.fplayer.core.device

import io.github.fplayer.core.model.AxisId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TCodeEncoderTest {
    @Test
    fun `version 0_2 encodes three digit positions and interval byte for byte`() {
        assertBytes("L0000\n", encoder(TCodeVersion.V0_2).encode(target("L0", 0)))
        assertBytes("L0500I250\n", encoder(TCodeVersion.V0_2).encode(target("L0", 50, 250)))
        assertBytes("V1999I9999\n", encoder(TCodeVersion.V0_2).encode(target("V1", 100, 9_999)))
    }

    @Test
    fun `version 0_3 encodes four digit positions and one LF`() {
        assertBytes("R00000\n", encoder(TCodeVersion.V0_3).encode(target("R0", 0)))
        assertBytes("R05000I1\n", encoder(TCodeVersion.V0_3).encode(target("R0", 50, 1)))
        assertBytes("A29999I9999999\n", encoder(TCodeVersion.V0_3).encode(target("A2", 100, 9_999_999)))
    }

    @Test
    fun `version 0_4 shares four digit core syntax`() {
        assertBytes("L25000I500\n", encoder(TCodeVersion.V0_4).encode(target("L2", 50, 500)))
        assertBytes("V39999\n", encoder(TCodeVersion.V0_4).encode(target("V3", 100)))
    }

    @Test
    fun `percentage conversion rounds half up deterministically`() {
        assertBytes("L0010\n", encoder(TCodeVersion.V0_2).encode(target("L0", 1)))
        assertBytes("L00100\n", encoder(TCodeVersion.V0_3).encode(target("L0", 1)))
        assertBytes("L03300\n", encoder(TCodeVersion.V0_4).encode(target("L0", 33)))
    }

    @Test
    fun `frame separates commands with ASCII space and terminates once`() {
        val bytes = encoder(TCodeVersion.V0_3).encodeFrame(
            listOf(target("L0", 25, 100), target("R2", 75, 100)),
        )
        assertBytes("L02500I100 R27499I100\n", bytes)
        assertFalse(bytes.toString(Charsets.US_ASCII).contains('\r'))
    }

    @Test
    fun `registry exposes only firmware axes supported by each version`() {
        assertEquals(
            listOf("L0", "L1", "L2", "L3", "R0", "R1", "R2", "V0", "V1"),
            TCodeAxisRegistry.forVersion(TCodeVersion.V0_2).map { it.id.value },
        )
        assertEquals(14, TCodeAxisRegistry.forVersion(TCodeVersion.V0_3).size)
        assertEquals(14, TCodeAxisRegistry.forVersion(TCodeVersion.V0_4).size)
        assertTrue(TCodeAxisRegistry.find(AxisId("A3"), TCodeVersion.V0_4) != null)
        assertTrue(TCodeAxisRegistry.find(AxisId("L3"), TCodeVersion.V0_3) == null)
    }

    @Test
    fun `identification accepts firmware spelling and rejects unsupported versions`() {
        assertEquals(TCodeVersion.V0_2, TCodeVersion.parseIdentification("TCode v0.2"))
        assertEquals(TCodeVersion.V0_3, TCodeVersion.parseIdentification(" tcode V0.3\r\n"))
        assertEquals(TCodeVersion.V0_4, TCodeVersion.parseIdentification("TCode v0.4"))
        assertFailure(TCodeErrorCode.UNSUPPORTED_VERSION) {
            TCodeVersion.parseIdentification("TCode v0.5")
        }
        assertFailure(TCodeErrorCode.UNSUPPORTED_VERSION) {
            TCodeVersion.parseIdentification("firmware 0.4")
        }
    }

    @Test
    fun `negotiation query and declared version match firmware byte for byte`() {
        assertBytes("D1\n", TCodeVersionNegotiator.queryBytes())
        assertEquals(
            TCodeVersion.V0_4,
            TCodeVersionNegotiator.resolve(TCodeVersion.V0_4, "TCode v0.4\r\n"),
        )
        assertEquals(
            TCodeVersion.V0_3,
            TCodeVersionNegotiator.resolve(null, "TCode v0.3\n"),
        )
        assertFailure(TCodeErrorCode.VERSION_MISMATCH) {
            TCodeVersionNegotiator.resolve(TCodeVersion.V0_3, "TCode v0.4")
        }
    }

    @Test
    fun `unknown and version incompatible axes are rejected`() {
        assertFailure(TCodeErrorCode.UNKNOWN_AXIS) {
            encoder(TCodeVersion.V0_3).encode(target("L3", 50))
        }
        assertFailure(TCodeErrorCode.UNKNOWN_AXIS) {
            encoder(TCodeVersion.V0_2).encode(target("A0", 50))
        }
        assertFailure(TCodeErrorCode.UNKNOWN_AXIS) {
            encoder(TCodeVersion.V0_4).encode(target("L9", 50))
        }
    }

    @Test
    fun `invalid positions durations and empty frames are rejected`() {
        assertFailure(TCodeErrorCode.POSITION_OUT_OF_RANGE) {
            encoder(TCodeVersion.V0_3).encode(target("L0", -1))
        }
        assertFailure(TCodeErrorCode.POSITION_OUT_OF_RANGE) {
            encoder(TCodeVersion.V0_3).encode(target("L0", 101))
        }
        assertFailure(TCodeErrorCode.NEGATIVE_DURATION) {
            encoder(TCodeVersion.V0_3).encode(target("L0", 50, -1))
        }
        assertFailure(TCodeErrorCode.DURATION_OUT_OF_RANGE) {
            encoder(TCodeVersion.V0_2).encode(target("L0", 50, 10_000))
        }
        assertFailure(TCodeErrorCode.DURATION_OUT_OF_RANGE) {
            encoder(TCodeVersion.V0_3).encode(target("L0", 50, 10_000_000))
        }
        assertFailure(TCodeErrorCode.EMPTY_FRAME) {
            encoder(TCodeVersion.V0_4).encodeFrame(emptyList())
        }
    }

    private fun encoder(version: TCodeVersion) = TCodeEncoder(version)

    private fun target(axis: String, position: Int, durationMs: Long = 0) =
        TCodeAxisTarget(AxisId(axis), position, durationMs)

    private fun assertBytes(expected: String, actual: ByteArray) =
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), actual)

    private fun assertFailure(expected: TCodeErrorCode, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull() as TCodeProtocolException
        assertEquals(expected, error.code)
    }
}
