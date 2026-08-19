package io.github.fplayer.core.index.sort;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.SortSpec;
import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds and atomically publishes a stable order from the current media snapshot. */
public final class MaterializedOrderBuilder {
    private final IndexDao dao;

    public MaterializedOrderBuilder(IndexDao dao) { this.dao = dao; }

    public void buildAndPublish(@NonNull String sourceId, @NonNull String scopeId,
                                @NonNull SortSpec spec, long generation, long nowEpochMs) {
        List<IndexEntities.MediaEntity> media = dao.currentMediaSnapshot(sourceId);
        if (!"ALL".equals(scopeId)) {
            ArrayList<IndexEntities.MediaEntity> scoped = new ArrayList<>();
            for (IndexEntities.MediaEntity item : media) if (scopeId.equals(item.folderId)) scoped.add(item);
            media = scoped;
        }
        Map<String, IndexEntities.PlaybackStateEntity> playback = new HashMap<>();
        for (IndexEntities.MediaEntity item : media) {
            IndexEntities.PlaybackStateEntity state = dao.playbackState(item.id);
            if (state != null) playback.put(item.id, state);
        }
        List<IndexEntities.MediaEntity> ordered = StableMediaSorter.sort(media, spec, playback);
        String headerId = sha256(sourceId + "|" + scopeId + "|" + spec.getField() + "|" + spec.getDirection() + "|" + generation);
        ArrayList<IndexEntities.MaterializedOrderRowEntity> rows = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            rows.add(new IndexEntities.MaterializedOrderRowEntity(headerId, index, ordered.get(index).id));
        }
        dao.publishOrder(new IndexEntities.MaterializedOrderHeaderEntity(
                headerId, scopeId, spec.getField().name(), spec.getDirection().name(), generation,
                rows.size(), false, null, "stable-v1", nowEpochMs
        ), rows);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
