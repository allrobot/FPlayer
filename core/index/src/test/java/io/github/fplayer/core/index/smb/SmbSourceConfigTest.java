package io.github.fplayer.core.index.smb;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class SmbSourceConfigTest {
    @Test public void normalizesLocatorWithoutSecrets() {
        SmbSourceConfig config = SmbSourceConfig.defaults("source", "NAS", "NAS.Example", "media", "cred-1");
        assertEquals("nas.example", config.host);
        assertEquals("smb://nas.example:445/media", config.rootLocator());
        assertEquals("cred-1", config.credentialRef);
    }

    @Test public void normalizesRootAndAllowsAnonymous() {
        SmbSourceConfig config = new SmbSourceConfig("s", "S", "host", 1445, "share", "\\videos//", null,
                500, 500, 0);
        assertEquals("/videos", config.rootPath);
        assertEquals("smb://host:1445/share/videos", config.rootLocator());
        assertNull(config.credentialRef);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversal() {
        SmbSourceConfig.defaults("s", "S", "host", "share", null);
        new SmbSourceConfig("s", "S", "host", 445, "share", "/../private", null, 500, 500, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHostWithUserInfo() {
        SmbSourceConfig.defaults("s", "S", "user@host", "share", null);
    }
}
