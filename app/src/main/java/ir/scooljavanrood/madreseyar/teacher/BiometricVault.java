package ir.scooljavanrood.madreseyar.teacher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores only the staff session token, never the username/password.
 * An Android Keystore AES-GCM key requires strong biometric authorization per use.
 * The AES key is non-exportable. Adding a new enrolled biometric invalidates it.
 */
final class BiometricVault {
    private static final String PREFS = "msy_teacher_biometric_a02";
    private static final String ALIAS = "msy_teacher_bio_a02";
    private static final String IV = "iv";
    private static final String SEALED = "sealed";
    private final SharedPreferences prefs;

    BiometricVault(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isEnabled() {
        return prefs.contains(IV) && prefs.contains(SEALED);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        try {
            ks.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Android Keystore unavailable", e);
        }
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            spec.setUserAuthenticationValidityDurationSeconds(-1);
        }
        keyGen.init(spec.build());
        return keyGen.generateKey();
    }

    Cipher encryptionCipher() throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        return cipher;
    }

    Cipher decryptionCipher() throws GeneralSecurityException {
        if (!isEnabled()) throw new GeneralSecurityException("No saved biometric session");
        byte[] iv = Base64.decode(prefs.getString(IV, ""), Base64.NO_WRAP);
        if (iv.length != 12) throw new GeneralSecurityException("Invalid IV");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return cipher;
    }

    void saveAfterBiometric(Cipher authenticatedCipher, String token) throws GeneralSecurityException {
        if (token == null || token.isEmpty()) throw new GeneralSecurityException("Missing token");
        byte[] sealed = authenticatedCipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] iv = authenticatedCipher.getIV();
        if (iv == null || iv.length != 12) throw new GeneralSecurityException("Invalid GCM IV");
        if (!prefs.edit()
                .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .commit()) {
            throw new GeneralSecurityException("Could not persist encrypted token");
        }
    }

    String readAfterBiometric(Cipher authenticatedCipher) throws GeneralSecurityException {
        if (!isEnabled()) throw new GeneralSecurityException("No saved session");
        byte[] sealed = Base64.decode(prefs.getString(SEALED, ""), Base64.NO_WRAP);
        byte[] plain = authenticatedCipher.doFinal(sealed);
        return new String(plain, StandardCharsets.UTF_8);
    }

    void clear() {
        prefs.edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { /* Cleared token is sufficient to disable restore. */ }
    }
}
