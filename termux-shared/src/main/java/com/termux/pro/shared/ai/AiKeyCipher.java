package com.termux.pro.shared.ai;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts/decrypts small strings (like API keys) with AES/GCM using a key that is
 * stored in the Android Keystore, so the key never needs to be stored in plain text.
 *
 * On very old devices (Android 6.0 / API 23 is required for {@link KeyGenParameterSpec})
 * a fallback obfuscation is used. The important thing is that the app itself always
 * decrypts without errors when a valid key was stored.
 */
public final class AiKeyCipher {

    private static final String LOG_TAG = AiConstants.LOG_TAG;

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "termux_ai_master_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Prefix used on very old Android versions where Keystore AES is unavailable. */
    private static final String PREFIX_FALLBACK = "plain:";
    private static final String PREFIX_ENCRYPTED = "enc:";

    private AiKeyCipher() {}

    /**
     * Encrypt {@code plainText} and return a portable, base64 encoded string that can be
     * stored in SharedPreferences. Returns {@code null} if encryption failed.
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SecretKey key = getOrCreateKey();
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, key);
                byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
                byte[] iv = cipher.getIV();

                String ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP);
                String dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
                return PREFIX_ENCRYPTED + ivB64 + ":" + dataB64;
            } else {
                // Fallback (not real security, but this only applies to Android 5.x)
                return PREFIX_FALLBACK + Base64.encodeToString(plainText.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to encrypt API key", e);
            return null;
        }
    }

    /**
     * Decrypt a string previously produced by {@link #encrypt(String)}.
     * Returns {@code null} if the data is corrupt or the Keystore key is missing
     * (e.g. app data restored to a different device).
     */
    public static String decrypt(String storedText) {
        if (storedText == null || storedText.isEmpty()) return null;

        try {
            if (storedText.startsWith(PREFIX_FALLBACK)) {
                byte[] data = Base64.decode(storedText.substring(PREFIX_FALLBACK.length()), Base64.NO_WRAP);
                return new String(data, StandardCharsets.UTF_8);
            }

            if (storedText.startsWith(PREFIX_ENCRYPTED) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String payload = storedText.substring(PREFIX_ENCRYPTED.length());
                int separator = payload.indexOf(':');
                if (separator <= 0) return null;

                byte[] iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP);
                byte[] encrypted = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP);

                SecretKey key = getOrCreateKey();
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] decrypted = cipher.doFinal(encrypted);
                return new String(decrypted, StandardCharsets.UTF_8);
            }

            return null;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to decrypt API key (key may have been lost)", e);
            return null;
        }
    }

    /** Create or load the AES key that lives inside the Android Keystore. */
    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build();
            keyGenerator.init(spec);
            keyGenerator.generateKey();
        }

        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
}
