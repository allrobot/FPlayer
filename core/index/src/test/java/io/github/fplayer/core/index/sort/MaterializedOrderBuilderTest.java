package io.github.fplayer.core.index.sort;

import static org.junit.Assert.assertEquals;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import io.github.fplayer.core.index.SortDirection;
import io.github.fplayer.core.index.SortField;
import io.github.fplayer.core.index.SortSpec;
import io.github.fplayer.core.index.db.FPlayerIndexDatabase;
import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class MaterializedOrderBuilderTest {
    private FPlayerIndexDatabase database;
    private IndexDao dao;
    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class).allowMainThreadQueries().build();
        dao = database.indexDao();
        dao.insertSource(new IndexEntities.SourceEntity("source", "LOCAL", "Fixture", "content://root", null, null, 1, 1));
        dao.beginScan("source", 1, 1);
        dao.upsertFolders(List.of(new IndexEntities.FolderEntity("folder", 1, "source", null, "/", "Root", 2, 2)));
        dao.upsertMedia(List.of(media("a", "Alpha"), media("b", "Beta")));
        dao.commitScan("source", 1, 2);
    }
    @After public void tearDown() { database.close(); }
    @Test public void sortingPublishesAndCurrentMediaRemainsReadable() {
        MaterializedOrderBuilder builder = new MaterializedOrderBuilder(dao);
        builder.buildAndPublish("source", "ALL", new SortSpec(SortField.TITLE, SortDirection.ASCENDING), 1, 3);
        assertEquals("a", dao.currentOrderPage("ALL", "TITLE", "ASCENDING", 2, 0).get(0).mediaId);
        builder.buildAndPublish("source", "ALL", new SortSpec(SortField.TITLE, SortDirection.DESCENDING), 2, 4);
        assertEquals("b", dao.currentOrderPage("ALL", "TITLE", "DESCENDING", 2, 0).get(0).mediaId);
        assertEquals("Alpha", dao.currentMedia("a").displayName);
        assertEquals(Integer.valueOf(1), dao.currentOrdinal("ALL", "TITLE", "DESCENDING", "a"));
    }
    private static IndexEntities.MediaEntity media(String id, String title) {
        return new IndexEntities.MediaEntity(id, 1, "source", "folder", id, "content://" + id, title,
                title.toLowerCase(java.util.Locale.ROOT), "/" + id + ".mp4", 1L, 1L, 1L,
                1, 1, 1.0, "video/mp4", null, null, 0, "READY");
    }
}
