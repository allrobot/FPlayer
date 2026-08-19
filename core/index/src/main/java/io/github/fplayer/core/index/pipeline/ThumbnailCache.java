package io.github.fplayer.core.index.pipeline;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Filesystem thumbnail cache with atomic publication and post-commit cleanup. */
public final class ThumbnailCache {
    private final Path root;

    public ThumbnailCache(@NonNull Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @NonNull
    public String cacheKey(String mediaId, Long sizeBytes, Long modifiedAtEpochMs,
                           int width, int height, String strategyVersion) {
        return sha256(mediaId + "|" + sizeBytes + "|" + modifiedAtEpochMs + "|" +
                width + "x" + height + "|" + strategyVersion);
    }

    @NonNull
    public Path publish(String cacheKey, @NonNull byte[] bytes) throws IOException {
        if (cacheKey.isBlank() || bytes.length == 0) throw new IOException("THUMBNAIL_EMPTY");
        Path target = resolve(cacheKey);
        Path temp = root.resolve("." + cacheKey + ".tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        if (Files.size(temp) == 0) {
            Files.deleteIfExists(temp);
            throw new IOException("THUMBNAIL_EMPTY");
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    public void cleanup(@NonNull Set<String> referencedKeys) throws IOException {
        Set<String> safe = new HashSet<>(referencedKeys);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.thumb")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String key = name.substring(0, name.length() - 6);
                if (!safe.contains(key)) Files.deleteIfExists(file);
            }
        }
    }

    @NonNull public Path path(String cacheKey) { return resolve(cacheKey); }

    private Path resolve(String cacheKey) {
        if (!cacheKey.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("THUMBNAIL_KEY_INVALID");
        return root.resolve(cacheKey + ".thumb");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
