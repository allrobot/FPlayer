package io.github.fplayer.core.index.db;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

public final class IndexEntities {
    private IndexEntities() {}

    @Entity(
            tableName = "source",
            primaryKeys = {"id"},
            indices = @Index(value = {"type", "rootLocator"}, unique = true)
    )
    public static final class SourceEntity {
        @NonNull public final String id;
        @NonNull public final String type;
        @NonNull public final String displayName;
        @NonNull public final String rootLocator;
        @Nullable public final String authRef;
        @Nullable public final Long currentScanGeneration;
        public final long createdAtEpochMs;
        public final long updatedAtEpochMs;

        public SourceEntity(
                String id,
                String type,
                String displayName,
                String rootLocator,
                @Nullable String authRef,
                @Nullable Long currentScanGeneration,
                long createdAtEpochMs,
                long updatedAtEpochMs
        ) {
            this.id = id;
            this.type = type;
            this.displayName = displayName;
            this.rootLocator = rootLocator;
            this.authRef = authRef;
            this.currentScanGeneration = currentScanGeneration;
            this.createdAtEpochMs = createdAtEpochMs;
            this.updatedAtEpochMs = updatedAtEpochMs;
        }
    }

    @Entity(
            tableName = "folder",
            primaryKeys = {"id", "scanGeneration"},
            foreignKeys = @ForeignKey(
                    entity = SourceEntity.class,
                    parentColumns = "id",
                    childColumns = "sourceId",
                    onDelete = ForeignKey.CASCADE
            ),
            indices = {
                    @Index("sourceId"),
                    @Index(value = {"sourceId", "scanGeneration"}),
                    @Index(value = {"sourceId", "scanGeneration", "normalizedPath"}, unique = true)
            }
    )
    public static final class FolderEntity {
        @NonNull public final String id;
        public final long scanGeneration;
        @NonNull public final String sourceId;
        @Nullable public final String parentId;
        @NonNull public final String normalizedPath;
        @NonNull public final String displayName;
        public final int directMediaCount;
        public final int descendantMediaCount;

        public FolderEntity(
                String id,
                long scanGeneration,
                String sourceId,
                @Nullable String parentId,
                String normalizedPath,
                String displayName,
                int directMediaCount,
                int descendantMediaCount
        ) {
            this.id = id;
            this.scanGeneration = scanGeneration;
            this.sourceId = sourceId;
            this.parentId = parentId;
            this.normalizedPath = normalizedPath;
            this.displayName = displayName;
            this.directMediaCount = directMediaCount;
            this.descendantMediaCount = descendantMediaCount;
        }
    }

    @Entity(
            tableName = "media",
            primaryKeys = {"id", "scanGeneration"},
            foreignKeys = {
                    @ForeignKey(
                            entity = SourceEntity.class,
                            parentColumns = "id",
                            childColumns = "sourceId",
                            onDelete = ForeignKey.CASCADE
                    ),
                    @ForeignKey(
                            entity = FolderEntity.class,
                            parentColumns = {"id", "scanGeneration"},
                            childColumns = {"folderId", "scanGeneration"},
                            onDelete = ForeignKey.CASCADE
                    )
            },
            indices = {
                    @Index("sourceId"),
                    @Index(value = {"folderId", "scanGeneration"}),
                    @Index(value = {"sourceId", "scanGeneration"}),
                    @Index(value = {"sourceId", "scanGeneration", "stableKey"}, unique = true),
                    @Index(value = {"sourceId", "scanGeneration", "normalizedTitle", "id"})
            }
    )
    public static final class MediaEntity {
        @NonNull public final String id;
        public final long scanGeneration;
        @NonNull public final String sourceId;
        @NonNull public final String folderId;
        @NonNull public final String stableKey;
        @NonNull public final String locator;
        @NonNull public final String displayName;
        @NonNull public final String normalizedTitle;
        @NonNull public final String normalizedPath;
        @Nullable public final Long sizeBytes;
        @Nullable public final Long modifiedAtEpochMs;
        @Nullable public final Long durationMs;
        @Nullable public final Integer width;
        @Nullable public final Integer height;
        @Nullable public final Double frameRate;
        @Nullable public final String mimeType;
        @Nullable public final String videoCodec;
        @Nullable public final String audioCodec;
        @Nullable public final Integer subtitleCount;
        @NonNull public final String probeStatus;

