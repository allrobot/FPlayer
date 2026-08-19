package io.github.fplayer.core.index.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.database.Cursor;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class IndexMigrationTest {
    private static final String DATABASE_NAME = "migration-v1-v2.db";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DATABASE_NAME);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DATABASE_NAME);
    }

    @Test
    public void migrationOneToTwoPreservesOrderAndAddsReproducibilityFields() {
        LegacyV1Database legacy = Room.databaseBuilder(
                        context,
                        LegacyV1Database.class,
                        DATABASE_NAME
                )
                .allowMainThreadQueries()
                .build();
        SupportSQLiteDatabase legacySql = legacy.getOpenHelper().getWritableDatabase();
        legacySql.execSQL(
                "INSERT INTO materialized_order_header " +
                        "(id, scopeId, sortField, sortDirection, generation, rowCount, isCurrent) " +
                        "VALUES ('legacy-order', 'ALL', 'TITLE', 'ASCENDING', 7, 1, 1)"
        );
        legacySql.execSQL(
                "INSERT INTO materialized_order_row (headerId, ordinal, mediaId) " +
                        "VALUES ('legacy-order', 0, 'media-a')"
        );
        legacy.close();

        FPlayerIndexDatabase migrated = Room.databaseBuilder(
                        context,
                        FPlayerIndexDatabase.class,
                        DATABASE_NAME
                )
                .addMigrations(FPlayerIndexDatabase.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build();

        IndexEntities.MaterializedOrderHeaderEntity header = migrated.indexDao()
                .currentOrderHeader("ALL", "TITLE", "ASCENDING");
        assertNotNull(header);
        assertEquals("legacy-order", header.id);
        assertEquals(7L, header.generation);
        assertNull(header.randomSeed);
        assertNull(header.algorithmVersion);
        assertEquals(0L, header.generatedAtEpochMs);
        assertEquals(
                "media-a",
                migrated.indexDao()
                        .currentOrderPage("ALL", "TITLE", "ASCENDING", 10, 0)
                        .get(0)
                        .mediaId
        );

        Cursor cursor = migrated.getOpenHelper().getReadableDatabase().query(
                "SELECT randomSeed, algorithmVersion, generatedAtEpochMs " +
                        "FROM materialized_order_header WHERE id = 'legacy-order'"
        );
        try {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(Cursor.FIELD_TYPE_NULL, cursor.getType(0));
            assertEquals(Cursor.FIELD_TYPE_NULL, cursor.getType(1));
            assertEquals(0L, cursor.getLong(2));
        } finally {
            cursor.close();
            migrated.close();
        }
    }
}
