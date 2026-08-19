package io.github.fplayer.core.index.smb;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SmbAcceptanceRuntimePolicyTest {
    @Test public void credentialsAreRequiredForEnabledAcceptance() {
        assertFalse(SmbAcceptanceRuntimePolicy.credentialsProvided(null, "secret"));
        assertFalse(SmbAcceptanceRuntimePolicy.credentialsProvided("user", null));
        assertFalse(SmbAcceptanceRuntimePolicy.credentialsProvided(" ", "secret"));
        assertFalse(SmbAcceptanceRuntimePolicy.credentialsProvided("user", ""));
        assertTrue(SmbAcceptanceRuntimePolicy.credentialsProvided("user", "secret"));
    }
}
