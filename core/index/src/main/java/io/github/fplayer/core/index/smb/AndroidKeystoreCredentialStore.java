package io.github.fplayer.core.index.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM encrypted credential blobs with keys held by the Android Keystore. */
public final class AndroidKeystoreCredentialStore implements CredentialStore {
    private static final String STORE = "smb_credentials";
    private static final String KEY_PREFIX = "fplayer.smb.";
    private final SharedPreferences preferences;

    public AndroidKeystoreCredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    @Override public synchronized void put(String reference, SmbCredential credential) {
        validateReference(reference);
        try {
            Cipher cipher = cipher(reference, Cipher.ENCRYPT_MODE);
            byte[] plain = encode(credential);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] packed = ByteBuffer.allocate(4 + cipher.getIV().length + encrypted.length)
                    .putInt(cipher.getIV().length).put(cipher.getIV()).put(encrypted).array();
            preferences.edit().putString(reference, Base64.getEncoder().encodeToString(packed)).apply();
            java.util.Arrays.fill(plain, (byte) 0);
        } catch (Exception exception) {
            throw new IllegalStateException("SMB_CREDENTIAL_STORE_FAILED", exception);
        }
    }

    @Override @Nullable public synchronized SmbCredential get(String reference) {
        validateReference(reference);
        String encoded = preferences.getString(reference, null);
        if (encoded == null) return null;
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            int ivLength = buffer.getInt();
            if (ivLength < 12 || ivLength > 16 || packed.length <= 4 + ivLength) throw new IllegalStateException();
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = cipher(reference, Cipher.DECRYPT_MODE, iv);
            return decode(cipher.doFinal(encrypted));
        } catch (Exception exception) {
            throw new IllegalStateException("SMB_CREDENTIAL_READ_FAILED", exception);
        }
    }

    @Override public synchronized void delete(String reference) {
        validateReference(reference);
        preferences.edit().remove(reference).apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(KEY_PREFIX + reference);
        } catch (Exception exception) {
            throw new IllegalStateException("SMB_CREDENTIAL_DELETE_FAILED", exception);
        }
    }

    private Cipher cipher(String reference, int mode) throws Exception {
        return cipher(reference, mode, null);
    }

    private Cipher cipher(String reference, int mode, @Nullable byte[] iv) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String alias = KEY_PREFIX + reference;
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            generator.generateKey();
        }
        SecretKey key = ((KeyStore.SecretKeyEntry) store.getEntry(alias, null)).getSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (mode == Cipher.ENCRYPT_MODE) cipher.init(mode, key);
        else cipher.init(mode, key, new GCMParameterSpec(128, iv));
        return cipher;
    }

    private static byte[] encode(SmbCredential credential) {
        byte[] user = credential.username.getBytes(StandardCharsets.UTF_8);
        ByteBuffer chars = StandardCharsets.UTF_8.encode(CharBuffer.wrap(credential.password));
        byte[] pass = new byte[chars.remaining()];
        chars.get(pass);
        return ByteBuffer.allocate(4 + user.length + 4 + pass.length).putInt(user.length).put(user)
                .putInt(pass.length).put(pass).array();
    }

    private static SmbCredential decode(byte[] plain) {
        ByteBuffer buffer = ByteBuffer.wrap(plain);
        int userLength = buffer.getInt();
        if (userLength < 1 || userLength > buffer.remaining()) throw new IllegalStateException();
        byte[] user = new byte[userLength]; buffer.get(user);
        int passLength = buffer.getInt();
        if (passLength < 1 || passLength > buffer.remaining()) throw new IllegalStateException();
        byte[] pass = new byte[passLength]; buffer.get(pass);
        return new SmbCredential(new String(user, StandardCharsets.UTF_8),
                new String(pass, StandardCharsets.UTF_8).toCharArray());
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("SMB_CREDENTIAL_REF_INVALID");
        }
    }
}
