package io.github.fplayer.core.index.smb;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import io.github.fplayer.core.index.db.FPlayerIndexDatabase;
import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.saf.LocalSafScanner;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Runtime-gated acceptance against a user-authorized SMB fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RealSmbAcceptanceTest {
    private static final String SOURCE_ID = "t32-real-smb";
    private static final String CREDENTIAL_REF = "t32-runtime";

    @Test public void realScanAndConnectionCutPreserveCommittedSnapshot() {
        Assume.assumeTrue("REAL_SMB_ACCEPTANCE_DISABLED",
                "true".equalsIgnoreCase(System.getenv("FPLAYER_T32_SMB_ENABLED")));

        String phase = "ENV";
        FPlayerIndexDatabase database = null;
        InMemoryCredentialStore credentials = new InMemoryCredentialStore();
        char[] password = null;
        try {
            String host = requiredEnvironment("FPLAYER_T32_SMB_HOST");
            int port = integerEnvironment("FPLAYER_T32_SMB_PORT", 1, 65_535);
            String share = requiredEnvironment("FPLAYER_T32_SMB_SHARE");
            String root = requiredEnvironment("FPLAYER_T32_SMB_ROOT");
            int expectedMedia = integerEnvironment("FPLAYER_T32_SMB_MEDIA_COUNT", 1, 1_000);
            String username = System.getenv("FPLAYER_T32_SMB_USERNAME");
            String passwordValue = System.getenv("FPLAYER_T32_SMB_PASSWORD");
            String credentialRef = null;
            if (username != null && !username.isBlank()) {
                if (passwordValue == null || passwordValue.isEmpty()) {
                    throw new IllegalArgumentException("SMB_RUNTIME_PASSWORD_REQUIRED");
                }
                password = passwordValue.toCharArray();
                CredentialStore.SmbCredential credential =
                        new CredentialStore.SmbCredential(username, password);
                try {
                    credentials.put(CREDENTIAL_REF, credential);
                } finally {
                    credential.clear();
                }
                credentialRef = CREDENTIAL_REF;
            }

            phase = "DATABASE";
            Context context = ApplicationProvider.getApplicationContext();
            database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class)
                    .allowMainThreadQueries()
                    .build();
            IndexDao dao = database.indexDao();
            SmbSourceConfig directConfig = config(host, port, share, root, credentialRef);
            new SmbSourceRepository(dao).create(directConfig, 1L);
            SmbScanner scanner = new SmbScanner(dao, new IncrementingClock());

            phase = "DIRECT_SCAN";
            try (SmbjDocumentTree tree = new SmbjDocumentTree(directConfig, credentials)) {
                SmbScanner.SmbScanResult committed = scanner.scan(
                        directConfig,
                        1L,
                        tree,
                        LocalSafScanner.Cancellation.NEVER
                );
                assertEquals(SmbScanner.SmbScanResult.State.ONLINE, committed.state);
                assertNotNull(committed.scan);
                assertTrue(committed.scan.committed);
                assertEquals(expectedMedia, committed.scan.mediaCount);
                assertEquals(expectedMedia, dao.currentMediaCount(SOURCE_ID));
            }

            phase = "CONNECTION_CUT";
            try (CuttableTcpProxy proxy = new CuttableTcpProxy(host, port)) {
                SmbSourceConfig proxyConfig = config(
                        "127.0.0.1",
                        proxy.localPort(),
                        share,
                        root,
                        credentialRef
                );
                try (SmbjDocumentTree realTree = new SmbjDocumentTree(proxyConfig, credentials)) {
                    SmbScanner.SmbScanResult failed = scanner.scan(
                            proxyConfig,
                            2L,
                            new CutAfterFirstListingTree(realTree, proxy),
                            LocalSafScanner.Cancellation.NEVER
                    );
                    assertEquals(SmbScanner.SmbScanResult.State.FAILED, failed.state);
                    assertEquals("SMB_IO_FAILED", failed.failureCode);
                }
            }

            phase = "SNAPSHOT";
            assertEquals(IndexDao.SCAN_INCOMPLETE, dao.scanStatus(SOURCE_ID, 2L));
            assertEquals(Long.valueOf(1L), dao.source(SOURCE_ID).currentScanGeneration);
            assertEquals(expectedMedia, dao.currentMediaCount(SOURCE_ID));
        } catch (Throwable failure) {
            throw new AssertionError("REAL_SMB_ACCEPTANCE_" + phase + "_FAILED_"
                    + failure.getClass().getSimpleName());
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
            credentials.delete(CREDENTIAL_REF);
            if (database != null) database.close();
        }
    }

    private static SmbSourceConfig config(
            String host,
            int port,
            String share,
            String root,
            String credentialRef
    ) {
        return new SmbSourceConfig(
                SOURCE_ID,
                "T32 SMB",
                host,
                port,
                share,
                root,
                credentialRef,
                4_000L,
                4_000L,
                0
        );
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SMB_RUNTIME_VALUE_REQUIRED");
        return value;
    }

    private static int integerEnvironment(String name, int minimum, int maximum) {
        int value;
        try {
            value = Integer.parseInt(requiredEnvironment(name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SMB_RUNTIME_INTEGER_INVALID");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("SMB_RUNTIME_INTEGER_OUT_OF_RANGE");
        }
        return value;
    }

    private static final class IncrementingClock implements java.util.function.LongSupplier {
        private long value = 10L;
        @Override public long getAsLong() { return value++; }
    }

    private static final class CutAfterFirstListingTree implements SmbDocumentTree {
        private final SmbDocumentTree delegate;
        private final CuttableTcpProxy proxy;
        private final AtomicBoolean firstListing = new AtomicBoolean(true);

        CutAfterFirstListingTree(SmbDocumentTree delegate, CuttableTcpProxy proxy) {
            this.delegate = delegate;
            this.proxy = proxy;
        }

        @Override public boolean isOnline() { return delegate.isOnline(); }
        @Override public Entry root() throws IOException { return delegate.root(); }

        @Override public List<Entry> children(Entry directory) throws IOException {
            List<Entry> children = delegate.children(directory);
            if (firstListing.compareAndSet(true, false)) {
                proxy.cutConnections();
                // A flat share may have no later directory read to observe the cut.
                // Surface the interrupted operation deterministically after closing the proxy.
                throw new IOException("SMB_CONNECTION_CUT");
            }
            return children;
        }
    }

    private static final class CuttableTcpProxy implements AutoCloseable {
        private final String targetHost;
        private final int targetPort;
        private final ServerSocket listener;
        private final ExecutorService executor;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        CuttableTcpProxy(String targetHost, int targetPort) throws IOException {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "t32-smb-proxy");
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(this::accept);
        }

        int localPort() { return listener.getLocalPort(); }

        private void accept() {
            try {
                Socket client = listener.accept();
                Socket upstream = new Socket();
                upstream.connect(new InetSocketAddress(targetHost, targetPort), 4_000);
                sockets.add(client);
                sockets.add(upstream);
                executor.execute(() -> pump(client, upstream));
                executor.execute(() -> pump(upstream, client));
            } catch (IOException ignored) {
                if (!closed.get()) close();
            }
        }

        private void pump(Socket source, Socket destination) {
            try {
                InputStream input = source.getInputStream();
                OutputStream output = destination.getOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Connection cuts are the expected terminal path for this proxy.
            }
        }

        void cutConnections() {
            close();
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { listener.close(); } catch (IOException ignored) { }
            for (Socket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            sockets.clear();
            executor.shutdownNow();
        }
    }
}
