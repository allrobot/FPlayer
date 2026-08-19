package io.github.fplayer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fplayer.core.index.SortDirection
import io.github.fplayer.core.index.SortField
import io.github.fplayer.core.index.SortSpec
import io.github.fplayer.core.model.MediaId
import kotlinx.coroutines.launch

@Composable
fun LibraryCatalogScreen(
    snapshot: LibraryCatalogSnapshot,
    adaptiveGridColumns: Int,
    onAlbumViewChanged: (LibraryAlbumView) -> Unit,
    onCollectionChanged: (LibraryCollection) -> Unit,
    onLayoutChanged: (LibraryLayout) -> Unit,
    onSortChanged: (SortSpec) -> Unit,
    onSearchChanged: (String) -> Unit,
    onOpenFolder: (LibraryCatalogFolder) -> Unit,
    onCloseFolder: () -> Unit,
    onOpenMedia: (LibraryCatalogMedia) -> Unit,
    onRequestDelete: (MediaId) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
    openDrawerSignal: Int = 0,
    openSearchSignal: Int = 0,
    thumbnailKey: (LibraryCatalogMedia) -> String? = { null },
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchVisible by remember { mutableStateOf(false) }
    var sortMenuVisible by remember { mutableStateOf(false) }
    LaunchedEffect(openDrawerSignal) {
        if (openDrawerSignal > 0) drawerState.open()
    }
    LaunchedEffect(openSearchSignal) {
        if (openSearchSignal > 0) searchVisible = true
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("媒体库", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                LibraryCollection.entries.forEach { collection ->
                    NavigationDrawerItem(
                        label = { Text(collectionLabel(collection)) },
                        selected = snapshot.collection == collection,
                        onClick = {
                            onCollectionChanged(collection)
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(collectionIcon(collection), contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (snapshot.selectedFolder != null) onCloseFolder() else scope.launch { drawerState.open() }
                }) {
                    Icon(
                        if (snapshot.selectedFolder != null) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                        contentDescription = if (snapshot.selectedFolder != null) "返回相册" else "打开抽屉",
                    )
                }
                Text(
                    snapshot.selectedFolder?.displayName
                        ?: if (snapshot.collection == LibraryCollection.ALL) "相册" else collectionLabel(snapshot.collection),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { sortMenuVisible = true }) {
                        Text(sortLabel(snapshot.sortSpec.field))
                        Icon(
                            if (snapshot.sortSpec.direction == SortDirection.ASCENDING) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                            contentDescription = if (snapshot.sortSpec.direction == SortDirection.ASCENDING) "升序" else "降序",
                        )
                    }
                    DropdownMenu(expanded = sortMenuVisible, onDismissRequest = { sortMenuVisible = false }) {
                        SortField.entries.forEach { field ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(field)) },
                                onClick = {
                                    val direction = if (snapshot.sortSpec.field == field) {
                                        if (snapshot.sortSpec.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
                                    } else {
                                        SortDirection.ASCENDING
                                    }
                                    onSortChanged(SortSpec(field, direction))
                                    sortMenuVisible = false
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { searchVisible = !searchVisible }) {
                    Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = if (searchVisible) "关闭搜索" else "搜索")
                }
                IconButton(onClick = {
                    onLayoutChanged(if (snapshot.layout == LibraryLayout.GRID) LibraryLayout.DOUBLE_COLUMN else LibraryLayout.GRID)
                }) {
                    Icon(
                        if (snapshot.layout == LibraryLayout.GRID) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                        contentDescription = if (snapshot.layout == LibraryLayout.GRID) "网格" else "双列",
                    )
                }
            }
            if (searchVisible) {
                OutlinedTextField(
                    value = snapshot.searchQuery,
                    onValueChange = onSearchChanged,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    label = { Text("搜索标题、文件夹或路径") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
            }
            if (snapshot.collection == LibraryCollection.ALL) {
                PrimaryScrollableTabRow(selectedTabIndex = snapshot.albumView.ordinal, edgePadding = 8.dp) {
                    LibraryAlbumView.entries.forEach { view ->
                        Tab(
                            selected = snapshot.albumView == view,
                            onClick = { onAlbumViewChanged(view) },
                            text = { Text(albumViewLabel(view)) },
                        )
                    }
                }
            } else {
                HorizontalDivider()
            }
            CatalogBody(
                snapshot = snapshot,
                adaptiveGridColumns = adaptiveGridColumns,
                onOpenFolder = onOpenFolder,
                onOpenMedia = onOpenMedia,
                onRequestDelete = onRequestDelete,
                thumbnailKey = thumbnailKey,
            )
        }
        }
    }
    if (snapshot.pendingDeleteMediaId != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("确认删除") },
            text = { Text("删除不会由浏览手势直接执行。确认后才会交给当前来源处理。") },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun CatalogBody(
    snapshot: LibraryCatalogSnapshot,
    adaptiveGridColumns: Int,
    onOpenFolder: (LibraryCatalogFolder) -> Unit,
    onOpenMedia: (LibraryCatalogMedia) -> Unit,
    onRequestDelete: (MediaId) -> Unit,
    thumbnailKey: (LibraryCatalogMedia) -> String?,
) {
    val showFolders = snapshot.collection == LibraryCollection.ALL && snapshot.albumView != LibraryAlbumView.ALL_VIDEOS
    if ((showFolders && snapshot.folders.isEmpty()) || (!showFolders && snapshot.media.isEmpty())) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (snapshot.searchQuery.isNotEmpty()) "没有匹配结果" else "暂无内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val columns = if (snapshot.layout == LibraryLayout.DOUBLE_COLUMN) 2 else adaptiveGridColumns
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showFolders) {
            items(snapshot.folders, key = { it.id }) { folder ->
                FolderTile(folder, onClick = { onOpenFolder(folder) })
            }
        } else {
            items(snapshot.media, key = { it.mediaId.value }) { media ->
                CatalogMediaTile(
                    media = media,
                    current = snapshot.currentMediaId == media.mediaId,
                    showMetadata = snapshot.layout == LibraryLayout.DOUBLE_COLUMN,
                    thumbnailKey = thumbnailKey(media),
                    onOpen = { onOpenMedia(media) },
                    onDelete = { onRequestDelete(media.mediaId) },
                )
            }
        }
    }
}

@Composable
private fun FolderTile(folder: LibraryCatalogFolder, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                "${folder.descendantMediaCount}",
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(folder.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
private fun CatalogMediaTile(
    media: LibraryCatalogMedia,
    current: Boolean,
    showMetadata: Boolean,
    thumbnailKey: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Outlined.SmartDisplay,
                contentDescription = thumbnailKey?.let { "视频缩略图" } ?: "视频预览占位",
                modifier = Modifier.align(Alignment.Center),
            )
            if (media.hasScript) Text("脚本", modifier = Modifier.align(Alignment.TopStart).padding(6.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除 ${media.title}")
            }
            if (current) {
                Text("正在播放", modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.62f)).padding(6.dp), color = Color.White)
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(media.progressPermille / 1000f).height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(media.title, maxLines = if (showMetadata) 2 else 1, overflow = TextOverflow.Ellipsis)
        if (showMetadata) {
            Text(media.folderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun albumViewLabel(view: LibraryAlbumView): String = when (view) {
    LibraryAlbumView.ALL_FOLDERS -> "所有文件夹"
    LibraryAlbumView.VIDEO_FOLDERS -> "含视频文件夹"
    LibraryAlbumView.ALL_VIDEOS -> "全部视频"
}

private fun collectionLabel(collection: LibraryCollection): String = when (collection) {
    LibraryCollection.ALL -> "全部媒体"
    LibraryCollection.HISTORY -> "历史"
    LibraryCollection.LIKED -> "喜欢"
    LibraryCollection.FAVORITES -> "收藏"
    LibraryCollection.DISLIKED -> "点踩"
}

private fun collectionIcon(collection: LibraryCollection) = when (collection) {
    LibraryCollection.ALL -> Icons.Outlined.SmartDisplay
    LibraryCollection.HISTORY -> Icons.Outlined.History
    LibraryCollection.LIKED -> Icons.Outlined.FavoriteBorder
    LibraryCollection.FAVORITES -> Icons.Outlined.BookmarkBorder
    LibraryCollection.DISLIKED -> Icons.Outlined.ThumbDownOffAlt
}

private fun sortLabel(field: SortField): String = when (field) {
    SortField.TITLE -> "标题"
    SortField.MODIFIED_AT -> "修改时间"
    SortField.LAST_PLAYED_AT -> "播放时间"
    SortField.STATUS -> "状态"
    SortField.DURATION -> "时长"
    SortField.SIZE -> "大小"
    SortField.RESOLUTION -> "分辨率"
    SortField.PATH -> "路径"
}
