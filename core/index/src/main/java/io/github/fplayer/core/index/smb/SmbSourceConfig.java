package io.github.fplayer.core.index.smb;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Non-secret SMB source settings. Passwords are referenced by credentialRef only. */
public final class SmbSourceConfig {
    public static final int DEFAULT_PORT = 445;
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 8_000L;
    public static final long DEFAULT_READ_TIMEOUT_MS = 12_000L;
    public static final int DEFAULT_RETRY_LIMIT = 2;

    public final String sourceId;
    public final String displayName;
    public final String host;
    public final int port;
    public final String share;
    public final String rootPath;
    @Nullable public final String credentialRef;
    public final long connectTimeoutMs;
    public final long readTimeoutMs;
    public final int retryLimit;

    public SmbSourceConfig(
            String sourceId,
            String displayName,
            String host,
            int port,
            String share,
            String rootPath,
            @Nullable String credentialRef,
            long connectTimeoutMs,
            long readTimeoutMs,
            int retryLimit
    ) {
        this.sourceId = required(sourceId, "SMB_SOURCE_ID_REQUIRED");
        this.displayName = required(displayName, "SMB_DISPLAY_NAME_REQUIRED");
        this.host = normalizeHost(host);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("SMB_PORT_INVALID");
        this.port = port;
        this.share = normalizeShare(share);
        this.rootPath = normalizeRoot(rootPath);
        this.credentialRef = credentialRef == null || credentialRef.isBlank()
                ? null : credentialRef.trim();
        if (connectTimeoutMs < 500L || connectTimeoutMs > 120_000L) {
            throw new IllegalArgumentException("SMB_CONNECT_TIMEOUT_INVALID");
        }
        if (readTimeoutMs < 500L || readTimeoutMs > 300_000L) {
            throw new IllegalArgumentException("SMB_READ_TIMEOUT_INVALID");
        }
        if (retryLimit < 0 || retryLimit > 5) throw new IllegalArgumentException("SMB_RETRY_LIMIT_INVALID");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.retryLimit = retryLimit;
    }

    public static SmbSourceConfig defaults(
            String sourceId, String displayName, String host, String share, @Nullable String credentialRef
    ) {
        return new SmbSourceConfig(sourceId, displayName, host, DEFAULT_PORT, share, "/", credentialRef,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_RETRY_LIMIT);
    }

    /** Stable locator used in SourceEntity; it contains no username or password. */
    public String rootLocator() {
        String path = rootPath.equals("/") ? "" : rootPath;
        return String.format(Locale.ROOT, "smb://%s:%d/%s%s", host, port, share, path);
    }

    private static String required(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
        return value.trim();
    }

    private static String normalizeHost(String value) {
        String host = required(value, "SMB_HOST_REQUIRED").toLowerCase(Locale.ROOT);
        if (host.contains("/") || host.contains("\\") || host.contains("@") || host.contains(":")) {
            throw new IllegalArgumentException("SMB_HOST_INVALID");
        }
        return host;
    }

    private static String normalizeShare(String value) {
        String share = required(value, "SMB_SHARE_REQUIRED");
        if (share.contains("/") || share.contains("\\") || share.equals(".") || share.equals("..")) {
            throw new IllegalArgumentException("SMB_SHARE_INVALID");
        }
        return share;
    }

    private static String normalizeRoot(String value) {
        String root = value == null || value.isBlank() ? "/" : value.trim().replace('\\', '/');
        if (!root.startsWith("/")) root = "/" + root;
        while (root.contains("//")) root = root.replace("//", "/");
        for (String segment : root.split("/")) {
            if (segment.equals("..")) throw new IllegalArgumentException("SMB_ROOT_TRAVERSAL");
        }
        return root.length() > 1 && root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }
}
