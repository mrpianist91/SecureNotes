package com.example.securenotes.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey; //Android Keystore

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;

public class SecurityUtils {

    private static final String PREFERENCES_FILE_NAME = "secure_notes_prefs";
    private static final String DB_PASSPHRASE_KEY     = "db_passphrase";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "SecureNotes_AES_GCM";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LEN = 128; // bit
    private static final int KEY_SIZE = 256; // bit
    private static final int SALT_LENGTH       = 16;     // 16 byte = 128 bit
    private static final int HASH_LENGTH       = 64;     // 64 byte = 512 bit
    private static final int PBKDF2_ROUNDS = 120_000; // paramet. NIST 2025

    // Sezione PIN (PBKDF2-HMAC-SHA-512)
// -------------------------------------------------------------------------

    /* Genera un salt crittograficamente sicuro. */
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** Deriva un hash PBKDF2-HMAC-SHA-512 dal PIN. */
    public static byte[] hashPin(char[] pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin, salt, PBKDF2_ROUNDS, HASH_LENGTH * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 failed", e);
        } finally {
            /* Wipe del dato sensibile */
            Arrays.fill(pin, '\0');
        }
    }
    /**
     * Verifica il PIN inserito.

    public static boolean verifyPin(@NonNull char[] pin, @NonNull byte[] salt,
                                    @NonNull byte[] expectedHash)
            throws GeneralSecurityException {
        byte[] computed = hashPin(pin, salt);
        boolean equals = Arrays.equals(computed, expectedHash);
        Arrays.fill(computed, (byte) 0);
        return equals;
    }
                                    */

    /** Verifica la correttezza del PIN confrontando gli hash in constant-time. */
    public static boolean verifyPin(char[] pin, byte[] storedHash, byte[] salt) {
        byte[] newHash = null;
        try {
            newHash = hashPin(pin, salt);
            boolean equals = Arrays.equals(newHash, storedHash);
            return equals;
            //MessageDigest.isEqual(newHash, storedHash);   // constant-time
        } finally {
            /* Wipe di tutti i buffer sensibili */
            Arrays.fill(pin,  '\0');
            if (newHash != null) Arrays.fill(newHash, (byte) 0);
            Arrays.fill(salt, (byte) 0);   // (opzionale: se hai ancora bisogno del salt dopo, sposta questa riga)
        }
    }
// -------------------------------------------------------------------------
// Sezione Keystore / AES
// -------------------------------------------------------------------------
    /**
     * Ritorna la chiave simmetrica AES-GCM lunga 256 bit; la crea se non
     esiste.
     */
    private static SecretKey getOrCreateSecretKey() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (key != null) {
                return key;
            }
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(KEY_SIZE)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
// Richiede ogni volta biometria o credenziali dispositivo
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(false);
// Da API 30 in poi è possibile indicare i tipi di autenticazione accettati
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                        /* timeoutSec */ 0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG |
                                KeyProperties.AUTH_DEVICE_CREDENTIAL);
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            kg.init(builder.build());
            return kg.generateKey();
        } catch (IOException | GeneralSecurityException e) {
            // racchiudo I/O + problemi crypto in unchecked exception
            throw new IllegalStateException("Unable to create or retrieve AES key", e);
        }
    }
    /**
     * Restituisce un {@link Cipher} inizializzato per la <b>crittografia</b>.
     * <p>Usare quando <i>non esiste</i> ancora una passphrase SQLCipher
     cifrata.</p>
     * LʼIV va memorizzato a parte (p.es. in SharedPreferences).</p>
     */
    public static Cipher getCipherForEncrypt() throws GeneralSecurityException {
        SecretKey key = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }
    /**
     * Restituisce un {@link Cipher} inizializzato per la <b>decifratura</b> con
     IV noto.
     * <p>
     * Usare lʼIV precedentemente salvato assieme al testo cifrato della
     passphrase.
     * Il {@code Cipher} risultante può essere passato a
     * {link androidx.biometric.BiometricPrompt.CryptoObject} in modo che la
     decifratura avvenga
     * solo dopo unʼautenticazione biometrica.
     */
    public static Cipher getCipherForDecrypt(@NonNull byte[] iv) throws GeneralSecurityException {
        SecretKey key = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        return cipher;
    }

    //Sicurezza DATABASE
    /** Restituisce una SharedPreferences cifrata con AES-256/SIV-GCM e chiave custodita nel Keystore */
    private static SharedPreferences getEncryptedPrefs(Context context)
            throws GeneralSecurityException, IOException {

        // 1. Costruiamo (o recuperiamo) la chiave principale nell'Android Keystore
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        // 2. Creiamo le EncryptedSharedPreferences usando la chiave appena ottenuta
        return EncryptedSharedPreferences.create(
                context,                           // Context
                PREFERENCES_FILE_NAME,             // Nome file prefs
                masterKey,                         // MasterKey
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    /** Restituisce (o genera) una passphrase di 32 byte per SQLCipher */
    public static byte[] getOrCreateDatabasePassphrase(Context context) {
        try {
            SharedPreferences prefs = getEncryptedPrefs(context);
            String encoded = prefs.getString(DB_PASSPHRASE_KEY, null);

            if (encoded == null) {
                // Genera nuova passphrase a 256 bit
                byte[] newPassphrase = new byte[32];
                new SecureRandom().nextBytes(newPassphrase);

                // Salva in Base64
                prefs.edit()
                        .putString(DB_PASSPHRASE_KEY,
                                Base64.encodeToString(newPassphrase, Base64.NO_WRAP))
                        .apply();
                return newPassphrase;
            } else {
                // Decodifica passphrase esistente
                return Base64.decode(encoded, Base64.NO_WRAP);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Impossibile ottenere/creare la passphrase del database", e);
        }
    }
}
