package io.github.fplayer.core.index.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class IndexDatabaseTest {
    private static final String SOURCE_ID = "source-local";
    private static final String FOLDER_ID = "folder-root";

    private FPlayerIndexDatabase database;
    private IndexDao dao;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = database.indexDao();
        dao.insertSource(source(null, 100L));
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void incompleteGenerationDoesNotReplaceCurrentSnapshot() {
        dao.beginScan(SOURCE_ID, 1L, 101L);
        dao.upsertFolders(List.of(folder(1L, 1)));
        dao.upsertMedia(List.of(media("media-a", 1L, "Old title", "old-title")));
        dao.upsertScripts(List.of(script("script-a", "media-a", 1L)));
        dao.commitScan(SOURCE_ID, 1L, 110L);

        assertEquals("Old title", dao.currentMedia("media-a").displayName);
        assertEquals("Old title", dao.currentMediaLibrarySnapshot().get(0).displayName);
        assertEquals(1, dao.currentScriptsForMedia("media-a").size());
        assertEquals(Long.valueOf(1L), dao.source(SOURCE_ID).currentScanGeneration);
        assertEquals(
                1,
                dao.updateSourceMetadata(
                        SOURCE_ID,
                        "LOCAL",
                        "Renamed source",
                        "content://fixture/root",
                        null,
                        115L
                )
        );
        assertEquals(Long.valueOf(1L), dao.source(SOURCE_ID).currentScanGeneration);

        dao.beginScan(SOURCE_ID, 2L, 120L);
        dao.upsertFolders(List.of(folder(2L, 1)));
        dao.upsertMedia(List.of(media("media-a", 2L, "Uncommitted title", "uncommitted-title")));

        assertEquals("Old title", dao.currentMedia("media-a").displayName);
        assertEquals("Old title", dao.currentMediaLibrarySnapshot().get(0).displayName);
        assertEquals(1, dao.currentScriptsForMedia("media-a").size());

        dao.abandonScan(SOURCE_ID, 2L, 125L, "CANCELLED");

        assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus(SOURCE_ID, 2L));
        assertEquals("Old title", dao.currentMedia("media-a").displayName);
        assertEquals(Long.valueOf(1L), dao.source(SOURCE_ID).currentScanGeneration);

        dao.beginScan(SOURCE_ID, 3L, 130L);
        dao.upsertFolders(List.of(folder(3L, 1)));
        dao.upsertMedia(List.of(media("media-a", 3L, "Current title", "current-title")));
        dao.commitScan(SOURCE_ID, 3L, 140L);

        assertEquals(IndexDao.SCAN_COMPLETE, dao.scanStatus(SOURCE_ID, 3L));
        assertEquals("Current title", dao.currentMedia("media-a").displayName);
        assertEquals("Current title", dao.currentMediaLibrarySnapshot().get(0).displayName);
        assertTrue(dao.currentScriptsForMedia("media-a").isEmpty());
        assertEquals(1, dao.currentMediaCount(SOURCE_ID));
        assertEquals(Long.valueOf(3L), dao.source(SOURCE_ID).currentScanGeneration);
        assertThrows(
                IllegalStateException.class,
                () -> dao.beginScan(SOURCE_ID, 3L, 150L)
        );
    }

    @Test
    public void behaviorAndMaterializedOrderAreConsistentlyStored() {
        IndexEntities.PlaybackStateEntity playback = new IndexEntities.PlaybackStateEntity(
                "media-a",
                4_000L,
                200L,
                18_000L,
                750,
                2,
                3,
                4,
                5,
                6,
                true,
                false,
                true
        );
        dao.upsertPlaybackState(playback);
        dao.insertInteractionEvent(new IndexEntities.InteractionEventEntity(
                "event-a",
                "media-a",
                "MANUAL_REPLAY",
                201L,
                null
        ));
        dao.upsertRecommendationAggregate(new IndexEntities.RecommendationAggregateEntity(
                "media-a",
                18_000L,
                2,
                3,
                4,
                5,
                6,
                1.25,
                0.5,
                202L
        ));

        IndexEntities.PlaybackStateEntity storedPlayback = dao.playbackState("media-a");
        assertNotNull(storedPlayback);
        assertEquals(5, storedPlayback.backgroundLoopCount);
        assertTrue(storedPlayback.liked);
        assertFalse(storedPlayback.disliked);
        assertEquals("MANUAL_REPLAY", dao.interactionEvents("media-a").get(0).type);
        assertEquals(1, dao.sources().size());
        assertEquals("media-a", dao.playbackSnapshot().get(0).mediaId);
        assertEquals("event-a", dao.interactionSnapshot().get(0).id);
        assertEquals(
                1.25,
                dao.recommendationAggregate("media-a").decayedPositive,
                0.0
        );

        dao.publishOrder(
                orderHeader("order-1", 1L, 2, 210L),
                List.of(orderRow("order-1", 0, "media-b"), orderRow("order-1", 1, "media-a"))
        );
        assertEquals("order-1", dao.currentOrderHeader("ALL", "TITLE", "ASCENDING").id);
        assertEquals(Integer.valueOf(1), dao.currentOrdinal("ALL", "TITLE", "ASCENDING", "media-a"));

        assertThrows(
                IllegalArgumentException.class,
                () -> dao.publishOrder(
                        orderHeader("order-invalid", 2L, 2, 220L),
                        List.of(
                                orderRow("order-invalid", 0, "media-a"),
                                orderRow("order-invalid", 1, "media-a")
                        )
                )
        );
        assertEquals("order-1", dao.currentOrderHeader("ALL", "TITLE", "ASCENDING").id);

        dao.publishOrder(
                orderHeader("order-2", 2L, 2, 230L),
                List.of(orderRow("order-2", 0, "media-a"), orderRow("order-2", 1, "media-c"))
        );

        assertEquals("order-2", dao.currentOrderHeader("ALL", "TITLE", "ASCENDING").id);
        List<IndexEntities.MaterializedOrderRowEntity> page =
                dao.currentOrderPage("ALL", "TITLE", "ASCENDING", 1, 1);
        assertEquals(1, page.size());
        assertEquals("media-c", page.get(0).mediaId);
        assertNull(dao.currentOrdinal("ALL", "TITLE", "ASCENDING", "media-b"));
    }

    @Test
    public void twoThousandFiveHundredMediaCommitAndPageWithinBudget() {
        long startedNs = System.nanoTime();
        dao.beginScan(SOURCE_ID, 1L, 300L);
        dao.upsertFolders(List.of(folder(1L, 2_500)));

        List<IndexEntities.MediaEntity> media = new ArrayList<>(2_500);
        List<IndexEntities.MaterializedOrderRowEntity> rows = new ArrayList<>(2_500);
        for (int index = 0; index < 2_500; index++) {
            String id = String.format("media-%04d", index);
            String title = String.format("title-%04d", index);
            media.add(media(id, 1L, title, title));
            rows.add(orderRow("order-large", index, id));
        }
        dao.upsertMedia(media);
        dao.commitScan(SOURCE_ID, 1L, 310L);
        dao.publishOrder(orderHeader("order-large", 1L, 2_500, 320L), rows);
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;

        assertEquals(2_500, dao.currentMediaCount(SOURCE_ID));
        assertEquals(2_500, dao.currentMediaLibrarySnapshot().size());
        List<IndexEntities.MediaEntity> lastPage = dao.currentMediaPage(SOURCE_ID, 25, 2_475);
        assertEquals(25, lastPage.size());
        assertEquals("media-2475", lastPage.get(0).id);
        assertEquals("media-2499", lastPage.get(24).id);
        assertEquals(
                Integer.valueOf(1_999),
                dao.currentOrdinal("ALL", "TITLE", "ASCENDING", "media-1999")
        );
        assertTrue("2,500-row database baseline took " + elapsedMs + " ms", elapsedMs < 15_000L);
    }

    private static IndexEntities.SourceEntity source(Long generation, long updatedAtEpochMs) {
        return new IndexEntities.SourceEntity(
                SOURCE_ID,
                "LOCAL",
                "Synthetic source",
                "content://fixture/root",
                null,
                generation,
                100L,
                updatedAtEpochMs
        );
    }

    private static IndexEntities.FolderEntity folder(long generation, int mediaCount) {
        return new IndexEntities.FolderEntity(
                FOLDER_ID,
                generation,
                SOURCE_ID,
                null,
                "",
                "Synthetic root",
                mediaCount,
                mediaCount
        );
    }

    private static IndexEntities.MediaEntity media(
            String id,
            long generation,
            String displayName,
            String normalizedTitle
    ) {
        return new IndexEntities.MediaEntity(
                id,
                generation,
                SOURCE_ID,
                FOLDER_ID,
                "stable-" + id,
                "content://fixture/" + id,
                displayName,
                normalizedTitle,
                "/" + normalizedTitle + ".mp4",
                1_024L,
                10L,
                60_000L,
                1_920,
                1_080,
                30.0,
                "video/mp4",
                "h264",
                "aac",
                0,
                "READY"
        );
    }

    private static IndexEntities.ScriptEntity script(String id, String mediaId, long generation) {
        return new IndexEntities.ScriptEntity(
                id,
                generation,
                SOURCE_ID,
                mediaId,
                "content://fixture/" + id,
                "media-a",
                null,
                256L,
                10L,
                "VALID",
                10
        );
    }

    private static IndexEntities.MaterializedOrderHeaderEntity orderHeader(
            String id,
            long generation,
            int rowCount,
            long generatedAtEpochMs
    ) {
        return new IndexEntities.MaterializedOrderHeaderEntity(
                id,
                "ALL",
                "TITLE",
                "ASCENDING",
                generation,
                rowCount,
                false,
                null,
                "stable-v1",
                generatedAtEpochMs
        );
    }

    private static IndexEntities.MaterializedOrderRowEntity orderRow(
            String headerId,
            int ordinal,
            String mediaId
    ) {
        return new IndexEntities.MaterializedOrderRowEntity(headerId, ordinal, mediaId);
    }
}
