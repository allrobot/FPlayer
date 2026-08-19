package io.github.fplayer.core.index.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import io.github.fplayer.core.index.db.FPlayerIndexDatabase;
import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class MetadataThumbnailPipelineTest {
    private FPlayerIndexDatabase database;
    private IndexDao dao;
    private Path cacheRoot;

    @Before public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class).allowMainThreadQueries().build();
        dao = database.indexDao();
        dao.insertSource(new IndexEntities.SourceEntity("source", "LOCAL", "Fixture", "content://root", null, null, 1, 1));
        dao.beginScan("source", 1, 1);
        IndexEntities.FolderEntity folder = new IndexEntities.FolderEntity("folder", 1, "source", null, "/", "Root", 1, 1);
        dao.upsertFolders(List.of(folder));
        dao.upsertMedia(List.of(media("media", 1, 10)));
        dao.commitScan("source", 1, 2);
        cacheRoot = Files.createTempDirectory("thumb-cache");
    }

    @After public void tearDown() throws Exception { database.close(); delete(cacheRoot); }

    @Test public void queuePrefersCurrentAndRejectsLowerPriorityWhenFull() {
        BoundedMediaWorkQueue<String> queue = new BoundedMediaWorkQueue<>(2);
        assertTrue(queue.offer("remaining", BoundedMediaWorkQueue.WorkPriority.REMAINING));
        assertTrue(queue.offer("adjacent", BoundedMediaWorkQueue.WorkPriority.ADJACENT));
        assertTrue(queue.offer("current", BoundedMediaWorkQueue.WorkPriority.CURRENT));
        assertFalse(queue.offer("late", BoundedMediaWorkQueue.WorkPriority.REMAINING));
        assertEquals("current", queue.poll().value);
        assertEquals("adjacent", queue.poll().value);
    }

    @Test public void pipelineProbesWritesThumbnailAndCleanupRequiresCommit() throws Exception {
        ThumbnailCache cache = new ThumbnailCache(cacheRoot);
        MetadataThumbnailPipeline pipeline = new MetadataThumbnailPipeline(
                dao, "source", 1,
                locator -> new MediaMetadataProbe.ProbeResult(123L, 640, 360, 30.0, "video/mp4", "h264", "aac", 0),
                (locator, width, height) -> new byte[] {1, 2, 3}, cache, 160, 90, "v1");
        BoundedMediaWorkQueue<IndexEntities.MediaEntity> queue = new BoundedMediaWorkQueue<>(4);
        queue.offer(dao.currentMedia("media"), BoundedMediaWorkQueue.WorkPriority.CURRENT);
        MetadataThumbnailPipeline.Report report = pipeline.run(queue, MetadataThumbnailPipeline.NEVER, 3);
        assertEquals(1, report.probed);
        assertEquals(1, report.thumbnails);
        assertEquals(Long.valueOf(123L), dao.currentMedia("media").durationMs);
        assertEquals(1, dao.currentThumbnailCacheKeys("source").size());
        assertTrue(Files.size(cache.path(dao.currentThumbnailCacheKeys("source").get(0))) > 0);
        pipeline.cleanupCommitted();
    }

    @Test public void cancellationStopsBeforeNextItemAndProbeFailureDoesNotAbortQueue() throws Exception {
        ThumbnailCache cache = new ThumbnailCache(cacheRoot);
        AtomicInteger calls = new AtomicInteger();
        MetadataThumbnailPipeline pipeline = new MetadataThumbnailPipeline(
                dao, "source", 1,
                locator -> { if (calls.incrementAndGet() == 1) throw new Exception("probe");
                    return new MediaMetadataProbe.ProbeResult(null, null, null, null, null, null, null, null); },
                (locator, width, height) -> new byte[] {9}, cache, 100, 100, "v1");
        BoundedMediaWorkQueue<IndexEntities.MediaEntity> queue = new BoundedMediaWorkQueue<>(4);
        queue.offer(dao.currentMedia("media"), BoundedMediaWorkQueue.WorkPriority.CURRENT);
        MetadataThumbnailPipeline.Report report = pipeline.run(queue, () -> true, 3);
        assertEquals(0, report.probed);
        assertTrue(report.cancelled);
        assertThrows(IllegalStateException.class, () -> new MetadataThumbnailPipeline(
                dao, "source", 2, pipelineProbe(), (l, w, h) -> new byte[] {1}, cache, 1, 1, "v1").cleanupCommitted());
    }

    private static MediaMetadataProbe pipelineProbe() { return locator -> new MediaMetadataProbe.ProbeResult(null, null, null, null, null, null, null, null); }

    private static IndexEntities.MediaEntity media(String id, long generation, long size) {
        return new IndexEntities.MediaEntity(id, generation, "source", "folder", id, "content://" + id,
                id + ".mp4", id, "/" + id + ".mp4", size, 1L, null, null, null, null, "video/mp4", null, null, null, "UNPROBED");
    }

    private static void delete(Path path) throws Exception {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) { stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); }
    }
}
