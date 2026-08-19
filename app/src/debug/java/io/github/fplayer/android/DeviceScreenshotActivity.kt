package io.github.fplayer.android

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.fplayer.feature.device.DeviceConfigurationContent
import io.github.fplayer.feature.device.DeviceConnectionStatus
import io.github.fplayer.feature.device.DeviceDiagnostic
import io.github.fplayer.feature.device.DeviceUiState
import io.github.fplayer.feature.device.NetworkConnectionSettings

class DeviceScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        requestedOrientation = if (intent.getBooleanExtra(EXTRA_LANDSCAPE, false)) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        val width = intent.getIntExtra(EXTRA_WIDTH_DP, 360).coerceIn(320, 960)
        val height = intent.getIntExtra(EXTRA_HEIGHT_DP, 800).coerceIn(320, 960)
        val showConfirmation = intent.getBooleanExtra(EXTRA_CONFIRMATION, false)
        val baseState = DeviceUiState(
            network = NetworkConnectionSettings(host = "device.local", port = "8000"),
            diagnostics = listOf(DeviceDiagnostic("READY", "Profile validated")),
        )
        val state = if (showConfirmation) {
            baseState.copy(connectionStatus = DeviceConnectionStatus.CONNECTED)
        } else {
            baseState
        }

        setContent {
            MaterialTheme(colorScheme = ScreenshotColors) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    DeviceConfigurationContent(
                        state = state,
                        modifier = Modifier.size(width.dp, height.dp),
                        pendingTestPlan = if (showConfirmation) state.buildSafeTestPlan().getOrThrow() else null,
                        onAction = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val EXTRA_WIDTH_DP = "widthDp"
        const val EXTRA_HEIGHT_DP = "heightDp"
        const val EXTRA_LANDSCAPE = "landscape"
        const val EXTRA_CONFIRMATION = "confirmation"

        val ScreenshotColors = darkColorScheme(
            primary = Color(0xFF45C7C7),
            secondary = Color(0xFFE3B85C),
            background = Color(0xFF0B0D0F),
            surface = Color(0xFF13171A),
            surfaceVariant = Color(0xFF20262A),
            outlineVariant = Color(0xFF30383D),
            error = Color(0xFFFF6B6B),
        )
    }
}
