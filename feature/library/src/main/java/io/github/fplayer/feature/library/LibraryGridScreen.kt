package io.github.fplayer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LibraryGridScreen(
    snapshot: LibraryGridSnapshot,
    jumpTarget: LibraryJumpTarget?,
    adaptiveGridColumns: Int,
    onLayoutChanged: (LibraryLayout) -> Unit,
    onFilterChanged: (LibraryContentFilter) -> Unit,
    onOpenMedia: (LibraryGridItem) -> Unit,
    onJumpConsumed: () -> Unit,
    onJumpToJustWatched: () -> Unit,
    thumbnailKey: (LibraryGridItem) -> String? = { null },
    thumbnailLoader: (String) -> LibraryThumbnailState = { LibraryThumbnailState.Error },
    modifier: Modifier = Modifier,
) {
    require(adaptiveGridColumns >= 3)
    val gridState = rememberLazyGridState()
    LaunchedEffect(jumpTarget) {
        jumpTarget?.let { target ->
            gridState.scrollToItem(target.visibleIndex)
            onJumpConsumed()
        }
    }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            FolderHeader(
                snapshot = snapshot,
                onLayoutChanged = onLayoutChanged,
                onFilterChanged = onFilterChanged,
                onJumpToJustWatched = onJumpToJustWatched,
            )
            if (snapshot.visibleItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (snapshot.contentFilter == LibraryContentFilter.SCRIPTED_VIDEOS) "没有带脚本的视频" else "此文件夹没有视频",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val columns = if (snapshot.layout == LibraryLayout.DOUBLE_COLUMN) 2 else adaptiveGridColumns
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(snapshot.visibleItems, key = { it.mediaId.value }) { item ->
                        MediaGridTile(
                            item = item,
                            isCurrentOrJustWatched = snapshot.currentMediaId == item.mediaId ||
                                snapshot.justWatchedMediaId == item.mediaId,
                            showMetadata = snapshot.layout == LibraryLayout.DOUBLE_COLUMN,
                            thumbnailKey = thumbnailKey(item),
                            thumbnailLoader = thumbnailLoader,
                            onClick = { onOpenMedia(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(
    snapshot: LibraryGridSnapshot,
    onLayoutChanged: (LibraryLayout) -> Unit,
    onFilterChanged: (LibraryContentFilter) -> Unit,
    onJumpToJustWatched: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    snapshot.folder.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${snapshot.folder.directMediaCount} 个视频 · ${snapshot.folder.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            IconButton(
                onClick = onJumpToJustWatched,
                enabled = snapshot.justWatchedMediaId != null,
            ) {
                Icon(Icons.Outlined.History, contentDescription = "定位刚刚看过")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow {
                LibraryContentFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = snapshot.contentFilter == filter,
                        onClick = { onFilterChanged(filter) },
                        shape = SegmentedButtonDefaults.itemShape(index, LibraryContentFilter.entries.size),
                        icon = {
                            Icon(
                                Icons.Outlined.SmartDisplay,
                                contentDescription = null,
                            )
                        },
                    ) {
                        Text(if (filter == LibraryContentFilter.ALL_VIDEOS) "视频" else "脚本")
                    }
                }
            }
            SingleChoiceSegmentedButtonRow {
                LibraryLayout.entries.forEachIndexed { index, layout ->
                    SegmentedButton(
                        selected = snapshot.layout == layout,
                        onClick = { onLayoutChanged(layout) },
                        shape = SegmentedButtonDefaults.itemShape(index, LibraryLayout.entries.size),
                        icon = {},
                    ) {
                        Icon(
                            if (layout == LibraryLayout.GRID) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                            contentDescription = if (layout == LibraryLayout.GRID) "网格" else "双列",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGridTile(
    item: LibraryGridItem,
    isCurrentOrJustWatched: Boolean,
    showMetadata: Boolean,
    thumbnailKey: String?,
    thumbnailLoader: (String) -> LibraryThumbnailState,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 2f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            LibraryThumbnail(
                key = thumbnailKey,
                loader = thumbnailLoader,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.hasScript) {
                Text(
                    "脚本",
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (isCurrentOrJustWatched) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("刚刚看过", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth(item.progressPermille / 1000f)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (showMetadata) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showMetadata) {
            Text(
                "${item.folderName} · ${formatDuration(item.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return "--:--"
    val totalSeconds = durationMs / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
