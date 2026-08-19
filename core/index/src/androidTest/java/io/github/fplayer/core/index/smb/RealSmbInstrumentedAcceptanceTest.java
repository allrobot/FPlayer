package io.github.fplayer.core.index.smb;

import android.content.Context;
import android.os.Bundle;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fplayer.core.index.db.FPlayerIndexDatabase;
import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.saf.LocalSafScanner;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

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

/** Runtime-gated SMB acceptance on TEST_TABLET's Android network stack. */
@RunWith(AndroidJUnit4.class)
public final class RealSmbInstrumentedAcceptanceTest {
    private static final String SOURCE_ID = "t32-device-smb";
    private static final String CREDENTIAL_REF = "t32-device-runtime";

    @Test public void realScanAndConnectionCutPreserveCommittedSnapshot() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("REAL_SMB_DEVICE_ACCEPTANCE_DISABLED",
                "true".equalsIgnoreCase(arguments.getString("enabled")));

        String phase = "ARGS";
        FPlayerIndexDatabase database = null;
        InMemoryCredentialStore credentials = new InMemoryCredentialStore();
        char[] password = null;
        try {
            String host = requiredArgument(arguments, "smb_host");
            int port = integerArgument(arguments, "smb_port", 1, 65_535);
            String share = requiredArgument(arguments, "smb_share");
            String root = requiredArgument(arguments, "smb_root");
            int expectedMedia = integerArgument(arguments, "expected_media", 1, 1_000);
            String username = arguments.getString("smb_username");
            String passwordValue = arguments.getString("smb_password");
            Assume.assumeTrue("REAL_SMB_DEVICE_ACCEPTANCE_BLOCKED_CREDENTIALS",
                    username != null && !username.isBlank()
                            && passwordValue != null && !passwordValue.isEmpty());
            int expectedScripts = integerArgument(arguments, "expected_scripts", 0, 2_000);
            String credentialRef = null;
            password = passwordValue.toCharArray();
            CredentialStore.SmbCredential credential =
                    new CredentialStore.SmbCredential(username, password);
            try {
                credentials.put(CREDENTIAL_REF, credential);
            } finally {
                credential.clear();
            }
            credentialRef = CREDENTIAL_REF;

            phase = "DATABASE";
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            database = Room.inMemoryDatabaseBuilder(context, FPlayerIndexDatabase.class)
                    .allowMainThreadQueries()
                    .build();
            IndexDao dao = database.indexDao();
            SmbSourceConfig directConfig = config(host, port, share, root, credentialRef);
            new SmbSourceRepository(dao).create(directConfig, 1L);
            assertEquals(directConfig.rootLocator(), dao.source(SOURCE_ID).rootLocator);
            assertEquals(credentialRef, dao.source(SOURCE_ID).authRef);
            SmbScanner scanner = new SmbScanner(dao, new IncrementingClock());

            phase = "DIRECT_CONNECT";
            try (SmbjDocumentTree tree = new SmbjDocumentTree(directConfig, credentials)) {
                phase = "DIRECT_SCAN";
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
                assertEquals(expectedScripts, committed.scan.scriptCount);
                assertEquals(expectedMedia, dao.currentMediaCount(SOURCE_ID));
                assertMatchedScripts(dao, expectedScripts);
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
                            new CutBeforeFirstListingTree(realTree, proxy),
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

            phase = "RECOVERY";
            try (SmbjDocumentTree recoveredTree = new SmbjDocumentTree(directConfig, credentials)) {
                SmbScanner.SmbScanResult recovered = scanner.scan(
                        directConfig, 3L, recoveredTree, LocalSafScanner.Cancellation.NEVER);
                assertEquals(SmbScanner.SmbScanResult.State.ONLINE, recovered.state);
                assertNotNull(recovered.scan);
                assertTrue(recovered.scan.committed);
                assertEquals(expectedMedia, recovered.scan.mediaCount);
                assertEquals(expectedScripts, recovered.scan.scriptCount);
                assertEquals(Long.valueOf(3L), dao.source(SOURCE_ID).currentScanGeneration);
                assertEquals(expectedMedia, dao.currentMediaCount(SOURCE_ID));
                assertMatchedScripts(dao, expectedScripts);
            }
        } catch (Throwable failure) {
            throw new AssertionError("REAL_SMB_DEVICE_ACCEPTANCE_" + phase + "_FAILED_"
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
                "T32 Device SMB",
                host,
                port,
                share,
                root,
                credentialRef,
                6_000L,
                6_000L,
                0
        );
    }

    private static String requiredArgument(Bundle arguments, String name) {
        String value = arguments.getString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SMB_RUNTIME_ARGUMENT_REQUIRED");
        }
        return value;
    }

    private static int integerArgument(
            Bundle arguments,
            String name,
            int minimum,
            int maximum
    ) {
        int value;
        try {
            value = Integer.parseInt(requiredArgument(arguments, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SMB_RUNTIME_INTEGER_INVALID");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("SMB_RUNTIME_INTEGER_OUT_OF_RANGE");
        }
        return value;
    }

    private static void assertMatchedScripts(IndexDao dao, int expectedScripts) {
        long matched = dao.currentScriptSnapshot(SOURCE_ID).stream()
                .filter(script -> script.mediaId != null)
                .count();
        assertEquals(expectedScripts, matched);
    }

    private static final class IncrementingClock implements java.util.function.LongSupplier {
        private long value = 10L;
        @Override public long getAsLong() { return value++; }
    }

    private static final class CutBeforeFirstListingTree implements SmbDocumentTree {
        private final SmbDocumentTree delegate;
        private final CuttableTcpProxy proxy;
        private final AtomicBoolean firstListing = new AtomicBoolean(true);

        CutBeforeFirstListingTree(SmbDocumentTree delegate, CuttableTcpProxy proxy) {
            this.delegate = delegate;
            this.proxy = proxy;
        }

        @Override public boolean isOnline() { return delegate.isOnline(); }
        @Override public Entry root() throws IOException { return delegate.root(); }

        @Override public List<Entry> children(Entry directory) throws IOException {
            if (firstListing.compareAndSet(true, false)) {
                proxy.cutConnections();
            }
            return delegate.children(directory);
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
                Thread thread = new Thread(runnable, "t32-device-smb-proxy");
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
                upstream.connect(new InetSocketAddress(targetHost, targetPort), 6_000);
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

        void cutConnections() { close(); }

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
