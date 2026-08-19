package io.github.fplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.script.LatencyCompensationConfig
import io.github.fplayer.core.script.LatencyEstimate
import io.github.fplayer.core.script.LatencyMeasurementState
import io.github.fplayer.core.script.ManualAxisTarget
import io.github.fplayer.core.script.ScriptAxisOutputRange
import io.github.fplayer.core.script.ScriptOutputLimits
import io.github.fplayer.core.script.ScriptPlaybackSettingsSnapshot
import io.github.fplayer.core.script.effectiveOffsetMs
import kotlin.math.roundToInt

private const val MINIMUM_MANUAL_OFFSET_MS = -10_000L
private const val MAXIMUM_MANUAL_OFFSET_MS = 10_000L
private val DEFAULT_AXIS = AxisId("L0")

data class ScriptPlaybackSettingsState(
    val latency: LatencyCompensationConfig = LatencyCompensationConfig(),
    val estimate: LatencyEstimate = LatencyEstimate(
        state = LatencyMeasurementState.UNMEASURED,
        automaticOffsetMs = 0L,
        medianRoundTripMs = null,
        sampleCount = 0,
        measuredAtMonotonicMs = null,
    ),
    val outputLimits: ScriptOutputLimits = ScriptOutputLimits(emptyMap()),
    val selectedManualAxis: AxisId = DEFAULT_AXIS,
    val manualPosition: Int = 50,
) {
    fun snapshot(): ScriptPlaybackSettingsSnapshot = ScriptPlaybackSettingsSnapshot(
        latency = latency,
        estimate = estimate,
        outputLimits = outputLimits,
        selectedManualAxis = selectedManualAxis,
        manualPosition = manualPosition,
    )
}

val ScriptPlaybackSettingsStateSaver: Saver<ScriptPlaybackSettingsState, List<Any?>> = Saver(
    save = { state ->
        listOf(
            state.latency.automaticEnabled,
            state.latency.manualOffsetMs,
            state.outputLimits.perAxis.entries
                .sortedBy { it.key.value }
                .joinToString(";") { (axis, range) ->
                    "${axis.value},${range.minimum},${range.maximum}"
                },
            state.selectedManualAxis.value,
            state.manualPosition,
            state.estimate.state.name,
            state.estimate.automaticOffsetMs,
            state.estimate.medianRoundTripMs,
            state.estimate.sampleCount,
            state.estimate.measuredAtMonotonicMs,
        )
    },
    restore = { values ->
        val ranges = (values[2] as? String).orEmpty().split(';').mapNotNull { encoded ->
            if (encoded.isBlank()) return@mapNotNull null
            val parts = encoded.split(',')
            if (parts.size != 3) return@mapNotNull null
            val minimum = parts[1].toIntOrNull() ?: return@mapNotNull null
            val maximum = parts[2].toIntOrNull() ?: return@mapNotNull null
            AxisId(parts[0]) to ScriptAxisOutputRange(minimum, maximum)
        }.toMap()
        ScriptPlaybackSettingsState(
            latency = LatencyCompensationConfig().copy(
                automaticEnabled = values[0] as Boolean,
                manualOffsetMs = (values[1] as Number).toLong(),
            ),
            outputLimits = ScriptOutputLimits(ranges),
            selectedManualAxis = AxisId(values[3] as String),
            manualPosition = (values[4] as Number).toInt(),
            estimate = LatencyEstimate(
                state = LatencyMeasurementState.valueOf(values[5] as String),
                automaticOffsetMs = (values[6] as Number).toLong(),
                medianRoundTripMs = (values[7] as Number?)?.toLong(),
                sampleCount = (values[8] as Number).toInt(),
                measuredAtMonotonicMs = (values[9] as Number?)?.toLong(),
            ),
        )
    },
)

