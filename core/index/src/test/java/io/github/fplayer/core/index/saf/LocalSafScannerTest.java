package io.github.fplayer.core.index.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class LocalSafScannerTest {
    private static final String SOURCE_ID = "saf-source";
    private FPlayerIndexDatabase database;
    private IndexDao dao;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = database.indexDao();
        dao.insertSource(new IndexEntities.SourceEntity(
                SOURCE_ID,
                "LOCAL",
                "Fixture",
                "content://fixture/root",
                null,
                null,
                1L,
                1L
        ));
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void scanDetectsAddedModifiedRemovedAndMatchesScripts() throws IOException {
        MutableTree tree = new MutableTree();
        tree.add(root());
        tree.add(directory("folder", "Folder"), "root");
        tree.add(file("video", "Folder/video.mp4", "video/mp4", 10L, 100L), "folder");
        tree.add(file("script", "Folder/video.funscript", "application/json", 4L, 100L), "folder");

        LocalSafScanner scanner = new LocalSafScanner(dao, () -> 10L);
        LocalSafScanner.ScanResult first = scanner.scan(SOURCE_ID, 1L, tree, LocalSafScanner.Cancellation.NEVER);
        assertTrue(first.committed);
        assertEquals(1, first.mediaCount);
        assertEquals(1, first.scriptCount);
        assertEquals(2, first.addedCount);
        assertEquals(1, dao.currentScriptsForMedia(dao.currentMediaSnapshot(SOURCE_ID).get(0).id).size());

        tree.remove("script");
        tree.replace(file("video", "Folder/video.mp4", "video/mp4", 11L, 200L));
        tree.add(file("new-video", "Folder/new-video.webm", "video/webm", 20L, 200L), "folder");
        LocalSafScanner.ScanResult second = scanner.scan(SOURCE_ID, 2L, tree, LocalSafScanner.Cancellation.NEVER);
        assertTrue(second.committed);
        assertEquals(1, second.addedCount);
        assertEquals(1, second.modifiedCount);
        assertEquals(1, second.removedCount);
        assertEquals(2, dao.currentMediaCount(SOURCE_ID));
        assertEquals(Long.valueOf(2L), dao.source(SOURCE_ID).currentScanGeneration);
    }

    @Test
    public void cancellationLeavesOldSnapshotAndResumeUsesNewGeneration() throws IOException {
        MutableTree tree = new MutableTree();
        tree.add(root());
        tree.add(file("old", "old.mp4", "video/mp4", 1L, 1L), "root");
        LocalSafScanner scanner = new LocalSafScanner(dao, () -> 10L);
        scanner.scan(SOURCE_ID, 1L, tree, LocalSafScanner.Cancellation.NEVER);

        tree.add(file("new", "new.mp4", "video/mp4", 1L, 1L), "root");
        LocalSafScanner.ScanResult cancelled = scanner.scan(
                SOURCE_ID,
                2L,
                tree,
                new LocalSafScanner.Cancellation() {
                    private int checks;

                    @Override
                    public boolean isCancelled() {
                        return ++checks > 2;
                    }
                }
        );
        assertFalse(cancelled.committed);
        assertEquals("CANCELLED", cancelled.failureCode);
        assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus(SOURCE_ID, 2L));
        assertEquals(1, dao.currentMediaCount(SOURCE_ID));
        assertEquals(Long.valueOf(1L), dao.source(SOURCE_ID).currentScanGeneration);

        LocalSafScanner.ScanResult resumed = scanner.scan(SOURCE_ID, 3L, tree, LocalSafScanner.Cancellation.NEVER);
        assertTrue(resumed.committed);
        assertEquals(2, dao.currentMediaCount(SOURCE_ID));
        assertEquals(Long.valueOf(3L), dao.source(SOURCE_ID).currentScanGeneration);
    }

    @Test
    public void twoThousandFiveHundredSyntheticFilesAreDeterministic() throws IOException {
        MutableTree tree = new MutableTree();
        tree.add(root());
        for (int index = 0; index < 2_500; index++) {
            String id = "media-" + index;
            tree.add(file(id, id + ".mp4", "video/mp4", 100L, 1L), "dir-" + (index % 25));
        }
        for (int index = 0; index < 25; index++) {
            String id = "dir-" + index;
            tree.add(directory(id, id), "root");
        }
        long startedNs = System.nanoTime();
        LocalSafScanner.ScanResult result = new LocalSafScanner(dao, System::currentTimeMillis)
                .scan(SOURCE_ID, 1L, tree, LocalSafScanner.Cancellation.NEVER);
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        assertTrue(result.committed);
        assertEquals(2_500, result.mediaCount);
        assertEquals(25, result.folderCount - 1);
        assertEquals(2_500, dao.currentMediaCount(SOURCE_ID));
        assertEquals("/dir-0/media-0.mp4", dao.currentMediaPage(SOURCE_ID, 1, 0).get(0).normalizedPath);
        assertTrue("SAF fixture scan took " + elapsedMs + " ms", elapsedMs < 15_000L);
    }

    @Test
    public void cancellationAtTwentyFiveFiftyAndNinetyFivePercentKeepsOldSnapshot() throws IOException {
        MutableTree initial = new MutableTree();
        initial.add(root());
        initial.add(file("old", "old.mp4", "video/mp4", 1L, 1L), "root");
        LocalSafScanner scanner = new LocalSafScanner(dao, () -> 10L);
        assertTrue(scanner.scan(SOURCE_ID, 1L, initial, LocalSafScanner.Cancellation.NEVER).committed);

        MutableTree replacement = new MutableTree();
        replacement.add(root());
        for (int index = 0; index < 100; index++) {
            replacement.add(file("new-" + index, "new-" + index + ".mp4", "video/mp4", 1L, 1L), "root");
        }
        int[] percentages = {25, 50, 95};
        int[] cancellationChecks = {26, 51, 96};
        for (int index = 0; index < percentages.length; index++) {
            long generation = index + 2L;
            LocalSafScanner.ScanResult result = scanner.scan(
                    SOURCE_ID,
                    generation,
                    replacement,
                    new CancelAfterChecks(cancellationChecks[index])
            );
            assertFalse(percentages[index] + "% cancellation committed", result.committed);
            assertEquals(percentages[index], result.mediaCount);
            assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus(SOURCE_ID, generation));
            assertEquals(Long.valueOf(1L), dao.source(SOURCE_ID).currentScanGeneration);
            assertEquals(1, dao.currentMediaCount(SOURCE_ID));
        }
    }

    private static SafDocumentTree.Entry root() {
        return directory("root", "Root");
    }

    private static SafDocumentTree.Entry directory(String id, String name) {
        return new SafDocumentTree.Entry(id, "content://fixture/" + id, name, "vnd.android.document/directory", true, null, null);
    }

    private static SafDocumentTree.Entry file(String id, String name, String mime, long size, long modified) {
        return new SafDocumentTree.Entry(id, "content://fixture/" + id, name, mime, false, size, modified);
    }

    private static final class MutableTree implements SafDocumentTree {
        private final Map<String, Entry> entries = new HashMap<>();
        private final Map<String, List<String>> children = new HashMap<>();

        void add(Entry entry) { add(entry, null); }

        void add(Entry entry, String parentId) {
            entries.put(entry.documentId, entry);
            if (parentId != null) {
                children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(entry.documentId);
            }
        }

        void remove(String id) {
            entries.remove(id);
            for (List<String> values : children.values()) values.remove(id);
        }

        void replace(Entry entry) {
            entries.put(entry.documentId, entry);
        }

        @Override public Entry root() { return entries.get("root"); }

        @Override public List<Entry> children(Entry directory) {
            List<Entry> result = new ArrayList<>();
            for (String id : children.getOrDefault(directory.documentId, List.of())) result.add(entries.get(id));
            return result;
        }
    }

    private static final class CancelAfterChecks implements LocalSafScanner.Cancellation {
        private final int allowedChecks;
        private int checks;

        CancelAfterChecks(int allowedChecks) {
            this.allowedChecks = allowedChecks;
        }

        @Override public boolean isCancelled() {
            return ++checks > allowedChecks;
        }
    }
}
