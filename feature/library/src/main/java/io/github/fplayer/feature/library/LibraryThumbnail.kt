package io.github.fplayer.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

sealed interface LibraryThumbnailState {
    data object Loading : LibraryThumbnailState
    data class Ready(val image: ImageBitmap) : LibraryThumbnailState
    data object Error : LibraryThumbnailState
    data object Unavailable : LibraryThumbnailState
}

object LibraryThumbnailPresentation {
    fun load(key: String?, loader: (String) -> LibraryThumbnailState): LibraryThumbnailState =
        if (key == null) LibraryThumbnailState.Unavailable
        else runCatching { loader(key) }.getOrElse { LibraryThumbnailState.Error }

    fun accessibilityLabel(key: String?, state: LibraryThumbnailState?): String = when {
        key == null || state == null || state is LibraryThumbnailState.Unavailable || state is LibraryThumbnailState.Error ->
            "视频预览不可用"
        state is LibraryThumbnailState.Loading -> "视频预览加载中"
        state is LibraryThumbnailState.Ready -> "视频缩略图"
        else -> "视频预览不可用"
    }
}

@Composable
fun LibraryThumbnail(
    key: String?,
    loader: suspend (String) -> LibraryThumbnailState,
    modifier: Modifier = Modifier,
) {
    val state = produceState<LibraryThumbnailState>(
        initialValue = if (key == null) LibraryThumbnailState.Unavailable else LibraryThumbnailState.Loading,
        key1 = key,
    ) {
        if (key != null) {
            value = runCatching { loader(key) }.getOrElse { LibraryThumbnailState.Error }
        }
    }.value
    when (state) {
        is LibraryThumbnailState.Ready -> Image(
            bitmap = state.image,
            contentDescription = LibraryThumbnailPresentation.accessibilityLabel(key, state),
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        LibraryThumbnailState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SmartDisplay, contentDescription = "视频预览加载中")
        }
        LibraryThumbnailState.Error,
        LibraryThumbnailState.Unavailable,
        -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.BrokenImage, contentDescription = LibraryThumbnailPresentation.accessibilityLabel(key, state))
        }
    }
}
