package io.github.fplayer.core.index.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Dao
public interface IndexDao {
    String SCAN_WORKING = "WORKING";
    String SCAN_COMPLETE = "COMPLETE";
    String SCAN_INCOMPLETE = "INCOMPLETE";

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertSource(IndexEntities.SourceEntity source);

    @Query("UPDATE source SET type = :type, displayName = :displayName, rootLocator = :rootLocator, " +
            "authRef = :authRef, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :sourceId")
    int updateSourceMetadata(
            String sourceId,
            String type,
            String displayName,
            String rootLocator,
            @Nullable String authRef,
            long updatedAtEpochMs
    );

    @Query("SELECT * FROM source WHERE id = :sourceId")
    @Nullable
    IndexEntities.SourceEntity source(String sourceId);

    @Query("SELECT * FROM source ORDER BY displayName ASC, id ASC")
    List<IndexEntities.SourceEntity> sources();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertScanGeneration(IndexEntities.ScanGenerationEntity generation);

    @Query("SELECT MAX(generation) FROM scan_generation WHERE sourceId = :sourceId")
    @Nullable
    Long latestScanGeneration(String sourceId);

    @Query("SELECT status FROM scan_generation WHERE sourceId = :sourceId AND generation = :generation")
    @Nullable
    String scanStatus(String sourceId, long generation);

    @Upsert
    void upsertFolders(List<IndexEntities.FolderEntity> folders);

    @Upsert
    void upsertMedia(List<IndexEntities.MediaEntity> media);

    @Upsert
    void upsertScripts(List<IndexEntities.ScriptEntity> scripts);

    @Upsert
    void upsertThumbnails(List<IndexEntities.ThumbnailEntity> thumbnails);

    @Query("SELECT COUNT(*) FROM folder WHERE sourceId = :sourceId AND scanGeneration = :generation")
    int stagedFolderCount(String sourceId, long generation);

    @Query("SELECT COUNT(*) FROM media WHERE sourceId = :sourceId AND scanGeneration = :generation")
    int stagedMediaCount(String sourceId, long generation);

    @Query("SELECT COUNT(*) FROM script WHERE sourceId = :sourceId AND scanGeneration = :generation")
    int stagedScriptCount(String sourceId, long generation);

    @Query("UPDATE scan_generation SET status = 'COMPLETE', completedAtEpochMs = :completedAtEpochMs, " +
            "discoveredFolderCount = :folderCount, discoveredMediaCount = :mediaCount, " +
            "discoveredScriptCount = :scriptCount, failureCode = NULL " +
            "WHERE sourceId = :sourceId AND generation = :generation AND status = 'WORKING'")
    int completeWorkingScan(
            String sourceId,
            long generation,
            long completedAtEpochMs,
            int folderCount,
            int mediaCount,
            int scriptCount
    );

    @Query("UPDATE source SET currentScanGeneration = :generation, updatedAtEpochMs = :completedAtEpochMs " +
            "WHERE id = :sourceId")
    int setCurrentScanGeneration(String sourceId, long generation, long completedAtEpochMs);

    @Query("UPDATE scan_generation SET status = 'INCOMPLETE', completedAtEpochMs = :completedAtEpochMs, " +
            "failureCode = :failureCode WHERE sourceId = :sourceId AND generation = :generation " +
            "AND status = 'WORKING'")
    int markWorkingScanIncomplete(
            String sourceId,
            long generation,
            long completedAtEpochMs,
            String failureCode
    );

    @Transaction
    default void beginScan(String sourceId, long generation, long startedAtEpochMs) {
        if (source(sourceId) == null) {
            throw new IllegalStateException("SOURCE_NOT_FOUND");
        }
        Long latest = latestScanGeneration(sourceId);
        if (latest != null && generation <= latest) {
            throw new IllegalStateException("SCAN_GENERATION_NOT_MONOTONIC");
        }
        insertScanGeneration(new IndexEntities.ScanGenerationEntity(
                sourceId,
                generation,
                SCAN_WORKING,
                startedAtEpochMs,
                null,
                0,
                0,
                0,
                null
        ));
    }

    @Transaction
    default void commitScan(String sourceId, long generation, long completedAtEpochMs) {
        int folders = stagedFolderCount(sourceId, generation);
        int media = stagedMediaCount(sourceId, generation);
        int scripts = stagedScriptCount(sourceId, generation);
        if (completeWorkingScan(
                sourceId,
                generation,
                completedAtEpochMs,
                folders,
                media,
                scripts
        ) != 1) {
            throw new IllegalStateException("SCAN_NOT_WORKING");
        }
        if (setCurrentScanGeneration(sourceId, generation, completedAtEpochMs) != 1) {
            throw new IllegalStateException("SOURCE_NOT_FOUND");
        }
    }

