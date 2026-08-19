package io.github.fplayer.core.index.smb;

import io.github.fplayer.core.index.saf.SafDocumentTree;

/** SMB adapter boundary. Implementations must expose metadata only during discovery. */
public interface SmbDocumentTree extends SafDocumentTree, AutoCloseable {
    boolean isOnline();

    @Override
    default void close() {}
}