        public MediaEntity(
                String id,
                long scanGeneration,
                String sourceId,
                String folderId,
                String stableKey,
                String locator,
                String displayName,
                String normalizedTitle,
                String normalizedPath,
                @Nullable Long sizeBytes,
                @Nullable Long modifiedAtEpochMs,
                @Nullable Long durationMs,
                @Nullable Integer width,
                @Nullable Integer height,
                @Nullable Double frameRate,
                @Nullable String mimeType,
                @Nullable String videoCodec,
                @Nullable String audioCodec,
                @Nullable Integer subtitleCount,
                String probeStatus
        ) {
            this.id = id;
            this.scanGeneration = scanGeneration;
            this.sourceId = sourceId;
            this.folderId = folderId;
            this.stableKey = stableKey;
            this.locator = locator;
            this.displayName = displayName;
            this.normalizedTitle = normalizedTitle;
            this.normalizedPath = normalizedPath;
            this.sizeBytes = sizeBytes;
            this.modifiedAtEpochMs = modifiedAtEpochMs;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.mimeType = mimeType;
            this.videoCodec = videoCodec;
            this.audioCodec = audioCodec;
            this.subtitleCount = subtitleCount;
            this.probeStatus = probeStatus;
        }
    }

    @Entity(
            tableName = "script",
            primaryKeys = {"id", "scanGeneration"},
            foreignKeys = {
                    @ForeignKey(
                            entity = SourceEntity.class,
                            parentColumns = "id",
                            childColumns = "sourceId",
                            onDelete = ForeignKey.CASCADE
                    ),
                    @ForeignKey(
                            entity = MediaEntity.class,
                            parentColumns = {"id", "scanGeneration"},
                            childColumns = {"mediaId", "scanGeneration"},
                            onDelete = ForeignKey.CASCADE
                    )
            },
            indices = {
                    @Index("sourceId"),
                    @Index(value = {"mediaId", "scanGeneration"}),
                    @Index(value = {"sourceId", "scanGeneration", "normalizedBasename"})
            }
    )
    public static final class ScriptEntity {
        @NonNull public final String id;
        public final long scanGeneration;
        @NonNull public final String sourceId;
        @Nullable public final String mediaId;
        @NonNull public final String locator;
        @NonNull public final String normalizedBasename;
        @Nullable public final String axis;
        @Nullable public final Long sizeBytes;
        @Nullable public final Long modifiedAtEpochMs;
        @NonNull public final String parseStatus;
        public final int actionCount;

        public ScriptEntity(
                String id,
                long scanGeneration,
                String sourceId,
                @Nullable String mediaId,
                String locator,
                String normalizedBasename,
                @Nullable String axis,
                @Nullable Long sizeBytes,
                @Nullable Long modifiedAtEpochMs,
                String parseStatus,
                int actionCount
        ) {
            this.id = id;
            this.scanGeneration = scanGeneration;
            this.sourceId = sourceId;
            this.mediaId = mediaId;
            this.locator = locator;
            this.normalizedBasename = normalizedBasename;
            this.axis = axis;
            this.sizeBytes = sizeBytes;
            this.modifiedAtEpochMs = modifiedAtEpochMs;
            this.parseStatus = parseStatus;
            this.actionCount = actionCount;
        }
    }

    @Entity(tableName = "playback_state", primaryKeys = {"mediaId"})
    public static final class PlaybackStateEntity {
        @NonNull public final String mediaId;
        public final long resumeMs;
        @Nullable public final Long lastPlayedAtEpochMs;
        public final long uniqueForegroundPlayedMs;
        public final int coveragePermille;
        public final int completionCount;
        public final int manualReplayCount;
        public final int foregroundLoopCount;
        public final int backgroundLoopCount;
        public final int skippedCount;
        public final boolean liked;
        public final boolean disliked;
        public final boolean favorite;

