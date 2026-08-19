package io.github.fplayer.core.index.saf;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

/** Read-only view of a user-selected SAF document tree. */
public interface SafDocumentTree {
    @NonNull
    Entry root() throws IOException;

    @NonNull
    List<Entry> children(@NonNull Entry directory) throws IOException;

    final class Entry {
        @NonNull public final String documentId;
        @NonNull public final String locator;
        @NonNull public final String displayName;
        @NonNull public final String mimeType;
        public final boolean directory;
        @Nullable public final Long sizeBytes;
        @Nullable public final Long modifiedAtEpochMs;

        public Entry(
                String documentId,
                String locator,
                String displayName,
                String mimeType,
                boolean directory,
                @Nullable Long sizeBytes,
                @Nullable Long modifiedAtEpochMs
        ) {
            if (documentId.isBlank() || locator.isBlank() || displayName.isBlank()) {
                throw new IllegalArgumentException("SAF_ENTRY_IDENTITY_REQUIRED");
            }
            this.documentId = documentId;
            this.locator = locator;
            this.displayName = displayName;
            this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
            this.directory = directory;
            this.sizeBytes = sizeBytes;
            this.modifiedAtEpochMs = modifiedAtEpochMs;
        }
    }
}
