package io.github.fplayer.feature.device

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fplayer.core.device.TransportType
import io.github.fplayer.core.model.AxisId
import java.util.concurrent.Executor

sealed interface DeviceUiAction {
    data class SelectTransport(val transportType: TransportType) : DeviceUiAction
    data class UpdateHost(val value: String) : DeviceUiAction
    data class UpdatePort(val value: String) : DeviceUiAction
    data class UpdateWebSocketPath(val value: String) : DeviceUiAction
    data class UpdateSecureWebSocket(val enabled: Boolean) : DeviceUiAction
    data object Discover : DeviceUiAction
    data class SelectDevice(val id: String) : DeviceUiAction
    data object ConnectOrDisconnect : DeviceUiAction
    data object EmergencyStop : DeviceUiAction
    data class SetAxisEnabled(val axis: AxisId, val enabled: Boolean) : DeviceUiAction
    data class SetAxisRange(val axis: AxisId, val minimum: Int, val maximum: Int) : DeviceUiAction
    data class SetAxisReversed(val axis: AxisId, val reversed: Boolean) : DeviceUiAction
    data class SelectTestAxis(val axis: AxisId) : DeviceUiAction
    data class SetTestAmplitude(val amplitude: Int) : DeviceUiAction
    data object RequestSafeTest : DeviceUiAction
}

@Composable
fun DeviceConfigurationRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by rememberSaveable(stateSaver = DeviceUiStateSaver) { mutableStateOf(DeviceUiState()) }
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingTestPlan by remember { mutableStateOf<SafeTestPlan?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val mainExecutor = remember { Executor { command -> mainHandler.post(command) } }
    val session = remember {
        DeviceSession(
            backend = AndroidDeviceBackend(context),
            callbackExecutor = mainExecutor,
            eventSink = { event -> state = state.reduce(event) },
        )
    }
    DisposableEffect(session) {
        onDispose(session::close)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (result.values.all { it }) {
            action?.invoke()
        } else {
            state = state.appendDiagnostic(
                DeviceDiagnostic("PERMISSION_DENIED", "Permission denied", isError = true),
            )
        }
    }

    fun runWithPermissions(action: () -> Unit) {
        val permissions = requiredPermissions(state.selectedTransport)
        val missing = permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    DeviceConfigurationContent(
        state = state,
        modifier = modifier,
        pendingTestPlan = pendingTestPlan,
        onDismissSafeTest = { pendingTestPlan = null },
        onConfirmSafeTest = { plan ->
            pendingTestPlan = null
            session.runSafeTest(plan)
        },
        onAction = { action ->
            when (action) {
                is DeviceUiAction.SelectTransport -> state = state.withTransport(action.transportType)
                is DeviceUiAction.UpdateHost -> state = state.copy(network = state.network.copy(host = action.value))
                is DeviceUiAction.UpdatePort -> state = state.copy(network = state.network.copy(port = action.value.filter(Char::isDigit)))
                is DeviceUiAction.UpdateWebSocketPath -> state = state.copy(network = state.network.copy(webSocketPath = action.value))
                is DeviceUiAction.UpdateSecureWebSocket -> state = state.copy(network = state.network.copy(secureWebSocket = action.enabled))
                DeviceUiAction.Discover -> runWithPermissions { session.discover(state.selectedTransport) }
                is DeviceUiAction.SelectDevice -> state = state.copy(selectedDeviceId = action.id)
                DeviceUiAction.ConnectOrDisconnect -> {
                    if (state.connectionStatus == DeviceConnectionStatus.CONNECTED ||
                        state.connectionStatus == DeviceConnectionStatus.CONNECTING
                    ) {
                        session.disconnect()
                    } else {
                        state.buildConnectionRequest()
                            .onSuccess { request -> runWithPermissions { session.connect(request) } }
                            .onFailure { error ->
                                state = state.appendDiagnostic(
                                    validationDiagnostic(error.message ?: "PROFILE_INVALID"),
                                )
                            }
                    }
                }
                DeviceUiAction.EmergencyStop -> session.emergencyStop()
                is DeviceUiAction.SetAxisEnabled -> state = state.withAxisEnabled(action.axis, action.enabled)
                is DeviceUiAction.SetAxisRange -> state = state.withAxisRange(action.axis, action.minimum, action.maximum)
                is DeviceUiAction.SetAxisReversed -> state = state.withAxisReversed(action.axis, action.reversed)
                is DeviceUiAction.SelectTestAxis -> state = state.withTestAxis(action.axis)
                is DeviceUiAction.SetTestAmplitude -> state = state.withTestAmplitude(action.amplitude)
                DeviceUiAction.RequestSafeTest -> state.buildSafeTestPlan()
                    .onSuccess { pendingTestPlan = it }
                    .onFailure { error ->
                        state = state.appendDiagnostic(
                            validationDiagnostic(error.message ?: "SAFE_TEST_INVALID"),
                        )
                    }
            }
        },
    )
}

@Composable
fun DeviceConfigurationContent(
    state: DeviceUiState,
    modifier: Modifier = Modifier,
    pendingTestPlan: SafeTestPlan? = null,
    onDismissSafeTest: () -> Unit = {},
    onConfirmSafeTest: (SafeTestPlan) -> Unit = {},
    onAction: (DeviceUiAction) -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wideLayout = maxWidth >= 840.dp
            Column(modifier = Modifier.fillMaxSize()) {
                DeviceHeader(state, onAction)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (wideLayout) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(0.44f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                        ) {
                            ConnectionSection(state, onAction)
                            DiagnosticsSection(state)
                        }
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Column(
                            modifier = Modifier
                                .weight(0.56f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                        ) {
                            AxisSection(state, onAction)
                            SafeTestSection(state, onAction)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        ConnectionSection(state, onAction)
                        SectionDivider()
                        AxisSection(state, onAction)
                        SectionDivider()
                        SafeTestSection(state, onAction)
                        SectionDivider()
                        DiagnosticsSection(state)
                    }
                }
            }
        }
    }
    pendingTestPlan?.let { plan ->
        SafeTestConfirmationDialog(
            state = state,
            plan = plan,
            onDismiss = onDismissSafeTest,
            onConfirm = { onConfirmSafeTest(plan) },
        )
    }
}

