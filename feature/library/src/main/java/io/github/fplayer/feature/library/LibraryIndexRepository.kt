package io.github.fplayer.feature.library

import io.github.fplayer.core.index.db.IndexDao
import io.github.fplayer.core.model.MediaId

/** Reads only committed Room generations. Call from a background dispatcher. */
class LibraryIndexRepository(private val dao: IndexDao) {
    fun load(): LibraryCatalogInput {
        val playbackByMediaId = dao.playbackSnapshot().associateBy { it.mediaId }
        val folders = mutableListOf<LibraryCatalogFolder>()
        val media = mutableListOf<LibraryCatalogMedia>()

        dao.sources().forEach { source ->
            val sourceFolders = dao.currentFolders(source.id)
            val folderNameById = sourceFolders.associate { it.id to it.displayName }
            val scriptedMediaIds = dao.currentScriptSnapshot(source.id)
                .mapNotNullTo(mutableSetOf()) { it.mediaId }

            folders += sourceFolders.map { folder ->
                LibraryCatalogFolder(
                    id = folder.id,
                    sourceId = folder.sourceId,
                    displayName = folder.displayName,
                    path = folder.normalizedPath,
                    directMediaCount = folder.directMediaCount,
                    descendantMediaCount = folder.descendantMediaCount,
                )
            }
            media += dao.currentMediaSnapshot(source.id).map { item ->
                val playback = playbackByMediaId[item.id]
                LibraryCatalogMedia(
                    mediaId = MediaId(item.id),
                    sourceId = item.sourceId,
                    folderId = item.folderId,
                    title = item.displayName,
                    normalizedTitle = item.normalizedTitle,
                    path = item.normalizedPath,
                    folderName = folderNameById[item.folderId] ?: source.displayName,
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    modifiedAtEpochMs = item.modifiedAtEpochMs,
                    width = item.width,
                    height = item.height,
                    probeStatus = item.probeStatus,
                    progressPermille = playback?.coveragePermille ?: 0,
                    lastPlayedAtEpochMs = playback?.lastPlayedAtEpochMs,
                    hasScript = item.id in scriptedMediaIds,
                    liked = playback?.liked ?: false,
                    favorite = playback?.favorite ?: false,
                    disliked = playback?.disliked ?: false,
                )
            }
        }
        return LibraryCatalogInput(folders = folders, media = media)
    }
}
