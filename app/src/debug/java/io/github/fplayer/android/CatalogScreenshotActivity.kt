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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.fplayer.core.model.MediaId
import io.github.fplayer.feature.library.LibraryAlbumView
import io.github.fplayer.feature.library.LibraryCatalogFolder
import io.github.fplayer.feature.library.LibraryCatalogInput
import io.github.fplayer.feature.library.LibraryCatalogMedia
import io.github.fplayer.feature.library.LibraryCatalogScreen
import io.github.fplayer.feature.library.LibraryCatalogStateMachine
import io.github.fplayer.feature.library.LibraryGridStateMachine

class CatalogScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        showOnLockScreen()
        val landscape = intent.getBooleanExtra(EXTRA_LANDSCAPE, false)
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        val width = intent.getIntExtra(EXTRA_WIDTH_DP, if (landscape) 800 else 600).coerceIn(320, 960)
        val height = intent.getIntExtra(EXTRA_HEIGHT_DP, if (landscape) 480 else 960).coerceIn(320, 960)
        val state = screenshotState()

        setContent {
            MaterialTheme(colorScheme = ScreenshotColors) {
                var snapshot by remember { mutableStateOf(state.snapshot()) }
                fun refresh() { snapshot = state.snapshot() }
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LibraryCatalogScreen(
                        snapshot = snapshot,
                        adaptiveGridColumns = LibraryGridStateMachine.adaptiveGridColumns(width),
                        onAlbumViewChanged = { state.setAlbumView(it); refresh() },
                        onCollectionChanged = { state.setCollection(it); refresh() },
                        onLayoutChanged = { state.setLayout(it); refresh() },
                        onSortChanged = { state.setSort(it); refresh() },
                        onSearchChanged = { state.setSearchQuery(it); refresh() },
                        onOpenFolder = { state.openFolder(it.id); refresh() },
                        onCloseFolder = { state.closeFolder(); refresh() },
                        onOpenMedia = { state.selectMedia(it.mediaId); refresh() },
                        onRequestDelete = { state.requestDelete(it); refresh() },
                        onCancelDelete = { state.cancelDelete(); refresh() },
                        onConfirmDelete = { state.confirmDelete(); refresh() },
                        modifier = Modifier.size(width.dp, height.dp).clipToBounds(),
                    )
                }
            }
        }
    }

    private fun screenshotState(): LibraryCatalogStateMachine {
        val folders = List(18) { index ->
            LibraryCatalogFolder(
                id = "folder-$index",
                sourceId = "sample-source",
                displayName = "Sample folder ${index + 1}",
                path = "/Sample/folder-${index + 1}",
                directMediaCount = if (index % 5 == 0) 0 else 12,
                descendantMediaCount = if (index % 5 == 0) 0 else 12,
            )
        }
        val media = List(72) { index ->
            LibraryCatalogMedia(
                mediaId = MediaId("sample-$index"),
                sourceId = "sample-source",
                folderId = "folder-${index % 18}",
                title = "Sample video ${index + 1}",
                normalizedTitle = "sample video ${index + 1}",
                path = "/Sample/folder-${index % 18 + 1}/video-${index + 1}.mp4",
                folderName = "Sample folder ${index % 18 + 1}",
                durationMs = 75_000L + index * 1_000L,
                sizeBytes = 8_000_000L + index * 10_000L,
                modifiedAtEpochMs = 1_700_000_000_000L + index,
                width = 1_920,
                height = 1_080,
                probeStatus = "READY",
                progressPermille = if (index % 7 == 0) 420 else 0,
                lastPlayedAtEpochMs = if (index % 4 == 0) 1_700_000_000_000L + index else null,
                hasScript = index % 3 != 1,
                liked = index % 11 == 0,
                favorite = index % 13 == 0,
                disliked = index % 17 == 0,
            )
        }
        return LibraryCatalogStateMachine(LibraryCatalogInput(folders, media)).apply {
            if (intent.getBooleanExtra(EXTRA_ALL_VIDEOS, false)) {
                setAlbumView(LibraryAlbumView.ALL_VIDEOS)
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

    private companion object {
        const val EXTRA_WIDTH_DP = "widthDp"
        const val EXTRA_HEIGHT_DP = "heightDp"
        const val EXTRA_LANDSCAPE = "landscape"
        const val EXTRA_ALL_VIDEOS = "allVideos"

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
