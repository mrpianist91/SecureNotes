package com.example.securenotes.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

public class SecurityUtils {

    private static final String PREFERENCES_FILE_NAME = "secure_notes_prefs";
    private static final String DB_PASSPHRASE_KEY     = "db_passphrase";

    /** Restituisce una SharedPreferences cifrata con AES-256/SIV-GCM e chiave custodita nel Keystore */
    private static SharedPreferences getEncryptedPrefs(Context context)
            throws GeneralSecurityException, IOException {

        // 1. Costruiamo (o recuperiamo) la chiave principale nel Keystore
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)   // stessa sicurezza del vecchio AES256_GCM_SPEC
                .build();

        // 2. Creiamo le EncryptedSharedPreferences usando la chiave appena ottenuta
        return EncryptedSharedPreferences.create(
                context,                           // Context
                PREFERENCES_FILE_NAME,             // Nome file prefs
                masterKey,                         // MasterKey (non più solo alias)
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
