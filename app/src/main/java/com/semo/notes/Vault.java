package com.semo.notes;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts small secrets (the Firebase refresh token) with a key that lives in the Android Keystore
 * and never leaves it. The ciphertext is useless if the preferences file is copied off the device.
 */
final class Vault {
    private Vault() {
    }

    private static final String ALIAS = "notes_session_v1";
    private static final String PREFIX = "v1:";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    /** Returns "" when encryption is impossible (the secret is then simply not persisted). */
    static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** Returns "" when the value is missing, tampered with, or its key no longer exists. */
    static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return "";
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length < 13) return "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ note encryption at rest

    private static final String NOTE_PREFIX = "e1:";
    private static volatile SecretKeySpec dataKey;
    /** True when the stored data key could not be restored (e.g. the Keystore was reset): old local notes are unreadable. */
    static volatile boolean dataKeyReset;

    /**
     * A random 256-bit key that encrypts the notes in the local database. It is stored wrapped by the
     * Keystore key, so the database file alone (adb backup, a copied app folder) cannot be read.
     * Software AES-GCM is used for the notes themselves because Keystore calls are too slow per row.
     */
    static synchronized SecretKeySpec dataKey(Context c) {
        if (dataKey != null) return dataKey;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("notes_keys", Context.MODE_PRIVATE);
        String wrapped = p.getString("dk", "");
        if (!wrapped.isEmpty()) {
            String raw = decrypt(wrapped);
            if (!raw.isEmpty()) {
                dataKey = new SecretKeySpec(Base64.decode(raw, Base64.NO_WRAP), "AES");
                return dataKey;
            }
            dataKeyReset = true; // the wrapping key is gone: start over, the cloud copy is re-imported
        }
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        String w = encrypt(Base64.encodeToString(k, Base64.NO_WRAP));
        if (w.isEmpty()) return null; // no Keystore on this device: notes stay unencrypted rather than lost
        p.edit().putString("dk", w).apply();
        dataKey = new SecretKeySpec(k, "AES");
        return dataKey;
    }

    /** Encrypts a note field; empty text and devices without a Keystore are stored as they are. */
    static String seal(Context c, String plain) {
        if (plain == null || plain.isEmpty()) return plain == null ? "" : plain;
        SecretKeySpec key = dataKey(c);
        if (key == null) return plain;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[12 + ct.length];
            System.arraycopy(iv, 0, all, 0, 12);
            System.arraycopy(ct, 0, all, 12, ct.length);
            return NOTE_PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            return plain;
        }
    }

    /** Reads a stored field: encrypted values are decrypted, older plain-text rows are returned unchanged. */
    static String open(Context c, String stored) {
        if (stored == null || !stored.startsWith(NOTE_PREFIX)) return stored == null ? "" : stored;
        SecretKeySpec key = dataKey(c);
        if (key == null) return "";
        try {
            byte[] all = Base64.decode(stored.substring(NOTE_PREFIX.length()), Base64.NO_WRAP);
            if (all.length < 13) return "";
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(ci.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(NOTE_PREFIX);
    }
}
