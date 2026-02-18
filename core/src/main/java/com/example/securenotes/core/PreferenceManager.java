package com.example.securenotes.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Wrapper centralizzato intorno a EncryptedSharedPreferences.
 * – Tutti i valori sono cifrati AES-256/GCM.
 * – Nessun dato sensibile (PIN, chiavi, token) rimane in chiaro su FS.
 */
public class PreferenceManager {

    /*costanti*/
    private static final String PREF_FILE_NAME        = "secure_notes_prefs";

    private static final String KEY_PIN_HASH          = "pin_hash";
    private static final String KEY_PIN_SALT          = "pin_salt";
    private static final String KEY_IS_PIN_SET        = "is_pin_set";

    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";

    private static final String KEY_LOGIN_ATTEMPTS    = "login_attempts";
    private static final String KEY_LOCKOUT_TIMESTAMP = "lockout_timestamp";

    private static final String KEY_SESSION_TIMEOUT   = "session_timeout_ms";   // nuovo
    private static final long   FALLBACK_TIMEOUT_MS   = 3 * 60 * 1000L;         // 3′

    /*stato*/
    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Impossibile creare EncryptedSharedPreferences", e);
        }
    }

    /* SEZIONE PIN e BIOMETRIA */


    /*public void savePin(byte[] hash, byte[] salt) {
        prefs.edit()
                .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putBoolean(KEY_IS_PIN_SET, true)
                .apply();
    }*/

    public byte[] getPinHash() {
        String b64 = prefs.getString(KEY_PIN_HASH, null);
        return b64 == null ? null : Base64.decode(b64, Base64.NO_WRAP);
    }

    public byte[] getPinSalt() {
        String b64 = prefs.getString(KEY_PIN_SALT, null);
        return b64 == null ? null : Base64.decode(b64, Base64.NO_WRAP);
    }

    public boolean isPinSet() {
        return prefs.getBoolean(KEY_IS_PIN_SET, false);
    }

    public void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }

    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    /*SEZIONE LOCK-OUT*/


    public int getLoginAttempts() {
        return prefs.getInt(KEY_LOGIN_ATTEMPTS, 0);
    }

    public void setLoginAttempts(int attempts) {
        prefs.edit().putInt(KEY_LOGIN_ATTEMPTS, attempts).apply();
    }

    public void resetLoginAttempts() {
        setLoginAttempts(0);
    }

    public long getLockoutTimestamp() {
        return prefs.getLong(KEY_LOCKOUT_TIMESTAMP, 0L);
    }

    public void setLockoutTimestamp(long timestamp) {
        prefs.edit().putLong(KEY_LOCKOUT_TIMESTAMP, timestamp).apply();
    }

    /*SEZIONE TIMEOUT DI SESSIONE */

    /**
     * Ritorna il timeout di sessione scelto dall’utente.
     *
     * @param fallback valore da usare se la preferenza non è mai stata salvata
     */
    public long getSessionTimeoutMs(long fallback) {
        return prefs.getLong(KEY_SESSION_TIMEOUT, fallback);
    }

    /**
     * Overload comodo che restituisce il default di progetto (3 minuti).
     */
    public long getSessionTimeoutMs() {
        return getSessionTimeoutMs(FALLBACK_TIMEOUT_MS);
    }

    /**
     * Salva il nuovo timeout di sessione (intervallo in millisecondi).
     * Valori validi: 3-10 min (controllo a carico della UI).
     */
    public void setSessionTimeoutMs(long millis) {
        prefs.edit().putLong(KEY_SESSION_TIMEOUT, millis).apply();
    }
}
