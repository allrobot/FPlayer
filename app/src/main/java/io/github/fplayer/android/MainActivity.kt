package io.github.fplayer.android

import android.Manifest
import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.fplayer.feature.device.DeviceConfigurationRoute
import io.github.fplayer.core.index.saf.SafPermissionStore
import io.github.fplayer.core.index.db.FPlayerIndexDatabase
import io.github.fplayer.core.model.AxisId
import io.github.fplayer.core.model.MediaLocator
import io.github.fplayer.feature.feed.PlaybackOverlayAction
import io.github.fplayer.feature.feed.PlaybackOverlayMode
import io.github.fplayer.feature.feed.PlaybackOverlayReducer
import io.github.fplayer.feature.feed.PlaybackOverlayState
import io.github.fplayer.feature.feed.FeedPagingEvent
import io.github.fplayer.feature.feed.PlaybackFeedReducer
import io.github.fplayer.feature.feed.PlaybackFeedState
import io.github.fplayer.feature.feed.PlaybackFeedItem
import io.github.fplayer.feature.feed.FeedMedia
import io.github.fplayer.feature.feed.LongPressPlaybackSettingsStateMachine
import io.github.fplayer.feature.library.HomeSurface
import io.github.fplayer.feature.library.LibraryContentFilter
import io.github.fplayer.feature.library.LibraryFolderHeader
import io.github.fplayer.feature.library.LibraryGridScreen
import io.github.fplayer.feature.library.LibraryGridStateMachine
import io.github.fplayer.feature.library.LibraryJumpTarget
import io.github.fplayer.feature.library.LibraryLayout
import io.github.fplayer.feature.library.LibraryCatalogInput
import io.github.fplayer.feature.library.LibraryCatalogScreen
import io.github.fplayer.feature.library.LibraryCatalogStateMachine
import io.github.fplayer.feature.library.LibraryIndexRepository
import io.github.fplayer.feature.settings.ScriptPlaybackSettingsState
import io.github.fplayer.feature.settings.ScriptPlaybackSettingsStateSaver
import io.github.fplayer.feature.settings.ScriptPlaybackSettingsSurface
import io.github.fplayer.feature.settings.reduce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class MainActivity : ComponentActivity() {
    private var playbackBinder: PlaybackService.LocalBinder? = null
    private var serviceBindingRequested = false
    private var pendingBackgroundPlaybackBinder: PlaybackService.LocalBinder? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val binder = pendingBackgroundPlaybackBinder
        pendingBackgroundPlaybackBinder = null
        if (granted) binder?.enableBackgroundPlayback()
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!serviceBindingRequested) return
            playbackBinder = service as? PlaybackService.LocalBinder
            playbackBinder?.setActivityVisible(true)
            playbackBinder?.let(::onPlaybackBinderConnected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = FPlayerColors) {
                FPlayerApp(
                    onSelectMedia = { mediaId -> playbackBinder?.selectMedia(mediaId) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBindingRequested) {
            val intent = Intent(this, PlaybackService::class.java)
            serviceBindingRequested = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        playbackBinder?.setActivityVisible(false)
        if (serviceBindingRequested) {
            unbindService(serviceConnection)
            serviceBindingRequested = false
        }
        playbackBinder = null
        super.onStop()
    }

    protected fun enableBackgroundPlayback(binder: PlaybackService.LocalBinder) {
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (NotificationPermissionPolicy.requiresRuntimeRequest(Build.VERSION.SDK_INT, permissionGranted)) {
            pendingBackgroundPlaybackBinder = binder
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            binder.enableBackgroundPlayback()
        }
    }

    protected open fun onPlaybackBinderConnected(binder: PlaybackService.LocalBinder) = Unit
}

private enum class AppDestination(val label: String) {
    HOME("首页"),
    LIBRARY("相册"),
    DEVICE("设备"),
    SETTINGS("设置"),
}

@Composable
private fun FPlayerApp(
    onSelectMedia: (io.github.fplayer.core.model.MediaId) -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    val destination = AppDestination.valueOf(destinationName)
    val context = LocalContext.current
    val indexDatabase = remember { FPlayerIndexDatabase.open(context) }
    DisposableEffect(indexDatabase) {
        onDispose { indexDatabase.close() }
    }
    val catalogState = remember { LibraryCatalogStateMachine(LibraryCatalogInput(emptyList(), emptyList())) }
    var catalogSnapshot by remember { mutableStateOf(catalogState.snapshot()) }
    var catalogDrawerSignal by rememberSaveable { mutableStateOf(0) }
    var catalogSearchSignal by rememberSaveable { mutableStateOf(0) }
    fun refreshCatalog() { catalogSnapshot = catalogState.snapshot() }
    LaunchedEffect(indexDatabase) {
        val input = withContext(Dispatchers.IO) {
            LibraryIndexRepository(indexDatabase.indexDao()).load()
        }
        catalogState.replace(input)
        refreshCatalog()
    }
    val libraryState = remember {
        LibraryGridStateMachine(
            folder = LibraryFolderHeader(
                id = "current-folder",
                displayName = "当前文件夹",
                path = "/",
                directMediaCount = 0,
                descendantMediaCount = 0,
            ),
            items = emptyList(),
        )
    }
    var librarySnapshot by remember { mutableStateOf(libraryState.snapshot()) }
    var libraryJumpTarget by remember { mutableStateOf<LibraryJumpTarget?>(null) }
    var scriptPlaybackSettings by rememberSaveable(stateSaver = ScriptPlaybackSettingsStateSaver) {
        mutableStateOf(ScriptPlaybackSettingsState())
    }
    fun refreshLibrary() { librarySnapshot = libraryState.snapshot() }
    fun openCurrentGrid() {
        libraryJumpTarget = libraryState.openGridFromFeed(librarySnapshot.currentMediaId)
        refreshLibrary()
        destinationName = AppDestination.HOME.name
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = {
                            if (item == AppDestination.HOME && destination == AppDestination.HOME) {
                                libraryState.onHomePressed()
                                refreshLibrary()
                            } else {
                                destinationName = item.name
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    AppDestination.HOME -> Icons.Outlined.Home
                                    AppDestination.LIBRARY -> Icons.Outlined.PhotoLibrary
                                    AppDestination.DEVICE -> Icons.Outlined.Devices
                                    AppDestination.SETTINGS -> Icons.Outlined.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (destination) {
            AppDestination.HOME -> when (librarySnapshot.homeSurface) {
                HomeSurface.FOLDER_GRID -> LibraryGridScreen(
                    snapshot = librarySnapshot,
                    jumpTarget = libraryJumpTarget,
                    adaptiveGridColumns = LibraryGridStateMachine.adaptiveGridColumns(
                        LocalConfiguration.current.screenWidthDp,
                    ),
                    onLayoutChanged = { layout ->
                        libraryState.setLayout(layout)
                        refreshLibrary()
                    },
                    onFilterChanged = { filter ->
                        libraryState.setContentFilter(filter)
                        libraryJumpTarget = libraryState.jumpToCurrent()
                        refreshLibrary()
                    },
                    onOpenMedia = { item ->
                        libraryState.openPlayback(item.mediaId)
                        refreshLibrary()
                    },
                    onJumpConsumed = { libraryJumpTarget = null },
                    onJumpToJustWatched = {
                        libraryJumpTarget = libraryState.jumpToJustWatched()
                    },
                    modifier = Modifier.padding(contentPadding),
                )
                HomeSurface.DEFAULT_FEED, HomeSurface.FOLDER_PLAYBACK -> PlaybackFeedScreen(
                    modifier = Modifier.padding(contentPadding),
                    feedItems = catalogSnapshot.media.map { media ->
                        PlaybackFeedItem(
                            media = FeedMedia(media.mediaId, MediaLocator(media.path)),
                            title = media.title,
                            folder = media.folderName,
                            summary = listOfNotNull(
                                media.durationMs?.let { "${it / 1_000}s" },
                                media.width?.let { width -> media.height?.let { height -> "${width}x$height" } },
                            ).joinToString(" / "),
                        )
                    },
                    scriptPlaybackSettings = scriptPlaybackSettings,
                    onOpenSettings = { destinationName = AppDestination.SETTINGS.name },
                    onOpenGrid = ::openCurrentGrid,
                    onOpenDrawer = {
                        catalogDrawerSignal += 1
                        destinationName = AppDestination.LIBRARY.name
                    },
                    onOpenSearch = {
                        catalogSearchSignal += 1
                        destinationName = AppDestination.LIBRARY.name
                    },
                )
            }
            AppDestination.LIBRARY -> LibraryCatalogScreen(
                snapshot = catalogSnapshot,
                adaptiveGridColumns = LibraryGridStateMachine.adaptiveGridColumns(
                    LocalConfiguration.current.screenWidthDp,
                ),
                onAlbumViewChanged = { catalogState.setAlbumView(it); refreshCatalog() },
                onCollectionChanged = { catalogState.setCollection(it); refreshCatalog() },
                onLayoutChanged = { catalogState.setLayout(it); refreshCatalog() },
                onSortChanged = { catalogState.setSort(it); refreshCatalog() },
                onSearchChanged = { catalogState.setSearchQuery(it); refreshCatalog() },
                onOpenFolder = { folder -> catalogState.openFolder(folder.id); refreshCatalog() },
                onCloseFolder = { catalogState.closeFolder(); refreshCatalog() },
                onOpenMedia = { item ->
                    catalogState.selectMedia(item.mediaId)
                    onSelectMedia(item.mediaId)
                    refreshCatalog()
                    destinationName = AppDestination.HOME.name
                },
                onRequestDelete = { mediaId ->
                    catalogState.requestDelete(mediaId)
                    refreshCatalog()
                },
                onCancelDelete = { catalogState.cancelDelete(); refreshCatalog() },
                onConfirmDelete = {
                    catalogState.confirmDelete()
                    refreshCatalog()
                },
                openDrawerSignal = catalogDrawerSignal,
                openSearchSignal = catalogSearchSignal,
                modifier = Modifier.padding(contentPadding),
            )
            AppDestination.DEVICE -> DeviceConfigurationRoute(Modifier.padding(contentPadding))
            AppDestination.SETTINGS -> ScriptPlaybackSettingsSurface(
                state = scriptPlaybackSettings,
                onAction = { action ->
                    scriptPlaybackSettings = reduce(scriptPlaybackSettings, action)
                },
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                availableAxes = listOf(
                    AxisId("L0"), AxisId("L1"), AxisId("L2"),
                    AxisId("R0"), AxisId("R1"), AxisId("R2"),
                ),
            )
        }
    }
}

@Composable
private fun PlaybackFeedScreen(
    modifier: Modifier = Modifier,
    feedItems: List<PlaybackFeedItem>,
    scriptPlaybackSettings: ScriptPlaybackSettingsState,
    onOpenSettings: () -> Unit,
    onOpenGrid: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    var overlay by rememberSaveable(stateSaver = PlaybackOverlaySaver) { mutableStateOf(PlaybackOverlayState()) }
    var feedState by remember { mutableStateOf(PlaybackFeedState()) }
    val feedReducer = remember { PlaybackFeedReducer(pageExtentPx = 1_000f) }
    LaunchedEffect(feedItems) {
        feedState = feedReducer.reduce(feedState, FeedPagingEvent.ReplaceItems(feedItems))
    }
    val context = LocalContext.current
    val playbackSettingsState = remember { LongPressPlaybackSettingsStateMachine() }
    playbackSettingsState.updateScriptSettings(scriptPlaybackSettings.snapshot())
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) runCatching { SafPermissionStore(context).persistReadOnly(uri) }
    }
    fun dispatch(action: PlaybackOverlayAction) {
        overlay = PlaybackOverlayReducer.reduce(overlay, action)
    }
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black).pointerInput(onOpenGrid) {
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = {
                    dx = 0f
                    dy = 0f
                    feedState = feedReducer.reduce(feedState, FeedPagingEvent.Down)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dx += dragAmount.x
                    dy += dragAmount.y
                    feedState = feedReducer.reduce(feedState, FeedPagingEvent.Move(dx, dy))
                },
                onDragEnd = {
                    feedState = feedReducer.reduce(feedState, FeedPagingEvent.Up())
                    if (feedState.paging.outcome == io.github.fplayer.feature.feed.FeedPagingOutcome.OPEN_GRID) {
                        onOpenGrid()
                    } else if (feedState.paging.phase == io.github.fplayer.feature.feed.FeedGesturePhase.SETTLING) {
                        feedState = feedReducer.reduce(feedState, FeedPagingEvent.Settle(1f))
                    }
                },
                onDragCancel = {
                    feedState = feedReducer.reduce(feedState, FeedPagingEvent.Up())
                },
            )
        },
    ) {
        if (feedItems.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("FPlayer", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text("暂无媒体", color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "添加文件夹")
                    Text("添加文件夹", modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else {
            val item = feedState.activeIndex?.let(feedItems::getOrNull)
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(item?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "媒体预览",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (overlay.mode == PlaybackOverlayMode.NORMAL) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OverlayIcon(Icons.Outlined.FolderOpen, "打开抽屉", onOpenDrawer)
                OverlayIcon(Icons.Outlined.GridView, "当前文件夹网格", onOpenGrid)
                OverlayIcon(Icons.Outlined.Search, "搜索", onOpenSearch)
                OverlayIcon(Icons.Outlined.MoreVert, "更多") {
                    dispatch(PlaybackOverlayAction.OpenMore)
                    onOpenSettings()
                }
            }
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayIcon(Icons.Outlined.FavoriteBorder, "喜欢") { dispatch(PlaybackOverlayAction.ToggleFavorite) }
                OverlayIcon(Icons.Outlined.BookmarkBorder, "收藏") { dispatch(PlaybackOverlayAction.ToggleBookmark) }
                OverlayIcon(Icons.Outlined.ThumbDownOffAlt, "点踩") { dispatch(PlaybackOverlayAction.ToggleDislike) }
                OverlayIcon(Icons.Outlined.StopCircle, "停止设备") { dispatch(PlaybackOverlayAction.StopDevice) }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
            ) {
                Text(overlay.title.ifBlank { "未选择媒体" }, color = Color.White, maxLines = 1)
                Text(overlay.folder.ifBlank { "本地媒体" }, color = Color.LightGray, maxLines = 1)
                Text(overlay.summary.ifBlank { "等待媒体索引" }, color = Color.LightGray, maxLines = 1)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OverlayIcon(if (overlay.mode == PlaybackOverlayMode.CLEAN) Icons.Outlined.CloseFullscreen else Icons.Outlined.Fullscreen, "切换清屏") {
                dispatch(PlaybackOverlayAction.ToggleCleanScreen)
            }
            OverlayIcon(Icons.Outlined.ArrowBack, "后退五秒") { dispatch(PlaybackOverlayAction.SeekBack) }
            OverlayIcon(if (overlay.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (overlay.isPlaying) "暂停" else "播放") {
                dispatch(PlaybackOverlayAction.TogglePlayPause)
            }
            TextButton(onClick = { dispatch(PlaybackOverlayAction.CycleSpeed) }) {
                Text("${overlay.speed}x", color = Color.White)
            }
        }
        if (overlay.deviceStopped) {
            Text(
                "设备已停止",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val PlaybackOverlaySaver = Saver<PlaybackOverlayState, List<Any?>>(
    save = { state ->
        listOf(
            state.mode.name,
            state.isPlaying,
            state.deviceConnected,
            state.deviceStopped,
            state.isFavorite,
            state.isBookmarked,
            state.isDisliked,
            state.speed,
            state.title,
            state.folder,
            state.summary,
        )
    },
    restore = { values ->
        PlaybackOverlayState(
            mode = PlaybackOverlayMode.valueOf(values[0] as String),
            isPlaying = values[1] as Boolean,
            deviceConnected = values[2] as Boolean,
            deviceStopped = values[3] as Boolean,
            isFavorite = values[4] as Boolean,
            isBookmarked = values[5] as Boolean,
            isDisliked = values[6] as Boolean,
            speed = (values[7] as Number).toFloat(),
            title = values[8] as String,
            folder = values[9] as String,
            summary = values[10] as String,
        )
    },
)

@Composable
private fun OverlayIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun EmptyLibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching { SafPermissionStore(context).persistReadOnly(uri) }
            selectedFolder = uri.lastPathSegment ?: uri.toString()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "FPlayer", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { folderPicker.launch(null) }) {
                Text("添加文件夹")
            }
            selectedFolder?.let { folder ->
                Text(text = folder, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DestinationTitle(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

private val FPlayerColors = darkColorScheme(
    primary = Color(0xFF45C7C7),
    onPrimary = Color(0xFF062020),
    secondary = Color(0xFFE3B85C),
    background = Color(0xFF0B0D0F),
    onBackground = Color(0xFFE7EAEC),
    surface = Color(0xFF13171A),
    onSurface = Color(0xFFE7EAEC),
    surfaceVariant = Color(0xFF20262A),
    onSurfaceVariant = Color(0xFFBBC3C8),
    outline = Color(0xFF69747A),
    outlineVariant = Color(0xFF30383D),
    error = Color(0xFFFF6B6B),
)