sealed interface ScriptPlaybackSettingsAction {
    data class SetAutomaticLatency(val enabled: Boolean) : ScriptPlaybackSettingsAction
    data class SetManualOffsetText(val text: String) : ScriptPlaybackSettingsAction
    data class SetAxisRange(val axis: AxisId, val minimum: Int, val maximum: Int) : ScriptPlaybackSettingsAction
    data class SetManualAxis(val axis: AxisId) : ScriptPlaybackSettingsAction
    data class SetManualPosition(val position: Int) : ScriptPlaybackSettingsAction
    data object ResetLatency : ScriptPlaybackSettingsAction
}

data class ScriptPlaybackDiagnostics(
    val state: LatencyMeasurementState,
    val sampleCount: Int,
    val medianRoundTripMs: Long?,
    val effectiveOffsetMs: Long,
)

fun reduce(
    state: ScriptPlaybackSettingsState,
    action: ScriptPlaybackSettingsAction,
): ScriptPlaybackSettingsState = when (action) {
    is ScriptPlaybackSettingsAction.SetAutomaticLatency -> state.copy(
        latency = state.latency.copy(automaticEnabled = action.enabled),
    )
    is ScriptPlaybackSettingsAction.SetManualOffsetText -> action.text.toLongOrNull()?.let { value ->
        state.copy(
            latency = state.latency.copy(
                manualOffsetMs = value.coerceIn(MINIMUM_MANUAL_OFFSET_MS, MAXIMUM_MANUAL_OFFSET_MS),
            ),
        )
    } ?: state
    is ScriptPlaybackSettingsAction.SetAxisRange -> {
        val first = action.minimum.coerceIn(0, 100)
        val second = action.maximum.coerceIn(0, 100)
        val range = ScriptAxisOutputRange(minOf(first, second), maxOf(first, second))
        state.copy(
            outputLimits = ScriptOutputLimits(state.outputLimits.perAxis + (action.axis to range)),
            manualPosition = if (action.axis == state.selectedManualAxis) {
                state.manualPosition.coerceIn(range.minimum, range.maximum)
            } else {
                state.manualPosition
            },
        )
    }
    is ScriptPlaybackSettingsAction.SetManualAxis -> {
        val range = state.outputLimits.rangeFor(action.axis)
        state.copy(
            selectedManualAxis = action.axis,
            manualPosition = state.manualPosition.coerceIn(range.minimum, range.maximum),
        )
    }
    is ScriptPlaybackSettingsAction.SetManualPosition -> {
        val range = state.outputLimits.rangeFor(state.selectedManualAxis)
        state.copy(manualPosition = action.position.coerceIn(range.minimum, range.maximum))
    }
    ScriptPlaybackSettingsAction.ResetLatency -> state.copy(
        estimate = LatencyEstimate(LatencyMeasurementState.UNMEASURED, 0L, null, 0, null),
    )
}

fun ScriptPlaybackSettingsState.diagnostics(): ScriptPlaybackDiagnostics = ScriptPlaybackDiagnostics(
    state = estimate.state,
    sampleCount = estimate.sampleCount,
    medianRoundTripMs = estimate.medianRoundTripMs,
    effectiveOffsetMs = effectiveOffsetMs(latency, estimate),
)

fun ScriptPlaybackSettingsState.manualAxisTarget(): ManualAxisTarget {
    val range = outputLimits.rangeFor(selectedManualAxis)
    return ManualAxisTarget(selectedManualAxis, manualPosition.coerceIn(range.minimum, range.maximum))
}

