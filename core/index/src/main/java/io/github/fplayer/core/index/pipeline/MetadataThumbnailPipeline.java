package io.github.fplayer.core.index.pipeline;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/** Bounded, cancellable metadata and thumbnail work runner. */
public final class MetadataThumbnailPipeline {
    private final IndexDao dao;
    private final MediaMetadataProbe probe;
    private final ThumbnailGenerator thumbnailGenerator;
    private final ThumbnailCache cache;
    private final String sourceId;
    private final long generation;
    private final int targetWidth;
    private final int targetHeight;
    private final String strategyVersion;

    public MetadataThumbnailPipeline(IndexDao dao, String sourceId, long generation,
                                     MediaMetadataProbe probe, ThumbnailGenerator thumbnailGenerator,
                                     ThumbnailCache cache, int targetWidth, int targetHeight,
                                     String strategyVersion) {
        this.dao = dao; this.sourceId = sourceId; this.generation = generation;
        this.probe = probe; this.thumbnailGenerator = thumbnailGenerator; this.cache = cache;
        this.targetWidth = targetWidth; this.targetHeight = targetHeight; this.strategyVersion = strategyVersion;
    }

    public Report run(@NonNull BoundedMediaWorkQueue<IndexEntities.MediaEntity> queue,
                      @NonNull Cancellation cancellation, long nowEpochMs) {
        int probed = 0, thumbnails = 0, failed = 0;
        while (!cancellation.isCancelled()) {
            BoundedMediaWorkQueue.Item<IndexEntities.MediaEntity> item = queue.poll();
            if (item == null) break;
            IndexEntities.MediaEntity media = item.value;
            try {
                MediaMetadataProbe.ProbeResult result = probe.probe(media.locator);
                dao.upsertMedia(List.of(withProbe(media, result)));
                probed++;
                byte[] bitmap = thumbnailGenerator.generate(media.locator, targetWidth, targetHeight);
                String key = cache.cacheKey(media.id, media.sizeBytes, media.modifiedAtEpochMs,
                        targetWidth, targetHeight, strategyVersion);
                cache.publish(key, bitmap);
                dao.upsertThumbnails(List.of(new IndexEntities.ThumbnailEntity(
                        media.id + ":" + key, generation, sourceId, media.id, null,
                        key, "READY", nowEpochMs
                )));
                thumbnails++;
            } catch (Exception ignored) {
                failed++;
            }
        }
        return new Report(probed, thumbnails, failed, cancellation.isCancelled());
    }

    /** Cleanup is deliberately callable only after the scanner has committed generation. */
    public void cleanupCommitted() throws IOException {
        if (dao.source(sourceId) == null || dao.source(sourceId).currentScanGeneration == null ||
                dao.source(sourceId).currentScanGeneration != generation) {
            throw new IllegalStateException("THUMBNAIL_CLEANUP_REQUIRES_COMMITTED_GENERATION");
        }
        cache.cleanup(new HashSet<>(dao.currentThumbnailCacheKeys(sourceId)));
    }

    private static IndexEntities.MediaEntity withProbe(IndexEntities.MediaEntity media, MediaMetadataProbe.ProbeResult result) {
        return new IndexEntities.MediaEntity(media.id, media.scanGeneration, media.sourceId, media.folderId,
                media.stableKey, media.locator, media.displayName, media.normalizedTitle, media.normalizedPath,
                media.sizeBytes, media.modifiedAtEpochMs, result.durationMs, result.width, result.height,
                result.frameRate, result.mimeType, result.videoCodec, result.audioCodec, result.subtitleCount, "READY");
    }

    public interface ThumbnailGenerator { byte[] generate(String locator, int width, int height) throws Exception; }
    public interface Cancellation { boolean isCancelled(); }
    public static final Cancellation NEVER = () -> false;
    public static final class Report {
        public final int probed, thumbnails, failed; public final boolean cancelled;
        Report(int probed, int thumbnails, int failed, boolean cancelled) {
            this.probed = probed; this.thumbnails = thumbnails; this.failed = failed; this.cancelled = cancelled;
        }
    }
}
