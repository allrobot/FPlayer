package io.github.fplayer.core.device

import io.github.fplayer.core.model.AxisId
import java.nio.charset.StandardCharsets

enum class TCodeVersion(
    val wireName: String,
    internal val positionMaximum: Int,
    internal val positionDigits: Int,
    internal val maximumDurationMs: Long,
) {
    V0_2("0.2", positionMaximum = 999, positionDigits = 3, maximumDurationMs = 9_999),
    V0_3("0.3", positionMaximum = 9_999, positionDigits = 4, maximumDurationMs = 9_999_999),
    V0_4("0.4", positionMaximum = 9_999, positionDigits = 4, maximumDurationMs = Int.MAX_VALUE.toLong()),
    ;

    companion object {
        fun parseIdentification(identification: String): TCodeVersion {
            val match = IDENTIFICATION.matchEntire(identification.trim())
                ?: throw TCodeProtocolException(
                    TCodeErrorCode.UNSUPPORTED_VERSION,
                    "Unsupported TCode identification: $identification",
                )
            return entries.firstOrNull { it.wireName == match.groupValues[1] }
                ?: throw TCodeProtocolException(
                    TCodeErrorCode.UNSUPPORTED_VERSION,
                    "Unsupported TCode version: ${match.groupValues[1]}",
                )
        }

        private val IDENTIFICATION = Regex("(?i)TCode\\s+v(\\d+\\.\\d+)")
    }
}

object TCodeVersionNegotiator {
    fun queryBytes(): ByteArray = VERSION_QUERY.toByteArray(StandardCharsets.US_ASCII)

    fun resolve(
        declaredVersion: TCodeVersion?,
        identification: String,
    ): TCodeVersion {
        val detectedVersion = TCodeVersion.parseIdentification(identification)
        if (declaredVersion != null && declaredVersion != detectedVersion) {
            throw TCodeProtocolException(
                TCodeErrorCode.VERSION_MISMATCH,
                "Declared TCode ${declaredVersion.wireName} does not match device ${detectedVersion.wireName}",
            )
        }
        return detectedVersion
    }

    private const val VERSION_QUERY = "D1\n"
}

enum class TCodeAxisKind {
    LINEAR,
    ROTATION,
    VIBRATION,
    AUXILIARY,
}

data class TCodeAxisDescriptor(
    val id: AxisId,
    val kind: TCodeAxisKind,
    val name: String,
    val versions: Set<TCodeVersion>,
)

object TCodeAxisRegistry {
    val axes: List<TCodeAxisDescriptor> = listOf(
        axis("L0", TCodeAxisKind.LINEAR, "Stroke", allVersions()),
        axis("L1", TCodeAxisKind.LINEAR, "Surge", allVersions()),
        axis("L2", TCodeAxisKind.LINEAR, "Sway", allVersions()),
        axis("L3", TCodeAxisKind.LINEAR, "Legacy linear 3", setOf(TCodeVersion.V0_2)),
        axis("R0", TCodeAxisKind.ROTATION, "Twist", allVersions()),
        axis("R1", TCodeAxisKind.ROTATION, "Roll", allVersions()),
        axis("R2", TCodeAxisKind.ROTATION, "Pitch", allVersions()),
        axis("V0", TCodeAxisKind.VIBRATION, "Vibration 1", allVersions()),
        axis("V1", TCodeAxisKind.VIBRATION, "Vibration 2", allVersions()),
        axis("V2", TCodeAxisKind.VIBRATION, "Vibration 3", modernVersions()),
        axis("V3", TCodeAxisKind.VIBRATION, "Vibration 4", modernVersions()),
        axis("A0", TCodeAxisKind.AUXILIARY, "Valve", modernVersions()),
        axis("A1", TCodeAxisKind.AUXILIARY, "Suction", modernVersions()),
        axis("A2", TCodeAxisKind.AUXILIARY, "Lubrication", modernVersions()),
        axis("A3", TCodeAxisKind.AUXILIARY, "Auxiliary", modernVersions()),
    )

    fun find(axis: AxisId, version: TCodeVersion): TCodeAxisDescriptor? =
        axes.firstOrNull { it.id == axis && version in it.versions }

    fun require(axis: AxisId, version: TCodeVersion): TCodeAxisDescriptor =
        find(axis, version) ?: throw TCodeProtocolException(
            TCodeErrorCode.UNKNOWN_AXIS,
            "Axis ${axis.value} is not registered for TCode ${version.wireName}",
        )

    fun forVersion(version: TCodeVersion): List<TCodeAxisDescriptor> =
        axes.filter { version in it.versions }

    private fun axis(
        id: String,
        kind: TCodeAxisKind,
        name: String,
        versions: Set<TCodeVersion>,
    ) = TCodeAxisDescriptor(AxisId(id), kind, name, versions)

    private fun allVersions() = TCodeVersion.entries.toSet()
    private fun modernVersions() = setOf(TCodeVersion.V0_3, TCodeVersion.V0_4)
}

enum class TCodeErrorCode {
    UNSUPPORTED_VERSION,
    VERSION_MISMATCH,
    UNKNOWN_AXIS,
    POSITION_OUT_OF_RANGE,
    NEGATIVE_DURATION,
    DURATION_OUT_OF_RANGE,
    EMPTY_FRAME,
}

class TCodeProtocolException(
    val code: TCodeErrorCode,
    message: String,
) : IllegalArgumentException(message)
