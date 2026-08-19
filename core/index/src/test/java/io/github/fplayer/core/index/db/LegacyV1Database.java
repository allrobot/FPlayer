package io.github.fplayer.core.index.db;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                IndexEntities.SourceEntity.class,
                IndexEntities.FolderEntity.class,
                IndexEntities.MediaEntity.class,
                IndexEntities.ScriptEntity.class,
                IndexEntities.PlaybackStateEntity.class,
                IndexEntities.InteractionEventEntity.class,
                IndexEntities.RecommendationAggregateEntity.class,
                IndexEntities.ThumbnailEntity.class,
                IndexEntities.ScanGenerationEntity.class,
                LegacyV1Database.LegacyOrderHeaderEntity.class,
                LegacyV1Database.LegacyOrderRowEntity.class
        },
        version = 1,
        exportSchema = true
)
abstract class LegacyV1Database extends RoomDatabase {
    @Entity(
            tableName = "materialized_order_header",
            primaryKeys = {"id"},
            indices = {
                    @Index(value = {"scopeId", "sortField", "sortDirection", "generation"}, unique = true),
                    @Index(value = {"scopeId", "sortField", "sortDirection", "isCurrent"})
            }
    )
    static final class LegacyOrderHeaderEntity {
        @NonNull public final String id;
        @NonNull public final String scopeId;
        @NonNull public final String sortField;
        @NonNull public final String sortDirection;
        public final long generation;
        public final int rowCount;
        public final boolean isCurrent;

        LegacyOrderHeaderEntity(
                String id,
                String scopeId,
                String sortField,
                String sortDirection,
                long generation,
                int rowCount,
                boolean isCurrent
        ) {
            this.id = id;
            this.scopeId = scopeId;
            this.sortField = sortField;
            this.sortDirection = sortDirection;
            this.generation = generation;
            this.rowCount = rowCount;
            this.isCurrent = isCurrent;
        }
    }

    @Entity(
            tableName = "materialized_order_row",
            primaryKeys = {"headerId", "ordinal"},
            foreignKeys = @ForeignKey(
                    entity = LegacyOrderHeaderEntity.class,
                    parentColumns = "id",
                    childColumns = "headerId",
                    onDelete = ForeignKey.CASCADE
            ),
            indices = {
                    @Index("headerId"),
                    @Index(value = {"headerId", "mediaId"}, unique = true)
            }
    )
    static final class LegacyOrderRowEntity {
        @NonNull public final String headerId;
        public final int ordinal;
        @NonNull public final String mediaId;

        LegacyOrderRowEntity(String headerId, int ordinal, String mediaId) {
            this.headerId = headerId;
            this.ordinal = ordinal;
            this.mediaId = mediaId;
        }
    }
}
