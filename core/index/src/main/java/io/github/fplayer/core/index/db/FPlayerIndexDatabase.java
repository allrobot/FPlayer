package io.github.fplayer.core.index.db;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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
                IndexEntities.MaterializedOrderHeaderEntity.class,
                IndexEntities.MaterializedOrderRowEntity.class
        },
        version = 2,
        exportSchema = true
)
public abstract class FPlayerIndexDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "fplayer-index.db";

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE materialized_order_header ADD COLUMN randomSeed INTEGER"
            );
            database.execSQL(
                    "ALTER TABLE materialized_order_header ADD COLUMN algorithmVersion TEXT"
            );
            database.execSQL(
                    "ALTER TABLE materialized_order_header " +
                            "ADD COLUMN generatedAtEpochMs INTEGER NOT NULL DEFAULT 0"
            );
        }
    };

    public static final Migration[] ALL_MIGRATIONS = {MIGRATION_1_2};

    @NonNull
    public static FPlayerIndexDatabase open(@NonNull Context context) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        FPlayerIndexDatabase.class,
                        DATABASE_NAME
                )
                .addMigrations(ALL_MIGRATIONS)
                .build();
    }

    public abstract IndexDao indexDao();
}