        public PlaybackStateEntity(
                String mediaId,
                long resumeMs,
                @Nullable Long lastPlayedAtEpochMs,
                long uniqueForegroundPlayedMs,
                int coveragePermille,
                int completionCount,
                int manualReplayCount,
                int foregroundLoopCount,
                int backgroundLoopCount,
                int skippedCount,
                boolean liked,
                boolean disliked,
                boolean favorite
        ) {
            this.mediaId = mediaId;
            this.resumeMs = resumeMs;
            this.lastPlayedAtEpochMs = lastPlayedAtEpochMs;
            this.uniqueForegroundPlayedMs = uniqueForegroundPlayedMs;
            this.coveragePermille = coveragePermille;
            this.completionCount = completionCount;
            this.manualReplayCount = manualReplayCount;
            this.foregroundLoopCount = foregroundLoopCount;
            this.backgroundLoopCount = backgroundLoopCount;
            this.skippedCount = skippedCount;
            this.liked = liked;
            this.disliked = disliked;
            this.favorite = favorite;
        }
    }

    @Entity(
            tableName = "interaction_event",
            primaryKeys = {"id"},
            indices = {
                    @Index(value = {"mediaId", "occurredAtEpochMs"}),
                    @Index(value = {"type", "occurredAtEpochMs"})
            }
    )
    public static final class InteractionEventEntity {
        @NonNull public final String id;
        @NonNull public final String mediaId;
        @NonNull public final String type;
        public final long occurredAtEpochMs;
        @Nullable public final String payloadJson;

        public InteractionEventEntity(
                String id,
                String mediaId,
                String type,
                long occurredAtEpochMs,
                @Nullable String payloadJson
        ) {
            this.id = id;
            this.mediaId = mediaId;
            this.type = type;
            this.occurredAtEpochMs = occurredAtEpochMs;
            this.payloadJson = payloadJson;
        }
    }

    @Entity(tableName = "recommendation_aggregate", primaryKeys = {"mediaId"})
    public static final class RecommendationAggregateEntity {
        @NonNull public final String mediaId;
        public final long totalForegroundPlayedMs;
        public final int completionCount;
        public final int manualReplayCount;
        public final int foregroundLoopCount;
        public final int backgroundLoopCount;
        public final int skippedCount;
        public final double decayedPositive;
        public final double decayedNegative;
        public final long updatedAtEpochMs;

        public RecommendationAggregateEntity(
                String mediaId,
                long totalForegroundPlayedMs,
                int completionCount,
                int manualReplayCount,
                int foregroundLoopCount,
                int backgroundLoopCount,
                int skippedCount,
                double decayedPositive,
                double decayedNegative,
                long updatedAtEpochMs
        ) {
            this.mediaId = mediaId;
            this.totalForegroundPlayedMs = totalForegroundPlayedMs;
            this.completionCount = completionCount;
            this.manualReplayCount = manualReplayCount;
            this.foregroundLoopCount = foregroundLoopCount;
            this.backgroundLoopCount = backgroundLoopCount;
            this.skippedCount = skippedCount;
            this.decayedPositive = decayedPositive;
            this.decayedNegative = decayedNegative;
            this.updatedAtEpochMs = updatedAtEpochMs;
        }
    }

    @Entity(
            tableName = "thumbnail",
            primaryKeys = {"id", "scanGeneration"},
            foreignKeys = @ForeignKey(
                    entity = SourceEntity.class,
                    parentColumns = "id",
                    childColumns = "sourceId",
                    onDelete = ForeignKey.CASCADE
            ),
            indices = {
                    @Index("sourceId"),
                    @Index(value = {"mediaId", "scanGeneration"}),
                    @Index(value = {"folderId", "scanGeneration"}),
                    @Index("cacheKey")
            }
    )
    public static final class ThumbnailEntity {
        @NonNull public final String id;
        public final long scanGeneration;
        @NonNull public final String sourceId;
        @Nullable public final String mediaId;
        @Nullable public final String folderId;
        @NonNull public final String cacheKey;
        @NonNull public final String status;
        @Nullable public final Long generatedAtEpochMs;