@Composable
fun ScriptPlaybackSettingsSurface(
    state: ScriptPlaybackSettingsState,
    onAction: (ScriptPlaybackSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    availableAxes: List<AxisId> = listOf(DEFAULT_AXIS),
    onManualTarget: ((ManualAxisTarget) -> Unit)? = null,
) {
    val axes = (availableAxes + state.outputLimits.perAxis.keys + state.selectedManualAxis)
        .distinct()
        .sortedBy { it.value }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSectionTitle("延迟补偿")
        LatencySettings(state, onAction)
        HorizontalDivider()
        SettingsSectionTitle("轴范围")
        AxisRangeSettings(state, axes, onAction)
        HorizontalDivider()
        SettingsSectionTitle("手动控制")
        ManualControlSettings(state, axes, onAction, onManualTarget)
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun LatencySettings(
    state: ScriptPlaybackSettingsState,
    onAction: (ScriptPlaybackSettingsAction) -> Unit,
) {
    var offsetText by remember(state.latency.manualOffsetMs) {
        mutableStateOf(state.latency.manualOffsetMs.toString())
    }
    val diagnostics = state.diagnostics()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "自动计算网络延迟",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = state.latency.automaticEnabled,
                onCheckedChange = {
                    onAction(ScriptPlaybackSettingsAction.SetAutomaticLatency(it))
                },
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        }
        OutlinedTextField(
            value = offsetText,
            onValueChange = { text ->
                offsetText = text
                onAction(ScriptPlaybackSettingsAction.SetManualOffsetText(text))
            },
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
            label = { Text("手动偏移 (ms)") },
            supportingText = { Text("-10000 到 +10000") },
            // An ASCII keyboard exposes +/-; the reducer still accepts only a
            // complete signed base-10 integer.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            singleLine = true,
        )
        Text(
            "状态 ${diagnostics.state.name} · 样本 ${diagnostics.sampleCount}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "中位 RTT ${diagnostics.medianRoundTripMs?.let { "$it ms" } ?: "--"} · 有效偏移 ${signed(diagnostics.effectiveOffsetMs)} ms",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { onAction(ScriptPlaybackSettingsAction.ResetLatency) },
            modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 48.dp),
        ) {
            Text("重置测量")
        }
    }
}

@Composable
private fun AxisRangeSettings(
    state: ScriptPlaybackSettingsState,
    axes: List<AxisId>,
    onAction: (ScriptPlaybackSettingsAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        axes.forEach { axis ->
            val range = state.outputLimits.rangeFor(axis)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(axis.value, style = MaterialTheme.typography.labelLarge)
                    Text("${range.minimum} .. ${range.maximum}", style = MaterialTheme.typography.bodyMedium)
                }
                RangeSlider(
                    value = range.minimum.toFloat()..range.maximum.toFloat(),
                    onValueChange = { values ->
                        onAction(
                            ScriptPlaybackSettingsAction.SetAxisRange(
                                axis,
                                values.start.roundToInt(),
                                values.endInclusive.roundToInt(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                    valueRange = 0f..100f,
                    steps = 99,
                )
            }
        }
    }
}

@Composable
private fun ManualControlSettings(
    state: ScriptPlaybackSettingsState,
    axes: List<AxisId>,
    onAction: (ScriptPlaybackSettingsAction) -> Unit,
    onManualTarget: ((ManualAxisTarget) -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val range = state.outputLimits.rangeFor(state.selectedManualAxis)
    val sliderEnabled = range.minimum < range.maximum
    val sliderRange = if (sliderEnabled) {
        range.minimum.toFloat()..range.maximum.toFloat()
    } else {
        0f..100f
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("控制轴", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 48.dp),
            ) {
                Text(state.selectedManualAxis.value)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                axes.forEach { axis ->
                    DropdownMenuItem(
                        text = { Text(axis.value) },
                        onClick = {
                            menuExpanded = false
                            onAction(ScriptPlaybackSettingsAction.SetManualAxis(axis))
                        },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }
        }
        Text("位置 ${state.manualPosition}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.manualPosition.toFloat(),
            onValueChange = {
                onAction(ScriptPlaybackSettingsAction.SetManualPosition(it.roundToInt()))
            },
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
            enabled = sliderEnabled,
            valueRange = sliderRange,
            steps = (range.maximum - range.minimum - 1).coerceAtLeast(0),
        )
        Button(
            onClick = { onManualTarget?.invoke(state.manualAxisTarget()) },
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().heightIn(min = 48.dp),
            enabled = onManualTarget != null,
        ) {
            Text("发送")
        }
    }
}

private fun signed(value: Long): String = if (value >= 0L) "+$value" else value.toString()
