package io.github.fplayer.core.index.smb;

/** Runtime-only policy shared by credentialed SMB acceptance tests. */
final class SmbAcceptanceRuntimePolicy {
    private SmbAcceptanceRuntimePolicy() { }

    static boolean credentialsProvided(String username, String password) {
        return username != null && !username.isBlank()
                && password != null && !password.isEmpty();
    }
}