        public ThumbnailEntity(
                String id,
                long scanGeneration,
                String sourceId,
                @Nullable String mediaId,
                @Nullable String folderId,
                String cacheKey,
                String status,
                @Nullable Long generatedAtEpochMs
        ) {
            this.id = id;
            this.scanGeneration = scanGeneration;
            this.sourceId = sourceId;
            this.mediaId = mediaId;
            this.folderId = folderId;
            this.cacheKey = cacheKey;
            this.status = status;
            this.generatedAtEpochMs = generatedAtEpochMs;
        }
    }

    @Entity(
            tableName = "scan_generation",
            primaryKeys = {"sourceId", "generation"},
            foreignKeys = @ForeignKey(
                    entity = SourceEntity.class,
                    parentColumns = "id",
                    childColumns = "sourceId",
                    onDelete = ForeignKey.CASCADE
            ),
            indices = {
                    @Index("sourceId"),
                    @Index(value = {"sourceId", "status"})
            }
    )
    public static final class ScanGenerationEntity {
        @NonNull public final String sourceId;
        public final long generation;
        @NonNull public final String status;
        public final long startedAtEpochMs;
        @Nullable public final Long completedAtEpochMs;
        public final int discoveredFolderCount;
        public final int discoveredMediaCount;
        public final int discoveredScriptCount;
        @Nullable public final String failureCode;

        public ScanGenerationEntity(
                String sourceId,
                long generation,
                String status,
                long startedAtEpochMs,
                @Nullable Long completedAtEpochMs,
                int discoveredFolderCount,
                int discoveredMediaCount,
                int discoveredScriptCount,
                @Nullable String failureCode
        ) {
            this.sourceId = sourceId;
            this.generation = generation;
            this.status = status;
            this.startedAtEpochMs = startedAtEpochMs;
            this.completedAtEpochMs = completedAtEpochMs;
            this.discoveredFolderCount = discoveredFolderCount;
            this.discoveredMediaCount = discoveredMediaCount;
            this.discoveredScriptCount = discoveredScriptCount;
            this.failureCode = failureCode;
        }
    }

    @Entity(
            tableName = "materialized_order_header",
            primaryKeys = {"id"},
            indices = {
                    @Index(value = {"scopeId", "sortField", "sortDirection", "generation"}, unique = true),
                    @Index(value = {"scopeId", "sortField", "sortDirection", "isCurrent"})
            }
    )
    public static final class MaterializedOrderHeaderEntity {
        @NonNull public final String id;
        @NonNull public final String scopeId;
        @NonNull public final String sortField;
        @NonNull public final String sortDirection;
        public final long generation;
        public final int rowCount;
        public final boolean isCurrent;
        @Nullable public final Long randomSeed;
        @Nullable public final String algorithmVersion;
        @ColumnInfo(defaultValue = "0") public final long generatedAtEpochMs;

        public MaterializedOrderHeaderEntity(
                String id,
                String scopeId,
                String sortField,
                String sortDirection,
                long generation,
                int rowCount,
                boolean isCurrent,
                @Nullable Long randomSeed,
                @Nullable String algorithmVersion,
                long generatedAtEpochMs
        ) {
            this.id = id;
            this.scopeId = scopeId;
            this.sortField = sortField;
            this.sortDirection = sortDirection;
            this.generation = generation;
            this.rowCount = rowCount;
            this.isCurrent = isCurrent;
            this.randomSeed = randomSeed;
            this.algorithmVersion = algorithmVersion;
            this.generatedAtEpochMs = generatedAtEpochMs;
        }
    }

    @Entity(
            tableName = "materialized_order_row",
            primaryKeys = {"headerId", "ordinal"},
            foreignKeys = @ForeignKey(
                    entity = MaterializedOrderHeaderEntity.class,
                    parentColumns = "id",
                    childColumns = "headerId",
                    onDelete = ForeignKey.CASCADE
            ),
            indices = {
                    @Index("headerId"),
                    @Index(value = {"headerId", "mediaId"}, unique = true)
            }
    )
    public static final class MaterializedOrderRowEntity {
        @NonNull public final String headerId;
        public final int ordinal;
        @NonNull public final String mediaId;

        public MaterializedOrderRowEntity(String headerId, int ordinal, String mediaId) {
            this.headerId = headerId;
            this.ordinal = ordinal;
            this.mediaId = mediaId;
        }
    }
}
