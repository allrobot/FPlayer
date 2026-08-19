package io.github.fplayer.core.device

import io.github.fplayer.core.model.AxisId
import java.nio.charset.StandardCharsets
import java.util.Locale

data class TCodeAxisTarget(
    val axis: AxisId,
    val position: Int,
    val durationMs: Long = 0,
)

class TCodeEncoder(
    val version: TCodeVersion,
) {
    fun encode(target: TCodeAxisTarget): ByteArray = encodeFrame(listOf(target))

    fun encode(target: DeviceTarget): ByteArray = encode(
        TCodeAxisTarget(target.axis, target.position, target.durationMs),
    )

    fun encodeFrame(targets: List<TCodeAxisTarget>): ByteArray {
        if (targets.isEmpty()) {
            throw TCodeProtocolException(TCodeErrorCode.EMPTY_FRAME, "A TCode frame must contain a target")
        }
        val frame = targets.joinToString(separator = " ", postfix = "\n") { encodeCommand(it) }
        return frame.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun encodeCommand(target: TCodeAxisTarget): String {
        TCodeAxisRegistry.require(target.axis, version)
        if (target.position !in POSITION_RANGE) {
            throw TCodeProtocolException(
                TCodeErrorCode.POSITION_OUT_OF_RANGE,
                "Position must be in 0..100: ${target.position}",
            )
        }
        if (target.durationMs < 0) {
            throw TCodeProtocolException(
                TCodeErrorCode.NEGATIVE_DURATION,
                "Duration must not be negative: ${target.durationMs}",
            )
        }
        if (target.durationMs > version.maximumDurationMs) {
            throw TCodeProtocolException(
                TCodeErrorCode.DURATION_OUT_OF_RANGE,
                "Duration exceeds TCode ${version.wireName} maximum: ${target.durationMs}",
            )
        }

        val protocolPosition = (target.position * version.positionMaximum + 50) / 100
        val position = String.format(Locale.ROOT, "%0${version.positionDigits}d", protocolPosition)
        val interval = if (target.durationMs == 0L) "" else "I${target.durationMs}"
        return "${target.axis.value}$position$interval"
    }

    private companion object {
        val POSITION_RANGE = 0..100
    }
}