    @Transaction
    default void abandonScan(
            String sourceId,
            long generation,
            long completedAtEpochMs,
            String failureCode
    ) {
        if (markWorkingScanIncomplete(
                sourceId,
                generation,
                completedAtEpochMs,
                failureCode
        ) != 1) {
            throw new IllegalStateException("SCAN_NOT_WORKING");
        }
    }

    @Query("SELECT m.* FROM media m INNER JOIN source s ON s.id = m.sourceId " +
            "WHERE m.id = :mediaId AND m.scanGeneration = s.currentScanGeneration")
    @Nullable
    IndexEntities.MediaEntity currentMedia(String mediaId);

    @Query("SELECT m.* FROM media m INNER JOIN source s ON s.id = m.sourceId " +
            "WHERE m.sourceId = :sourceId AND m.scanGeneration = s.currentScanGeneration " +
            "ORDER BY m.normalizedTitle ASC, m.normalizedPath ASC, m.id ASC LIMIT :limit OFFSET :offset")
    List<IndexEntities.MediaEntity> currentMediaPage(String sourceId, int limit, int offset);

    @Query("SELECT COUNT(*) FROM media m INNER JOIN source s ON s.id = m.sourceId " +
            "WHERE m.sourceId = :sourceId AND m.scanGeneration = s.currentScanGeneration")
    int currentMediaCount(String sourceId);

    @Query("SELECT m.* FROM media m INNER JOIN source s ON s.id = m.sourceId " +
            "WHERE m.sourceId = :sourceId AND m.scanGeneration = s.currentScanGeneration " +
            "ORDER BY m.normalizedPath ASC, m.id ASC")
    List<IndexEntities.MediaEntity> currentMediaSnapshot(String sourceId);

    @Query("SELECT m.* FROM media m INNER JOIN source s ON s.id = m.sourceId " +
            "WHERE m.scanGeneration = s.currentScanGeneration " +
            "ORDER BY m.normalizedTitle ASC, m.normalizedPath ASC, m.id ASC")
    List<IndexEntities.MediaEntity> currentMediaLibrarySnapshot();

    @Query("SELECT sc.* FROM script sc INNER JOIN source s ON s.id = sc.sourceId " +
            "WHERE sc.sourceId = :sourceId AND sc.scanGeneration = s.currentScanGeneration " +
            "ORDER BY sc.locator ASC, sc.id ASC")
    List<IndexEntities.ScriptEntity> currentScriptSnapshot(String sourceId);

    @Query("SELECT sc.* FROM script sc INNER JOIN source s ON s.id = sc.sourceId " +
            "WHERE sc.mediaId = :mediaId AND sc.scanGeneration = s.currentScanGeneration " +
            "ORDER BY CASE WHEN sc.axis IS NULL THEN 0 ELSE 1 END, sc.axis ASC, sc.locator ASC")
    List<IndexEntities.ScriptEntity> currentScriptsForMedia(String mediaId);

    @Query("SELECT t.* FROM thumbnail t INNER JOIN source s ON s.id = t.sourceId " +
            "WHERE t.sourceId = :sourceId AND t.mediaId = :mediaId " +
            "AND t.scanGeneration = s.currentScanGeneration AND t.status = 'READY' " +
            "ORDER BY t.generatedAtEpochMs DESC, t.id DESC LIMIT 1")
    @Nullable
    IndexEntities.ThumbnailEntity currentThumbnailForMedia(String sourceId, String mediaId);

    @Query("SELECT t.cacheKey FROM thumbnail t INNER JOIN source s ON s.id = t.sourceId " +
            "WHERE t.sourceId = :sourceId AND t.scanGeneration = s.currentScanGeneration " +
            "AND t.status = 'READY'")
    List<String> currentThumbnailCacheKeys(String sourceId);

    @Query("SELECT f.* FROM folder f INNER JOIN source s ON s.id = f.sourceId " +
            "WHERE f.sourceId = :sourceId AND f.scanGeneration = s.currentScanGeneration " +
            "ORDER BY f.normalizedPath ASC, f.id ASC")
    List<IndexEntities.FolderEntity> currentFolders(String sourceId);

    @Upsert
    void upsertPlaybackState(IndexEntities.PlaybackStateEntity state);

