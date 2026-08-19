package io.github.fplayer.core.index.smb;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

public final class CredentialStoreTest {
    @Test public void valuesAreCopiedAndCanBeDeleted() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        char[] secretChars = "fixture-value".toCharArray();
        store.put("nas", new CredentialStore.SmbCredential("alice", secretChars));
        secretChars[0] = 'x';
        CredentialStore.SmbCredential loaded = store.get("nas");
        assertEquals("alice", loaded.username);
        assertArrayEquals("fixture-value".toCharArray(), loaded.password);
        loaded.clear();
        store.delete("nas");
        assertNull(store.get("nas"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeReference() {
        new InMemoryCredentialStore().put("../secret", new CredentialStore.SmbCredential("a", new char[]{'b'}));
    }
}
