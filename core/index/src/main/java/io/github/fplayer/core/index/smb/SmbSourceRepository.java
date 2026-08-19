package io.github.fplayer.core.index.smb;

import androidx.annotation.NonNull;

import io.github.fplayer.core.index.db.IndexDao;
import io.github.fplayer.core.index.db.IndexEntities;

/** Persists only non-secret source metadata; the credential reference is an opaque Keystore key. */
public final class SmbSourceRepository {
    private final IndexDao dao;

    public SmbSourceRepository(@NonNull IndexDao dao) {
        this.dao = dao;
    }

    public void create(@NonNull SmbSourceConfig config, long nowEpochMs) {
        dao.insertSource(new IndexEntities.SourceEntity(
                config.sourceId,
                "SMB",
                config.displayName,
                config.rootLocator(),
                config.credentialRef,
                null,
                nowEpochMs,
                nowEpochMs
        ));
    }

    public int update(@NonNull SmbSourceConfig config, long nowEpochMs) {
        return dao.updateSourceMetadata(
                config.sourceId,
                "SMB",
                config.displayName,
                config.rootLocator(),
                config.credentialRef,
                nowEpochMs
        );
    }
}
