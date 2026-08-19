package io.github.fplayer.core.index.sort;

import static org.junit.Assert.assertEquals;

import io.github.fplayer.core.index.SortDirection;
import io.github.fplayer.core.index.SortField;
import io.github.fplayer.core.index.SortSpec;
import io.github.fplayer.core.index.db.IndexEntities;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;

public final class StableMediaSorterTest {
    @Test public void allFieldsAreBidirectionalAndTiesUseStableKeys() {
        IndexEntities.MediaEntity beta = media("b", "Beta", "/z", 200L, 2_000L, 1_000L, 1920, 1080, "READY");
        IndexEntities.MediaEntity alpha = media("a", "Alpha", "/a", 100L, 1_000L, 2_000L, 1280, 720, "UNPROBED");
        IndexEntities.MediaEntity tie = media("c", "Alpha", "/b", 100L, 1_000L, 3_000L, 1280, 720, "FAILED");
        List<IndexEntities.MediaEntity> input = List.of(beta, tie, alpha);
        assertEquals(List.of("a", "c", "b"), ids(StableMediaSorter.sort(input, new SortSpec(SortField.TITLE, SortDirection.ASCENDING), new HashMap<>())));
        assertEquals(List.of("b", "a", "c"), ids(StableMediaSorter.sort(input, new SortSpec(SortField.TITLE, SortDirection.DESCENDING), new HashMap<>())));
        assertEquals(List.of("b", "a", "c"), ids(StableMediaSorter.sort(input, new SortSpec(SortField.SIZE, SortDirection.DESCENDING), new HashMap<>())));
        assertEquals(List.of("b", "a", "c"), ids(StableMediaSorter.sort(input, new SortSpec(SortField.RESOLUTION, SortDirection.DESCENDING), new HashMap<>())));
    }

    @Test public void nullValuesStayLastInBothDirections() {
        IndexEntities.MediaEntity known = media("known", "Known", "/known", 1L, 1L, 1L, 1, 1, "READY");
        IndexEntities.MediaEntity unknown = new IndexEntities.MediaEntity("unknown", 1, "source", "folder", "unknown",
                "content://unknown", "Unknown", "unknown", "/unknown", null, null, null, null, null, null,
                "video/mp4", null, null, null, "UNPROBED");
        assertEquals(List.of("known", "unknown"), ids(StableMediaSorter.sort(List.of(unknown, known), new SortSpec(SortField.SIZE, SortDirection.ASCENDING), new HashMap<>())));
        assertEquals(List.of("known", "unknown"), ids(StableMediaSorter.sort(List.of(unknown, known), new SortSpec(SortField.SIZE, SortDirection.DESCENDING), new HashMap<>())));
    }

    private static List<String> ids(List<IndexEntities.MediaEntity> values) { return values.stream().map(m -> m.id).toList(); }

    private static IndexEntities.MediaEntity media(String id, String title, String path, long size, long modified,
                                                   long duration, int width, int height, String status) {
        return new IndexEntities.MediaEntity(id, 1, "source", "folder", id, "content://" + id, title,
                title.toLowerCase(java.util.Locale.ROOT), path, size, modified, duration, width, height, 30.0,
                "video/mp4", "h264", "aac", 0, status);
    }
}
