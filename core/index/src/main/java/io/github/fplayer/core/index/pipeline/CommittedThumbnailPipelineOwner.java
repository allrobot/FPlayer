package io.github.fplayer.core.index.pipeline;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Owns production thumbnail work for one committed index generation.
 *
 * The owner deliberately reads only committed media and treats a missing cache
 * file as work even when the database row still says READY. This keeps the
 * database key and the filesystem cache coherent after app-cache eviction.
 */
public final class CommittedThumbnailPipelineOwner {
    private final IndexDao dao;
    private final ThumbnailCache cache;
    private final MediaMetadataProbe probe;
    private final MetadataThumbnailPipeline.ThumbnailGenerator thumbnailGenerator;
    private final int targetWidth;
    private final int targetHeight;
    private final String strategyVersion;

    public CommittedThumbnailPipelineOwner(
            @NonNull IndexDao dao,
            @NonNull ThumbnailCache cache,
            @NonNull MediaMetadataProbe probe,
            @NonNull MetadataThumbnailPipeline.ThumbnailGenerator thumbnailGenerator,
            int targetWidth,
            int targetHeight,
            @NonNull String strategyVersion
    ) {
        if (targetWidth < 1 || targetHeight < 1) {
            throw new IllegalArgumentException("THUMBNAIL_SIZE_INVALID");
        }
        this.dao = dao;
        this.cache = cache;
        this.probe = probe;
        this.thumbnailGenerator = thumbnailGenerator;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.strategyVersion = strategyVersion;
    }

    /** Runs work for all media in the source's current committed generation. */
    @NonNull
    public Report run(@NonNull String sourceId, @Nullable String selectedMediaId, long nowEpochMs) {
        IndexEntities.SourceEntity source = dao.source(sourceId);
        if (source == null || source.currentScanGeneration == null) {
            return Report.empty();
        }

        long generation = source.currentScanGeneration;
        List<IndexEntities.MediaEntity> media = dao.currentMediaSnapshot(sourceId);
        BoundedMediaWorkQueue<IndexEntities.MediaEntity> queue =
                new BoundedMediaWorkQueue<>(Math.max(1, media.size()));
        int skipped = 0;
        int queued = 0;
        for (IndexEntities.MediaEntity item : media) {
            if (hasUsableThumbnail(sourceId, item)) {
                skipped++;
                continue;
            }
            BoundedMediaWorkQueue.WorkPriority priority =
                    item.id.equals(selectedMediaId)
                            ? BoundedMediaWorkQueue.WorkPriority.CURRENT
                            : BoundedMediaWorkQueue.WorkPriority.REMAINING;
            if (queue.offer(item, priority)) queued++;
        }
        if (queued == 0) return new Report(0, skipped, 0, 0, 0, false);

        MetadataThumbnailPipeline.Report result = new MetadataThumbnailPipeline(
                dao,
                sourceId,
                generation,
                probe,
                thumbnailGenerator,
                cache,
                targetWidth,
                targetHeight,
                strategyVersion
        ).run(queue, MetadataThumbnailPipeline.NEVER, nowEpochMs);
        return new Report(
                queued,
                skipped,
                result.probed,
                result.thumbnails,
                result.failed,
                result.cancelled
        );
    }

    private boolean hasUsableThumbnail(String sourceId, IndexEntities.MediaEntity media) {
        IndexEntities.ThumbnailEntity thumbnail = dao.currentThumbnailForMedia(sourceId, media.id);
        if (thumbnail == null || thumbnail.cacheKey == null) return false;
        try {
            return Files.isRegularFile(cache.path(thumbnail.cacheKey)) &&
                    Files.size(cache.path(thumbnail.cacheKey)) > 0;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    public static final class Report {
        public final int queued;
        public final int skipped;
        public final int probed;
        public final int thumbnails;
        public final int failed;
        public final boolean cancelled;

        Report(int queued, int skipped, int probed, int thumbnails, int failed, boolean cancelled) {
            this.queued = queued;
            this.skipped = skipped;
            this.probed = probed;
            this.thumbnails = thumbnails;
            this.failed = failed;
            this.cancelled = cancelled;
        }

        static Report empty() { return new Report(0, 0, 0, 0, 0, false); }
    }
}
