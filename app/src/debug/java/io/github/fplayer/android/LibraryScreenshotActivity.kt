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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.feature.library.LibraryContentFilter
import io.github.fplayer.feature.library.LibraryFolderHeader
import io.github.fplayer.feature.library.LibraryGridItem
import io.github.fplayer.feature.library.LibraryGridScreen
import io.github.fplayer.feature.library.LibraryGridStateMachine
import io.github.fplayer.feature.library.LibraryLayout

class LibraryScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        showOnLockScreen()
        val landscape = intent.getBooleanExtra(EXTRA_LANDSCAPE, false)
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        val width = intent.getIntExtra(EXTRA_WIDTH_DP, if (landscape) 960 else 600).coerceIn(320, 960)
        val height = intent.getIntExtra(EXTRA_HEIGHT_DP, if (landscape) 600 else 960).coerceIn(320, 960)
        val initialLayout = if (intent.getBooleanExtra(EXTRA_DOUBLE_COLUMN, false)) {
            LibraryLayout.DOUBLE_COLUMN
        } else {
            LibraryLayout.GRID
        }
        val state = screenshotState(initialLayout)

        setContent {
            MaterialTheme(colorScheme = ScreenshotColors) {
                var snapshot by remember { mutableStateOf(state.snapshot()) }
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LibraryGridScreen(
                        snapshot = snapshot,
                        jumpTarget = null,
                        adaptiveGridColumns = LibraryGridStateMachine.adaptiveGridColumns(width),
                        onLayoutChanged = { layout ->
                            state.setLayout(layout)
                            snapshot = state.snapshot()
                        },
                        onFilterChanged = { filter ->
                            state.setContentFilter(filter)
                            snapshot = state.snapshot()
                        },
                        onOpenMedia = {},
                        onJumpConsumed = {},
                        onJumpToJustWatched = {},
                        modifier = Modifier.size(width.dp, height.dp),
                    )
                }
            }
        }
    }

    private fun showOnLockScreen() {
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
    }

    private fun screenshotState(layout: LibraryLayout): LibraryGridStateMachine {
        val items = List(2_001) { index ->
            LibraryGridItem(
                mediaId = MediaId("sample-$index"),
                title = "Sample video ${index + 1}",
                folderName = "Sample folder",
                durationMs = 83_000L + index * 1_000L,
                progressPermille = if (index == 4) 420 else 0,
                hasScript = index % 3 != 1,
            )
        }
        return LibraryGridStateMachine(
            folder = LibraryFolderHeader(
                id = "sample-folder",
                displayName = "Sample folder",
                path = "/Sample folder",
                directMediaCount = items.size,
                descendantMediaCount = items.size,
            ),
            items = items,
            layout = layout,
            contentFilter = LibraryContentFilter.ALL_VIDEOS,
        ).apply {
            openGridFromFeed(MediaId("sample-4"))
        }
    }

    private companion object {
        const val EXTRA_WIDTH_DP = "widthDp"
        const val EXTRA_HEIGHT_DP = "heightDp"
        const val EXTRA_LANDSCAPE = "landscape"
        const val EXTRA_DOUBLE_COLUMN = "doubleColumn"

        val ScreenshotColors = darkColorScheme(
            primary = Color(0xFF45C7C7),
            onPrimary = Color(0xFF062020),
            secondary = Color(0xFFE3B85C),
            background = Color(0xFF0B0D0F),
            onBackground = Color(0xFFE7EAEC),
            surface = Color(0xFF13171A),
            onSurface = Color(0xFFE7EAEC),
            surfaceVariant = Color(0xFF20262A),
            onSurfaceVariant = Color(0xFFBBC3C8),
            outlineVariant = Color(0xFF30383D),
        )
    }
}
