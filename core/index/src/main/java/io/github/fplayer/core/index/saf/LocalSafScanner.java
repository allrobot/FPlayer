package io.github.fplayer.core.index.saf;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Deterministic, generation-based scanner for one persisted SAF tree. */
public final class LocalSafScanner {
    private static final int BATCH_SIZE = 128;
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg",
            "mpg", "mts", "ogv", "ts", "webm", "wmv"
    );

    private final IndexDao dao;
    private final LongSupplier clock;
    private final String sourceType;

    public LocalSafScanner(IndexDao dao, LongSupplier clock) {
        this(dao, clock, "LOCAL");
    }

    /**
     * The discovery algorithm is shared by local SAF and SMB document trees. The
     * default constructor remains LOCAL-only so callers cannot accidentally scan
     * a remote source with a local permission boundary.
     */
    public LocalSafScanner(IndexDao dao, LongSupplier clock, String sourceType) {
        this.dao = dao;
        this.clock = clock;
        if (!"LOCAL".equals(sourceType) && !"SMB".equals(sourceType)) {
            throw new IllegalArgumentException("SCAN_SOURCE_TYPE_UNSUPPORTED");
        }
        this.sourceType = sourceType;
    }

    @NonNull
    public ScanResult scan(
            @NonNull String sourceId,
            long generation,
            @NonNull SafDocumentTree tree,
            @NonNull Cancellation cancellation
    ) throws IOException {
        IndexEntities.SourceEntity source = dao.source(sourceId);
        if (source == null) {
            throw new IllegalStateException("SOURCE_NOT_FOUND");
        }
        if (!sourceType.equals(source.type)) {
            throw new IllegalArgumentException("SOURCE_TYPE_MISMATCH");
        }

        List<IndexEntities.MediaEntity> previousMedia = dao.currentMediaSnapshot(sourceId);
        List<IndexEntities.ScriptEntity> previousScripts = dao.currentScriptSnapshot(sourceId);
        boolean began = false;
        try {
            dao.beginScan(sourceId, generation, clock.getAsLong());
            began = true;
            Discovered discovered = discover(sourceId, generation, tree, cancellation);
            if (cancellation.isCancelled()) {
                return abandon(sourceId, generation, discovered, "CANCELLED");
            }

            writeBatches(discovered.folders, dao::upsertFolders, cancellation);
            writeBatches(discovered.media, dao::upsertMedia, cancellation);
            writeBatches(discovered.scripts, dao::upsertScripts, cancellation);
            if (cancellation.isCancelled()) {
                return abandon(sourceId, generation, discovered, "CANCELLED");
            }

            dao.commitScan(sourceId, generation, clock.getAsLong());
            Changes changes = changes(previousMedia, previousScripts, discovered.media, discovered.scripts);
            return new ScanResult(
                    generation,
                    true,
                    discovered.folders.size(),
                    discovered.media.size(),
                    discovered.scripts.size(),
                    changes.added,
                    changes.modified,
                    changes.removed,
                    null
            );
        } catch (IOException exception) {
            if (began && IndexDao.SCAN_WORKING.equals(dao.scanStatus(sourceId, generation))) {
                dao.abandonScan(sourceId, generation, clock.getAsLong(), failureCode(exception));
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (began && IndexDao.SCAN_WORKING.equals(dao.scanStatus(sourceId, generation))) {
                dao.abandonScan(sourceId, generation, clock.getAsLong(), "SCAN_FAILED");
            }
            throw exception;
        }
    }

    private Discovered discover(
            String sourceId,
            long generation,
            SafDocumentTree tree,
            Cancellation cancellation
    ) throws IOException {
        SafDocumentTree.Entry root = tree.root();
        if (!root.directory) {
            throw new IOException("SAF_ROOT_NOT_DIRECTORY");
        }

        LinkedHashMap<String, FolderDraft> folderDrafts = new LinkedHashMap<>();
        ArrayList<IndexEntities.MediaEntity> media = new ArrayList<>();
        ArrayList<ScriptDraft> scriptDrafts = new ArrayList<>();
        Map<String, ArrayList<IndexEntities.MediaEntity>> mediaByBase = new HashMap<>();
        ArrayDeque<PendingDirectory> pending = new ArrayDeque<>();
        String rootFolderId = logicalId("folder", sourceId, root.documentId);
        pending.add(new PendingDirectory(root, "", rootFolderId, null));

        while (!pending.isEmpty()) {
            if (cancellation.isCancelled()) {
                break;
            }
            PendingDirectory directory = pending.removeFirst();
            FolderDraft folder = new FolderDraft(
                    directory.folderId,
                    directory.parentId,
                    directory.normalizedPath,
                    directory.entry.displayName
            );
            folderDrafts.put(folder.id, folder);

            ArrayList<SafDocumentTree.Entry> children = new ArrayList<>(tree.children(directory.entry));
            children.sort(Comparator
                    .comparing((SafDocumentTree.Entry entry) -> normalizedSegment(entry.displayName))
                    .thenComparing(entry -> entry.documentId));
            for (SafDocumentTree.Entry entry : children) {
                if (cancellation.isCancelled()) {
                    break;
                }
                String segment = normalizedSegment(entry.displayName);
                String normalizedPath = joinPath(directory.normalizedPath, segment);
                if (entry.directory) {
                    pending.addLast(new PendingDirectory(
                            entry,
                            normalizedPath,
                            logicalId("folder", sourceId, entry.documentId),
                            directory.folderId
                    ));
                } else if (isScript(entry.displayName)) {
                    scriptDrafts.add(new ScriptDraft(entry, directory.folderId, normalizedPath));
                } else if (isMedia(entry)) {
                    String id = logicalId("media", sourceId, entry.documentId);
                    String basename = normalizedBasename(entry.displayName);
                    IndexEntities.MediaEntity entity = new IndexEntities.MediaEntity(
                            id,
                            generation,
                            sourceId,
                            directory.folderId,
                            entry.documentId,
                            entry.locator,
                            entry.displayName,
                            basename,
                            normalizedPath,
                            entry.sizeBytes,
                            entry.modifiedAtEpochMs,
                            null,
                            null,
                            null,
                            null,
                            entry.mimeType,
                            null,
                            null,
                            null,
                            "UNPROBED"
                    );
                    media.add(entity);
                    folder.directMediaCount++;
                    mediaByBase.computeIfAbsent(
                            directory.folderId + "\u0000" + basename,
                            ignored -> new ArrayList<>()
                    ).add(entity);
                }
            }
        }

        for (FolderDraft folder : folderDrafts.values()) {
            folder.descendantMediaCount = folder.directMediaCount;
        }
        for (FolderDraft folder : folderDrafts.values()) {
            if (folder.directMediaCount == 0) {
                continue;
            }
            String parentId = folder.parentId;
            while (parentId != null) {
                FolderDraft parent = folderDrafts.get(parentId);
                if (parent == null) {
                    throw new IllegalStateException("SAF_FOLDER_PARENT_MISSING");
                }
                parent.descendantMediaCount += folder.directMediaCount;
                parentId = parent.parentId;
            }
        }

        ArrayList<IndexEntities.FolderEntity> folders = new ArrayList<>(folderDrafts.size());
        for (FolderDraft folder : folderDrafts.values()) {
            folders.add(new IndexEntities.FolderEntity(
                    folder.id,
                    generation,
                    sourceId,
                    folder.parentId,
                    folder.normalizedPath,
                    folder.displayName,
                    folder.directMediaCount,
                    folder.descendantMediaCount
            ));
        }
        folders.sort(Comparator.comparing((IndexEntities.FolderEntity item) -> item.normalizedPath)
                .thenComparing(item -> item.id));
        media.sort(Comparator.comparing((IndexEntities.MediaEntity item) -> item.normalizedPath)
                .thenComparing(item -> item.id));

        ArrayList<IndexEntities.ScriptEntity> scripts = new ArrayList<>(scriptDrafts.size());
        scriptDrafts.sort(Comparator.comparing((ScriptDraft item) -> item.normalizedPath)
                .thenComparing(item -> item.entry.documentId));
        for (ScriptDraft draft : scriptDrafts) {
            ScriptName scriptName = scriptName(draft.entry.displayName);
            List<IndexEntities.MediaEntity> matches = mediaByBase.getOrDefault(
                    draft.folderId + "\u0000" + scriptName.basename,
                    new ArrayList<>()
            );
            String mediaId = matches.size() == 1 ? matches.get(0).id : null;
            String status = matches.size() > 1 ? "MATCH_CONFLICT" : "DISCOVERED";
            scripts.add(new IndexEntities.ScriptEntity(
                    logicalId("script", sourceId, draft.entry.documentId),
                    generation,
                    sourceId,
                    mediaId,
                    draft.entry.locator,
                    scriptName.basename,
                    scriptName.axis,
                    draft.entry.sizeBytes,
                    draft.entry.modifiedAtEpochMs,
                    status,
                    0
            ));
        }
        return new Discovered(folders, media, scripts);
    }

    private ScanResult abandon(
            String sourceId,
            long generation,
            Discovered discovered,
            String failureCode
    ) {
        dao.abandonScan(sourceId, generation, clock.getAsLong(), failureCode);
        return new ScanResult(
                generation,
                false,
                discovered.folders.size(),
                discovered.media.size(),
                discovered.scripts.size(),
                0,
                0,
                0,
                failureCode
        );
    }

    private static <T> void writeBatches(
            List<T> entries,
            BatchWriter<T> writer,
            Cancellation cancellation
    ) {
        for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
            if (cancellation.isCancelled()) {
                return;
            }
            writer.write(entries.subList(start, Math.min(entries.size(), start + BATCH_SIZE)));
        }
    }

    private static Changes changes(
            List<IndexEntities.MediaEntity> previousMedia,
            List<IndexEntities.ScriptEntity> previousScripts,
            List<IndexEntities.MediaEntity> currentMedia,
            List<IndexEntities.ScriptEntity> currentScripts
    ) {
        Map<String, String> previous = fingerprints(previousMedia, previousScripts);
        Map<String, String> current = fingerprints(currentMedia, currentScripts);
        int added = 0;
        int modified = 0;
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String old = previous.get(entry.getKey());
            if (old == null) {
                added++;
            } else if (!old.equals(entry.getValue())) {
                modified++;
            }
        }
        int removed = 0;
        for (String id : previous.keySet()) {
            if (!current.containsKey(id)) {
                removed++;
            }
        }
        return new Changes(added, modified, removed);
    }

    private static Map<String, String> fingerprints(
            List<IndexEntities.MediaEntity> media,
            List<IndexEntities.ScriptEntity> scripts
    ) {
        HashMap<String, String> result = new HashMap<>();
        for (IndexEntities.MediaEntity item : media) {
            result.put("m:" + item.id, item.locator + "|" + item.normalizedPath + "|"
                    + item.sizeBytes + "|" + item.modifiedAtEpochMs + "|" + item.mimeType);
        }
        for (IndexEntities.ScriptEntity item : scripts) {
            result.put("s:" + item.id, item.locator + "|" + item.normalizedBasename + "|"
                    + item.axis + "|" + item.sizeBytes + "|" + item.modifiedAtEpochMs);
        }
        return result;
    }

    private static boolean isMedia(SafDocumentTree.Entry entry) {
        return entry.mimeType.toLowerCase(Locale.ROOT).startsWith("video/")
                || MEDIA_EXTENSIONS.contains(extension(entry.displayName));
    }

    private static boolean isScript(String displayName) {
        return "funscript".equals(extension(displayName));
    }

    private static String extension(String displayName) {
        int dot = displayName.lastIndexOf('.');
        return dot < 0 ? "" : displayName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizedBasename(String displayName) {
        int dot = displayName.lastIndexOf('.');
        String basename = dot <= 0 ? displayName : displayName.substring(0, dot);
        return normalizedSegment(basename);
    }

    private static ScriptName scriptName(String displayName) {
        String basename = normalizedBasename(displayName);
        String axis = null;
        if (basename.length() > 3) {
            int separatorIndex = basename.length() - 3;
            char separator = basename.charAt(separatorIndex);
            String candidate = basename.substring(separatorIndex + 1).toUpperCase(Locale.ROOT);
            if ((separator == '.' || separator == '_' || separator == '-')
                    && candidate.matches("[A-Z][0-9]")) {
                axis = candidate;
                basename = basename.substring(0, separatorIndex);
            }
        }
        return new ScriptName(basename, axis);
    }

    private static String normalizedSegment(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .trim();
    }

    private static String joinPath(String parent, String child) {
        return parent.isEmpty() ? "/" + child : parent + "/" + child;
    }

    private static String logicalId(String type, String sourceId, String documentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (type + "\u0000" + sourceId + "\u0000" + documentId)
                            .getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder result = new StringBuilder(type).append('-');
            for (int index = 0; index < 16; index++) {
                result.append(String.format(Locale.ROOT, "%02x", bytes[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private String failureCode(IOException exception) {
        if ("SMB".equals(sourceType)) {
            return exception instanceof AndroidSafDocumentTree.SafAccessException
                    ? "SMB_AUTH_FAILED"
                    : "SMB_IO_FAILED";
        }
        return exception instanceof AndroidSafDocumentTree.SafAccessException
                ? "SAF_PERMISSION_LOST"
                : "SAF_IO_FAILED";
    }

    public interface Cancellation {
        Cancellation NEVER = () -> false;

        boolean isCancelled();
    }

    public static final class ScanResult {
        public final long generation;
        public final boolean committed;
        public final int folderCount;
        public final int mediaCount;
        public final int scriptCount;
        public final int addedCount;
        public final int modifiedCount;
        public final int removedCount;
        public final String failureCode;

        ScanResult(
                long generation,
                boolean committed,
                int folderCount,
                int mediaCount,
                int scriptCount,
                int addedCount,
                int modifiedCount,
                int removedCount,
                String failureCode
        ) {
            this.generation = generation;
            this.committed = committed;
            this.folderCount = folderCount;
            this.mediaCount = mediaCount;
            this.scriptCount = scriptCount;
            this.addedCount = addedCount;
            this.modifiedCount = modifiedCount;
            this.removedCount = removedCount;
            this.failureCode = failureCode;
        }
    }

    private interface BatchWriter<T> {
        void write(List<T> entries);
    }

    private static final class PendingDirectory {
        final SafDocumentTree.Entry entry;
        final String normalizedPath;
        final String folderId;
        final String parentId;

        PendingDirectory(
                SafDocumentTree.Entry entry,
                String normalizedPath,
                String folderId,
                String parentId
        ) {
            this.entry = entry;
            this.normalizedPath = normalizedPath;
            this.folderId = folderId;
            this.parentId = parentId;
        }
    }

    private static final class FolderDraft {
        final String id;
        final String parentId;
        final String normalizedPath;
        final String displayName;
        int directMediaCount;
        int descendantMediaCount;

        FolderDraft(String id, String parentId, String normalizedPath, String displayName) {
            this.id = id;
            this.parentId = parentId;
            this.normalizedPath = normalizedPath;
            this.displayName = displayName;
        }
    }

    private static final class ScriptDraft {
        final SafDocumentTree.Entry entry;
        final String folderId;
        final String normalizedPath;

        ScriptDraft(SafDocumentTree.Entry entry, String folderId, String normalizedPath) {
            this.entry = entry;
            this.folderId = folderId;
            this.normalizedPath = normalizedPath;
        }
    }

    private static final class ScriptName {
        final String basename;
        final String axis;

        ScriptName(String basename, String axis) {
            this.basename = basename;
            this.axis = axis;
        }
    }

    private static final class Discovered {
        final List<IndexEntities.FolderEntity> folders;
        final List<IndexEntities.MediaEntity> media;
        final List<IndexEntities.ScriptEntity> scripts;

        Discovered(
                List<IndexEntities.FolderEntity> folders,
                List<IndexEntities.MediaEntity> media,
                List<IndexEntities.ScriptEntity> scripts
        ) {
            this.folders = folders;
            this.media = media;
            this.scripts = scripts;
        }
    }

    private static final class Changes {
        final int added;
        final int modified;
        final int removed;

        Changes(int added, int modified, int removed) {
            this.added = added;
            this.modified = modified;
            this.removed = removed;
        }
    }
}
