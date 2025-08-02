package com.example.securenotes.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;
public class PreferenceManager {
    private static final String PREF_FILE_NAME = "secure_notes_prefs";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";
    private static final String KEY_IS_PIN_SET = "is_pin_set";
    private static final String KEY_LOGIN_ATTEMPTS = "login_attempts";
    private static final String KEY_LOCKOUT_TIMESTAMP = "lockout_timestamp";
    private final SharedPreferences sharedPreferences;

    public PreferenceManager(Context context) {
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                sharedPreferences = EncryptedSharedPreferences.create(
                        context,
                        PREF_FILE_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (GeneralSecurityException | IOException e) {
// Un errore qui è critico e indica un problema con il Keystore
// o con la crittografia del dispositivo.
                throw new RuntimeException("Impossibile creare EncryptedSharedPreferences", e);
            }
        }


    public void savePin(byte [] hash, byte [] salt) {
        String hashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP);
        String saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        sharedPreferences.edit()
                .putString(KEY_PIN_HASH, hashBase64)
                .putString(KEY_PIN_SALT, saltBase64)
                .putBoolean(KEY_IS_PIN_SET, true)
                .apply();
    }
    public byte [] getPinHash() {
        String hashBase64 = sharedPreferences.getString(KEY_PIN_HASH, null);
        return (hashBase64 == null)? null : Base64.decode(hashBase64,
                Base64.NO_WRAP);
    }
    public byte [] getPinSalt() {
        String saltBase64 = sharedPreferences.getString(KEY_PIN_SALT, null);
        return (saltBase64 == null) ? null : Base64.decode(saltBase64,
                Base64.NO_WRAP);
    }
    /*
     Controlla se un PIN è già stato impostato.
            * @return true se il PIN è stato impostato, false altrimenti.*/
    public boolean isPinSet() {
        return sharedPreferences.getBoolean(KEY_IS_PIN_SET, false);
    }

    /*
* Imposta la preferenza dell'utente per l'uso della biometria.
* @param enabled true per abilitare, false per disabilitare.*/
    public void setBiometricEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED,
                enabled).apply();
    }
/*
 Controlla se l'utente ha abilitato l'autenticazione biometrica.
 @return true se abilitata, false altrimenti.*/

    public boolean isBiometricEnabled() {
        return sharedPreferences.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public int getLoginAttempts() {
        return sharedPreferences.getInt(KEY_LOGIN_ATTEMPTS, 0);
    }

    public void setLoginAttempts(int attempts) {
                sharedPreferences.edit().putInt(KEY_LOGIN_ATTEMPTS, attempts).apply();
    }


    public void resetLoginAttempts() {
        setLoginAttempts(0);
    }

    public long getLockoutTimestamp() {
        return sharedPreferences.getLong(KEY_LOCKOUT_TIMESTAMP, 0L);
    }

    public void setLockoutTimestamp(long timestamp) {
                sharedPreferences.edit().putLong(KEY_LOCKOUT_TIMESTAMP,
                        timestamp).apply();
    }

}