@Composable
private fun DeviceHeader(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit) {
    val canDisconnect = state.connectionStatus == DeviceConnectionStatus.CONNECTED ||
        state.connectionStatus == DeviceConnectionStatus.CONNECTING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "设备",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = connectionStatusLabel(state.connectionStatus),
                style = MaterialTheme.typography.bodySmall,
                color = connectionStatusColor(state.connectionStatus),
            )
        }
        if (state.connectionStatus == DeviceConnectionStatus.CONNECTING) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        if (state.connectionStatus == DeviceConnectionStatus.CONNECTED) {
            IconButton(onClick = { onAction(DeviceUiAction.EmergencyStop) }) {
                Icon(
                    imageVector = Icons.Outlined.StopCircle,
                    contentDescription = "紧急停止设备",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(
            onClick = { onAction(DeviceUiAction.ConnectOrDisconnect) },
        ) {
            Icon(
                imageVector = if (canDisconnect) {
                    Icons.Outlined.LinkOff
                } else {
                    Icons.Outlined.Link
                },
                contentDescription = if (canDisconnect) "断开设备" else "连接设备",
                tint = if (canDisconnect) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun ConnectionSection(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit) {
    SectionTitle(Icons.Outlined.Cable, "连接")
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 600.dp) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TransportType.entries.chunked(2).forEach { rowTransports ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowTransports.forEach { transport ->
                            TransportChip(
                                state = state,
                                transport = transport,
                                onAction = onAction,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TransportType.entries) { transport ->
                    TransportChip(state, transport, onAction)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (state.needsDiscovery) {
        OutlinedButton(
            onClick = { onAction(DeviceUiAction.Discover) },
            enabled = state.canEditProfile && !state.isDiscovering,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
        ) {
            if (state.isDiscovering) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (state.selectedTransport == TransportType.BLUETOOTH_SPP) "读取已配对设备" else "发现设备")
        }
        DeviceSelectionList(state, onAction)
    } else {
        NetworkFields(state, onAction)
    }
}

@Composable
private fun TransportChip(
    state: DeviceUiState,
    transport: TransportType,
    onAction: (DeviceUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = state.selectedTransport == transport,
        onClick = { onAction(DeviceUiAction.SelectTransport(transport)) },
        enabled = state.canEditProfile,
        label = { Text(transportLabel(transport), maxLines = 1) },
        leadingIcon = if (state.selectedTransport == transport) {
            { Icon(transportIcon(transport), contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
private fun NetworkFields(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 440.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HostField(state, onAction, Modifier.fillMaxWidth())
                PortField(state, onAction, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HostField(state, onAction, Modifier.weight(1f))
                PortField(state, onAction, Modifier.width(112.dp))
            }
        }
    }
    if (state.selectedTransport == TransportType.WEBSOCKET) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.network.webSocketPath,
            onValueChange = { onAction(DeviceUiAction.UpdateWebSocketPath(it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canEditProfile,
            label = { Text("路径") },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TLS", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.network.secureWebSocket,
                onCheckedChange = { onAction(DeviceUiAction.UpdateSecureWebSocket(it)) },
                enabled = state.canEditProfile,
            )
        }
    }
}

@Composable
private fun HostField(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = state.network.host,
        onValueChange = { onAction(DeviceUiAction.UpdateHost(it)) },
        modifier = modifier,
        enabled = state.canEditProfile,
        label = { Text("主机") },
        singleLine = true,
    )
}

@Composable
private fun PortField(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = state.network.port,
        onValueChange = { onAction(DeviceUiAction.UpdatePort(it)) },
        modifier = modifier,
        enabled = state.canEditProfile,
        label = { Text("端口") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun DeviceSelectionList(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit) {
    if (!state.isDiscovering && state.discoveredDevices.isEmpty()) {
        Text(
            text = "未选择设备",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    state.discoveredDevices.forEach { device ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = state.selectedDeviceId == device.id,
                    enabled = state.canEditProfile,
                    role = Role.RadioButton,
                    onClick = { onAction(DeviceUiAction.SelectDevice(device.id)) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.selectedDeviceId == device.id,
                onClick = null,
                enabled = state.canEditProfile,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(device.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    device.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            device.signalStrength?.let {
                Text("$it dBm", style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AxisSection(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit) {
    SectionTitle(Icons.Outlined.Tune, "轴限制")
    state.axes.forEach { axis ->
        AxisRow(axis, state.canEditProfile, onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AxisRow(
    axis: AxisUiSettings,
    editable: Boolean,
    onAction: (DeviceUiAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = axis.enabled,
                onCheckedChange = { onAction(DeviceUiAction.SetAxisEnabled(axis.id, it)) },
                enabled = editable,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("${axis.id.value}  ${axis.name}", style = MaterialTheme.typography.bodyLarge)
                if (axis.enabled) {
                    Text(
                        "${axis.minimum} - ${axis.maximum}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (axis.enabled) {
                Text("反转", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = axis.reversed,
                    onCheckedChange = { onAction(DeviceUiAction.SetAxisReversed(axis.id, it)) },
                    enabled = editable,
                )
            }
        }
        if (axis.enabled) {
            RangeSlider(
                value = axis.minimum.toFloat()..axis.maximum.toFloat(),
                onValueChange = { range ->
                    onAction(
                        DeviceUiAction.SetAxisRange(
                            axis.id,
                            range.start.toInt(),
                            range.endInclusive.toInt(),
                        ),
                    )
                },
                valueRange = 0f..100f,
                steps = 99,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun SafeTestSection(state: DeviceUiState, onAction: (DeviceUiAction) -> Unit) {
    SectionTitle(Icons.Outlined.PlayArrow, "安全测试")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.enabledAxes) { axis ->
            FilterChip(
                selected = state.testAxis == axis.id,
                onClick = { onAction(DeviceUiAction.SelectTestAxis(axis.id)) },
                enabled = !state.testInProgress,
                label = { Text(axis.id.value) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    val maximumAmplitude = state.maximumSafeTestAmplitude().coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("幅度", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("±${state.testAmplitude}%", color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = state.testAmplitude.toFloat(),
        onValueChange = { onAction(DeviceUiAction.SetTestAmplitude(it.toInt())) },
        valueRange = 1f..maximumAmplitude.coerceAtLeast(2).toFloat(),
        steps = (maximumAmplitude - 2).coerceAtLeast(0),
        enabled = !state.testInProgress && maximumAmplitude > 1,
    )
    Button(
        onClick = { onAction(DeviceUiAction.RequestSafeTest) },
        enabled = state.connectionStatus == DeviceConnectionStatus.CONNECTED &&
            !state.testInProgress && state.maximumSafeTestAmplitude() >= 1,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
    ) {
        if (state.testInProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (state.testInProgress) "测试中" else "受限往返测试")
    }
}

@Composable
private fun DiagnosticsSection(state: DeviceUiState) {
    SectionTitle(Icons.Outlined.ErrorOutline, "诊断")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = state.transportDiagnostics,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.diagnostics.isEmpty()) {
        Text(
            text = "无故障",
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        state.diagnostics.asReversed().forEach { diagnostic ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = if (diagnostic.isError) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (diagnostic.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(diagnostic.code, style = MaterialTheme.typography.labelMedium)
                    Text(
                        diagnostic.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SafeTestConfirmationDialog(
    state: DeviceUiState,
    plan: SafeTestPlan,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val axis = state.axes.first { it.id == plan.axis }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { Text("确认设备动作") },
        text = {
            Text(
                "${plan.axis.value} 将在 ${axis.minimum}-${axis.maximum} 限制内执行一次往返，幅度为 ±${state.testAmplitude}%。",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(6.dp)) {
                Text("确认执行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(20.dp))
}

private fun DeviceUiState.reduce(event: DeviceSessionEvent): DeviceUiState = when (event) {
    DeviceSessionEvent.DiscoveryStarted -> copy(isDiscovering = true, discoveredDevices = emptyList(), selectedDeviceId = null)
    is DeviceSessionEvent.DiscoveryFinished -> copy(
        isDiscovering = false,
        discoveredDevices = event.devices,
        selectedDeviceId = event.devices.singleOrNull()?.id,
    )
    is DeviceSessionEvent.ConnectionChanged -> {
        val updated = copy(
            connectionStatus = event.status,
            transportDiagnostics = event.diagnostics,
            testInProgress = if (event.status == DeviceConnectionStatus.CONNECTED) testInProgress else false,
        )
        event.failure?.let { updated.appendDiagnostic(failureDiagnostic(it.code)) } ?: updated
    }
    is DeviceSessionEvent.Diagnostic -> appendDiagnostic(event.diagnostic)
    is DeviceSessionEvent.TestProgress -> copy(testInProgress = event.running)
}

private fun requiredPermissions(transportType: TransportType): List<String> = when {
    Build.VERSION.SDK_INT >= 31 && transportType == TransportType.BLE -> listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    Build.VERSION.SDK_INT >= 31 && transportType == TransportType.BLUETOOTH_SPP -> listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    Build.VERSION.SDK_INT < 31 && transportType == TransportType.BLE -> listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    else -> emptyList()
}

private fun validationDiagnostic(code: String): DeviceDiagnostic = DeviceDiagnostic(
    code = code,
    message = when (code) {
        "AXIS_REQUIRED" -> "Enable at least one axis"
        "NETWORK_HOST_REQUIRED" -> "Host is required"
        "NETWORK_PORT_INVALID" -> "Port must be between 1 and 65535"
        "WEBSOCKET_PATH_INVALID" -> "WebSocket path is invalid"
        "DEVICE_SELECTION_REQUIRED" -> "Select a discovered device"
        "DEVICE_NOT_CONNECTED" -> "Connect the device before testing"
        "TEST_RANGE_TOO_NARROW" -> "Selected axis range is too narrow"
        "TEST_AMPLITUDE_INVALID" -> "Test amplitude exceeds the axis limit"
        else -> "Device profile is invalid"
    },
    isError = true,
)

private fun connectionStatusLabel(status: DeviceConnectionStatus): String = when (status) {
    DeviceConnectionStatus.DISCONNECTED -> "未连接"
    DeviceConnectionStatus.CONNECTING -> "连接中"
    DeviceConnectionStatus.CONNECTED -> "已连接"
    DeviceConnectionStatus.FAILED -> "连接失败"
}

@Composable
private fun connectionStatusColor(status: DeviceConnectionStatus): Color = when (status) {
    DeviceConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    DeviceConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun transportLabel(transportType: TransportType): String = when (transportType) {
    TransportType.TCP -> "TCP"
    TransportType.UDP -> "UDP"
    TransportType.WEBSOCKET -> "WebSocket"
    TransportType.BLE -> "BLE"
    TransportType.BLUETOOTH_SPP -> "SPP 经典蓝牙"
    TransportType.USB_SERIAL -> "USB"
}

private fun transportIcon(transportType: TransportType): ImageVector = when (transportType) {
    TransportType.TCP,
    TransportType.UDP,
    TransportType.WEBSOCKET,
    -> Icons.Outlined.Wifi
    TransportType.BLE,
    TransportType.BLUETOOTH_SPP,
    -> Icons.Outlined.Bluetooth
    TransportType.USB_SERIAL -> Icons.Outlined.Usb
}

private val DeviceUiStateSaver = androidx.compose.runtime.saveable.Saver<DeviceUiState, List<Any?>>(
    save = { state ->
        listOf(
            state.selectedTransport.name,
            state.network.host,
            state.network.port,
            state.network.webSocketPath,
            state.network.secureWebSocket,
            state.axes.map { listOf(it.id.value, it.enabled, it.minimum, it.maximum, it.reversed) },
            state.testAxis.value,
            state.testAmplitude,
        )
    },
    restore = { saved ->
        val defaults = DeviceUiState()
        @Suppress("UNCHECKED_CAST")
        val axes = (saved[5] as List<List<Any?>>).map { values ->
            val id = AxisId(values[0] as String)
            defaults.axes.first { it.id == id }.copy(
                enabled = values[1] as Boolean,
                minimum = values[2] as Int,
                maximum = values[3] as Int,
                reversed = values[4] as Boolean,
            )
        }
        defaults.copy(
            selectedTransport = TransportType.valueOf(saved[0] as String),
            network = NetworkConnectionSettings(
                host = saved[1] as String,
                port = saved[2] as String,
                webSocketPath = saved[3] as String,
                secureWebSocket = saved[4] as Boolean,
            ),
            axes = axes,
            testAxis = AxisId(saved[6] as String),
            testAmplitude = saved[7] as Int,
        )
    },
)

@Preview(name = "Phone portrait", widthDp = 360, heightDp = 800)
@Preview(name = "Phone landscape", widthDp = 800, heightDp = 360)
@Preview(name = "Tablet portrait", widthDp = 800, heightDp = 1280)
@Preview(name = "Tablet landscape", widthDp = 1280, heightDp = 800)
@Composable
private fun DeviceConfigurationPreview() {
    MaterialTheme {
        DeviceConfigurationContent(
            state = DeviceUiState(
                selectedTransport = TransportType.BLE,
                discoveredDevices = listOf(
                    DiscoveredDeviceUi("preview", "TCODE-ESP32", "xx:xx:xx:12:34:56", -58),
                ),
                selectedDeviceId = "preview",
                diagnostics = listOf(DeviceDiagnostic("READY", "Profile validated")),
            ),
            onAction = {},
        )
    }
}
