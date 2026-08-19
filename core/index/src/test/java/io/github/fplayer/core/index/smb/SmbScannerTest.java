package io.github.fplayer.core.index.smb;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import io.github.fplayer.core.index.db.FPlayerIndexDatabase;
import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;
import io.github.fplayer.core.index.saf.LocalSafScanner;
import io.github.fplayer.core.index.saf.SafDocumentTree;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class SmbScannerTest {
    private FPlayerIndexDatabase database;
    private IndexDao dao;

    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class).allowMainThreadQueries().build();
        dao = database.indexDao();
        SmbSourceConfig config = SmbSourceConfig.defaults("smb-source", "NAS", "nas", "media", "cred");
        new SmbSourceRepository(dao).create(config, 1L);
    }

    @After public void tearDown() { database.close(); }

    @Test public void offlineDoesNotAdvanceCurrentGeneration() throws IOException {
        SmbScanner.SmbScanResult result = new SmbScanner(dao, () -> 2L)
                .scan(SmbSourceConfig.defaults("smb-source", "NAS", "nas", "media", "cred"), 1L,
                        new FixtureTree(false), LocalSafScanner.Cancellation.NEVER);
        assertEquals(SmbScanner.SmbScanResult.State.OFFLINE, result.state);
        assertEquals(null, dao.source("smb-source").currentScanGeneration);
    }

    @Test public void onlineScanPublishesGenerationAndUsesSmbType() throws IOException {
        SmbScanner.SmbScanResult result = new SmbScanner(dao, () -> 2L)
                .scan(SmbSourceConfig.defaults("smb-source", "NAS", "nas", "media", "cred"), 1L,
                        new FixtureTree(true), LocalSafScanner.Cancellation.NEVER);
        assertEquals(SmbScanner.SmbScanResult.State.ONLINE, result.state);
        assertTrue(result.scan.committed);
        assertEquals(1, dao.currentMediaCount("smb-source"));
        assertEquals("SMB", dao.source("smb-source").type);
    }

    @Test public void disconnectDuringDiscoveryKeepsLastCommittedSnapshot() throws IOException {
        SmbSourceConfig config = SmbSourceConfig.defaults("smb-source", "NAS", "nas", "media", "cred");
        SmbScanner scanner = new SmbScanner(dao, () -> 2L);
        assertEquals(SmbScanner.SmbScanResult.State.ONLINE,
                scanner.scan(config, 1L, new FixtureTree(true), LocalSafScanner.Cancellation.NEVER).state);

        SmbScanner.SmbScanResult failed = scanner.scan(
                config, 2L, new DisconnectingTree(), LocalSafScanner.Cancellation.NEVER);

        assertEquals(SmbScanner.SmbScanResult.State.FAILED, failed.state);
        assertEquals("SMB_IO_FAILED", failed.failureCode);
        assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus("smb-source", 2L));
        assertEquals(Long.valueOf(1L), dao.source("smb-source").currentScanGeneration);
        assertEquals(1, dao.currentMediaCount("smb-source"));
    }

    @Test public void ioFailureAfterRootListingIsRedactedAndPreservesCommittedSnapshot() throws IOException {
        SmbSourceConfig config = SmbSourceConfig.defaults("smb-source", "NAS", "nas", "media", "cred");
        SmbScanner scanner = new SmbScanner(dao, () -> 2L);
        assertEquals(SmbScanner.SmbScanResult.State.ONLINE,
                scanner.scan(config, 1L, new FixtureTree(true), LocalSafScanner.Cancellation.NEVER).state);

        SmbScanner.SmbScanResult failed = scanner.scan(
                config, 2L, new FlatDisconnectingTree(), LocalSafScanner.Cancellation.NEVER);

        assertEquals(SmbScanner.SmbScanResult.State.FAILED, failed.state);
        assertEquals("SMB_IO_FAILED", failed.failureCode);
        assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus("smb-source", 2L));
        assertEquals(Long.valueOf(1L), dao.source("smb-source").currentScanGeneration);
        assertEquals(1, dao.currentMediaCount("smb-source"));

        SmbScanner.SmbScanResult recovered = scanner.scan(
                config, 3L, new FixtureTree(true), LocalSafScanner.Cancellation.NEVER);
        assertEquals(SmbScanner.SmbScanResult.State.ONLINE, recovered.state);
        assertTrue(recovered.scan.committed);
        assertEquals(Long.valueOf(3L), dao.source("smb-source").currentScanGeneration);
        assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus("smb-source", 2L));
        assertEquals(1, dao.currentMediaCount("smb-source"));
    }

    private static final class FixtureTree implements SmbDocumentTree {
        private final boolean online;
        FixtureTree(boolean online) { this.online = online; }
        @Override public boolean isOnline() { return online; }
        @Override public Entry root() { return new Entry("root", "smb://nas:445/media", "Root", "vnd.android.document/directory", true, null, null); }
        @Override public List<Entry> children(Entry directory) {
            return List.of(new Entry("video", "smb://nas:445/media/video.mp4", "video.mp4", "video/mp4", false, 10L, 1L));
        }
    }

    private static final class DisconnectingTree implements SmbDocumentTree {
        private final Entry root = new Entry("root", "smb://fixture/root", "Root",
                "vnd.android.document/directory", true, null, null);
        private final Entry folder = new Entry("folder", "smb://fixture/folder", "Folder",
                "vnd.android.document/directory", true, null, null);
        @Override public boolean isOnline() { return true; }
        @Override public Entry root() { return root; }
        @Override public List<Entry> children(Entry directory) throws IOException {
            if (directory == root) return List.of(folder);
            throw new IOException("synthetic disconnect");
        }
    }

    private static final class FlatDisconnectingTree implements SmbDocumentTree {
        private final Entry root = new Entry("root", "smb://fixture/root", "Root",
                "vnd.android.document/directory", true, null, null);
        @Override public boolean isOnline() { return true; }
        @Override public Entry root() { return root; }
        @Override public List<Entry> children(Entry directory) throws IOException {
            throw new IOException("synthetic connection cut");
        }
    }
}