    @Query("SELECT * FROM playback_state WHERE mediaId = :mediaId")
    @Nullable
    IndexEntities.PlaybackStateEntity playbackState(String mediaId);

    @Query("SELECT * FROM playback_state")
    List<IndexEntities.PlaybackStateEntity> playbackSnapshot();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertInteractionEvent(IndexEntities.InteractionEventEntity event);

    @Query("SELECT * FROM interaction_event WHERE mediaId = :mediaId " +
            "ORDER BY occurredAtEpochMs ASC, id ASC")
    List<IndexEntities.InteractionEventEntity> interactionEvents(String mediaId);

    @Query("SELECT * FROM interaction_event ORDER BY occurredAtEpochMs ASC, id ASC")
    List<IndexEntities.InteractionEventEntity> interactionSnapshot();

    @Upsert
    void upsertRecommendationAggregate(IndexEntities.RecommendationAggregateEntity aggregate);

    @Query("SELECT * FROM recommendation_aggregate WHERE mediaId = :mediaId")
    @Nullable
    IndexEntities.RecommendationAggregateEntity recommendationAggregate(String mediaId);

    @Query("SELECT * FROM recommendation_aggregate ORDER BY mediaId ASC")
    List<IndexEntities.RecommendationAggregateEntity> recommendationSnapshot();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertOrderHeader(IndexEntities.MaterializedOrderHeaderEntity header);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertOrderRows(List<IndexEntities.MaterializedOrderRowEntity> rows);

    @Query("UPDATE materialized_order_header SET isCurrent = 0 " +
            "WHERE scopeId = :scopeId AND sortField = :sortField AND sortDirection = :sortDirection " +
            "AND isCurrent = 1")
    int clearCurrentOrder(String scopeId, String sortField, String sortDirection);

    @Query("UPDATE materialized_order_header SET isCurrent = 1 WHERE id = :headerId AND isCurrent = 0")
    int activateOrder(String headerId);

    @Query("SELECT * FROM materialized_order_header WHERE scopeId = :scopeId " +
            "AND sortField = :sortField AND sortDirection = :sortDirection AND isCurrent = 1 LIMIT 1")
    @Nullable
    IndexEntities.MaterializedOrderHeaderEntity currentOrderHeader(
            String scopeId,
            String sortField,
            String sortDirection
    );

    @Query("SELECT r.* FROM materialized_order_row r " +
            "INNER JOIN materialized_order_header h ON h.id = r.headerId " +
            "WHERE h.scopeId = :scopeId AND h.sortField = :sortField " +
            "AND h.sortDirection = :sortDirection AND h.isCurrent = 1 " +
            "ORDER BY r.ordinal ASC LIMIT :limit OFFSET :offset")
    List<IndexEntities.MaterializedOrderRowEntity> currentOrderPage(
            String scopeId,
            String sortField,
            String sortDirection,
            int limit,
            int offset
    );

    @Query("SELECT r.ordinal FROM materialized_order_row r " +
            "INNER JOIN materialized_order_header h ON h.id = r.headerId " +
            "WHERE h.scopeId = :scopeId AND h.sortField = :sortField " +
            "AND h.sortDirection = :sortDirection AND h.isCurrent = 1 AND r.mediaId = :mediaId")
    @Nullable
    Integer currentOrdinal(
            String scopeId,
            String sortField,
            String sortDirection,
            String mediaId
    );

    @Transaction
    default void publishOrder(
            IndexEntities.MaterializedOrderHeaderEntity header,
            List<IndexEntities.MaterializedOrderRowEntity> rows
    ) {
        if (header.isCurrent) {
            throw new IllegalArgumentException("ORDER_MUST_BE_STAGED");
        }
        if (header.rowCount != rows.size()) {
            throw new IllegalArgumentException("ORDER_ROW_COUNT_MISMATCH");
        }
        Set<String> mediaIds = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            IndexEntities.MaterializedOrderRowEntity row = rows.get(index);
            if (!header.id.equals(row.headerId) || row.ordinal != index) {
                throw new IllegalArgumentException("ORDER_ROWS_NOT_CONTIGUOUS");
            }
            if (!mediaIds.add(row.mediaId)) {
                throw new IllegalArgumentException("ORDER_MEDIA_DUPLICATE");
            }
        }
        insertOrderHeader(header);
        if (!rows.isEmpty()) {
            insertOrderRows(rows);
        }
        clearCurrentOrder(header.scopeId, header.sortField, header.sortDirection);
        if (activateOrder(header.id) != 1) {
            throw new IllegalStateException("ORDER_ACTIVATION_FAILED");
        }
    }
}
