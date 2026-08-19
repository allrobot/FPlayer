package io.github.fplayer.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryThumbnailPresentationTest {
    @Test
    fun missingKeyUsesAnHonestPreviewUnavailableLabel() {
        assertEquals(
            "视频预览不可用",
            LibraryThumbnailPresentation.accessibilityLabel(key = null, state = null),
        )
    }

    @Test
    fun loaderStatesExposeLoadingAndErrorWithoutClaimingAThumbnailExists() {
        assertEquals(
            "视频预览加载中",
            LibraryThumbnailPresentation.accessibilityLabel("thumbnail-1", LibraryThumbnailState.Loading),
        )
        assertEquals(
            "视频预览不可用",
            LibraryThumbnailPresentation.accessibilityLabel("thumbnail-1", LibraryThumbnailState.Error),
        )
    }
}
