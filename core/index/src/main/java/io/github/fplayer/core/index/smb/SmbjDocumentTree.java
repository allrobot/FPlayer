package io.github.fplayer.core.index.smb;

import androidx.annotation.NonNull;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import io.github.fplayer.core.index.saf.SafDocumentTree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** SMBJ-backed metadata-only tree. The caller owns this object for one scan. */
public final class SmbjDocumentTree implements SmbDocumentTree {
    private final SmbSourceConfig config;
    private final SMBClient client;
    private final Connection connection;
    private final Session session;
    private final DiskShare share;
    private volatile boolean closed;

    public SmbjDocumentTree(@NonNull SmbSourceConfig config, @NonNull CredentialStore credentials) throws IOException {
        this.config = config;
        SmbConfig smbConfig = SmbConfig.builder()
                .withTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
        client = new SMBClient(smbConfig);
        Connection openedConnection = null;
        Session openedSession = null;
        DiskShare openedShare = null;
        try {
            openedConnection = client.connect(config.host, config.port);
            CredentialStore.SmbCredential credential = config.credentialRef == null
                    ? null : credentials.get(config.credentialRef);
            try {
                AuthenticationContext auth = credential == null
                        ? AuthenticationContext.anonymous()
                        : new AuthenticationContext(credential.username, credential.password, "");
                openedSession = openedConnection.authenticate(auth);
            } finally {
                if (credential != null) credential.clear();
            }
            openedShare = (DiskShare) openedSession.connectShare(config.share);
        } catch (Exception exception) {
            closeQuietly(openedShare);
            closeQuietly(openedSession);
            closeQuietly(openedConnection);
            client.close();
            throw new IOException("SMB_CONNECT_FAILED", exception);
        }
        connection = openedConnection;
        session = openedSession;
        share = openedShare;
    }

    @Override public boolean isOnline() {
        return !closed && connection.isConnected() && share.isConnected();
    }

    @NonNull @Override public Entry root() throws IOException {
        ensureOpen();
        String path = smbPath(config.rootPath);
        return new Entry("root:" + config.rootPath, locator(path), lastSegment(config.rootPath),
                "vnd.android.document/directory", true, null, null);
    }

    @NonNull @Override public List<Entry> children(@NonNull Entry directory) throws IOException {
        ensureOpen();
        String path = directory.documentId.startsWith("root:")
                ? directory.documentId.substring("root:".length())
                : pathFromId(directory.documentId);
        path = smbPath(path);
        try {
            List<FileIdBothDirectoryInformation> listed = share.list(path);
            ArrayList<Entry> result = new ArrayList<>(listed.size());
            for (FileIdBothDirectoryInformation item : listed) {
                String name = item.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                boolean isDirectory = (item.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                String childPath = path.isEmpty() ? name : path + "\\" + name;
                String id = isDirectory
                        ? "path:" + Long.toUnsignedString(item.getFileId(), 16) + ":" + childPath
                        : "file:" + Long.toUnsignedString(item.getFileId(), 16) + ":" + childPath;
                Long modified = item.getLastWriteTime() == null ? null : item.getLastWriteTime().toEpochMillis();
                result.add(new Entry(id, locator(childPath), name, mimeType(name, isDirectory), isDirectory,
                        isDirectory ? null : item.getEndOfFile(), modified));
            }
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("SMB_LIST_FAILED", exception);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        closeQuietly(share);
        closeQuietly(session);
        closeQuietly(connection);
        client.close();
    }

    private void ensureOpen() throws IOException {
        if (!isOnline()) throw new IOException("SMB_OFFLINE");
    }

    private String locator(String path) {
        String normalized = path.replace('\\', '/');
        return "smb://" + config.host + ":" + config.port + "/" + config.share
                + (normalized.isEmpty() ? "" : "/" + normalized);
    }

    private static String smbPath(String path) {
        if (path == null || path.equals("/")) return "";
        return path.replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
    }

    private static String lastSegment(String path) {
        if (path == null || path.equals("/")) return "/";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    private static String pathFromId(String id) {
        if (id.startsWith("path:")) {
            int separator = id.indexOf(':', "path:".length());
            return separator < 0 ? "" : id.substring(separator + 1);
        }
        if (id.startsWith("file:")) {
            int separator = id.indexOf(':', "file:".length());
            return separator < 0 ? "" : id.substring(separator + 1);
        }
        return "";
    }

    private static String mimeType(String name, boolean directory) {
        if (directory) return "vnd.android.document/directory";
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts" -> "video/" + extension;
            default -> "application/octet-stream";
        };
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) { }
    }
}
