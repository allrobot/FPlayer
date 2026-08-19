package io.github.fplayer.core.index.smb;

import androidx.annotation.Nullable;

/** Secret storage boundary. Implementations must never persist credentials in Room or logs. */
public interface CredentialStore {
    void put(String reference, SmbCredential credential);

    @Nullable
    SmbCredential get(String reference);

    void delete(String reference);

    final class SmbCredential {
        public final String username;
        public final char[] password;

        public SmbCredential(String username, char[] password) {
            if (username == null || username.isBlank()) throw new IllegalArgumentException("SMB_USERNAME_REQUIRED");
            if (password == null || password.length == 0) throw new IllegalArgumentException("SMB_PASSWORD_REQUIRED");
            this.username = username;
            this.password = password.clone();
        }

        public void clear() {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
