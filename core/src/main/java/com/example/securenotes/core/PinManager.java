package com.example.securenotes.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.annotation.NonNull;
import java.util.Arrays;

/**
 * Gestore centralizzato per la verifica del PIN e il Lockout.
 * Usato sia da AuthViewModel (Login) che da VaultViewModel (Sblocco File).
 */
public class PinManager {

    public enum PinResult { SUCCESS, INCORRECT, LOCKED, ERROR }

    private final Context context;

    // Chiavi delle preferenze (Allineate con AuthViewModel originale)
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_FAILS = "pin_fail_count";
    private static final String KEY_LOCK_UNTIL = "pin_lock_until";
    private static final String KEY_BACKOFF_STEP = "pin_backoff_step";

    public PinManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Verifica PIN con gestione Lockout e Backoff esponenziale */
    public PinResult verifyPin(String inputPin) {
        try {
            SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(context);
            long now = System.currentTimeMillis();
            //KEY_LOCK_UNTIL conserva (nelle EncryptedSharedPreferences) un timestamp in millis (epoch) fino al quale il login è bloccato dopo troppi tentativi con PIN errato.
            long lockUntil = prefs.getLong(KEY_LOCK_UNTIL, 0L);

            // 1. Controllo Lockout
            if (now < lockUntil) {
                return PinResult.LOCKED;
            }

            // 2. Recupero Dati
            String saltB64 = prefs.getString(KEY_PIN_SALT, null);
            String hashB64 = prefs.getString(KEY_PIN_HASH, null);

            if (saltB64 == null || hashB64 == null) {
                // Controllo fallback legacy "user_pin" se necessario,
                // ma per sicurezza restituiamo ERROR se i dati crypto mancano.
                return PinResult.ERROR;
            }

            // carica salt/hash
            byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
            byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
            // verifica con confronto a tempo costante
            char[] pinChars = inputPin.toCharArray();

            boolean isMatch;
            try {
                isMatch = SecurityUtils.verifyPin(pinChars, salt, expected);
            } finally {
                Arrays.fill(pinChars, '\0');
            }

            // 3. Gestione Esito
            if (isMatch) {
                // Successo: Resetta contatori
                prefs.edit().putInt(KEY_FAILS, 0)
                        .putInt(KEY_BACKOFF_STEP, 0)
                        .putLong(KEY_LOCK_UNTIL, 0L)
                        .apply();
                return PinResult.SUCCESS;
            } else {
                // Errore: Gestione tentativi e blocco
                handleFailure(prefs, now);
                // Se dopo l'incremento siamo bloccati, ritorna LOCKED, altrimenti INCORRECT
                return isLocked() ? PinResult.LOCKED : PinResult.INCORRECT;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return PinResult.ERROR;
        }
    }

    private void handleFailure(SharedPreferences prefs, long now) {
        int fails = prefs.getInt(KEY_FAILS, 0) + 1;
        int step = prefs.getInt(KEY_BACKOFF_STEP, 0);

        if (fails >= 5) {
            // Backoff: 30s, 2m, 10m, 1h
            long[] backoff = {30_000L, 120_000L, 600_000L, 3_600_000L};
            long duration = backoff[Math.min(step, backoff.length - 1)];

            prefs.edit()
                    .putInt(KEY_FAILS, 0) // Resetta i fail count per ricominciare il ciclo dopo il lock
                    .putInt(KEY_BACKOFF_STEP, step + 1)
                    .putLong(KEY_LOCK_UNTIL, now + duration)
                    .apply();
        } else {
            prefs.edit().putInt(KEY_FAILS, fails).apply();
        }
    }

    public long getLockRemainingMillis() {
        try {
            long until = SecurityUtils.getEncryptedPrefs(context).getLong(KEY_LOCK_UNTIL, 0L);
            return Math.max(0L, until - System.currentTimeMillis());
        } catch (Exception e) { return 0L; }
    }

    public boolean isLocked() {
        return getLockRemainingMillis() > 0;
    }
}