package io.github.fplayer.core.index.smb;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Test/preview implementation; production wiring must use AndroidKeystoreCredentialStore. */
public final class InMemoryCredentialStore implements CredentialStore {
    private final Map<String, SmbCredential> values = new HashMap<>();

    @Override public synchronized void put(String reference, SmbCredential credential) {
        validateReference(reference);
        SmbCredential old = values.put(reference, new SmbCredential(credential.username, credential.password));
        if (old != null) old.clear();
    }

    @Override @Nullable public synchronized SmbCredential get(String reference) {
        validateReference(reference);
        SmbCredential value = values.get(reference);
        return value == null ? null : new SmbCredential(value.username, value.password);
    }

    @Override public synchronized void delete(String reference) {
        validateReference(reference);
        SmbCredential old = values.remove(reference);
        if (old != null) old.clear();
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("SMB_CREDENTIAL_REF_INVALID");
        }
    }
}
