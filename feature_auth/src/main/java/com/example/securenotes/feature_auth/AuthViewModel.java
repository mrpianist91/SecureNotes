package com.example.securenotes.feature_auth;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.securenotes.core.PinManager;
import com.example.securenotes.core.SecurityUtils;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthViewModel extends AndroidViewModel {
    // Risultati possibili per il login PIN...manteniamo l'enum locale per compatibilità con i Fragment esistenti
    public enum LoginResult { SUCCESS, INCORRECT_PIN, LOCKED}

    private final MutableLiveData<LoginResult> loginResult = new MutableLiveData<>();
    private final MutableLiveData<Boolean> pinCreated = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // NUOVO: Delega la logica di verifica
    private final PinManager pinManager;

    /*private int pinFailCount = 0;
    private boolean wasPinExisting = false;*/
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_HASH = "pin_hash";
    /*private static final String KEY_FAILS = "pin_fail_count";
    private static final String KEY_LOCK_UNTIL = "pin_lock_until";
    private static final String KEY_BACKOFF_STEP = "pin_backoff_step";*/

    public AuthViewModel(@NonNull Application application) {
        super(application);
        this.pinManager = new PinManager(application);
        Log.d("AuthVM", "loginResult id=" + System.identityHashCode(loginResult));
    }

    /** LiveData per osservare l'esito del login (PIN) */
    public LiveData<LoginResult> getLoginResult() {
        return loginResult;
    }


    /** LiveData per osservare l'esito della creazione/modifica PIN */
    public LiveData<Boolean> getPinCreated() {
        return pinCreated;
    }

    /** Indica se un PIN era già registrato (usato per determinare se si tratta di modifica) */
    /*public boolean wasPinExisting() {
        return wasPinExisting;
    }*/

    /** Verifica se esiste già un PIN configurato */
    public boolean isPinSet() {
        try {
            SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(getApplication());
            return prefs.getString(KEY_PIN_HASH, null) != null/*|| prefs.getString("user_pin", null) != null*/; // fallback legacy
        } catch (Exception e) {
            // In caso di errore di lettura, per sicurezza consideriamo non impostato
            return false;
        }
    }

    /** Verifica in background il PIN inserito confrontandolo con quello salvato (hash PBKDF2) */
    public void verifyPin(@NonNull String inputPin) {
        Log.d("AuthVM", "VM id=" + System.identityHashCode(this));
        executor.execute(() -> {
            /*try {
                Log.d("AuthVM", "VM id=" + System.identityHashCode(this));
                SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(getApplication());
                long now = System.currentTimeMillis();
                //KEY_LOCK_UNTIL conserva (nelle EncryptedSharedPreferences) un timestamp in millis (epoch) fino al quale il login è bloccato dopo troppi tentativi con PIN errato.
                long lockUntil = prefs.getLong(KEY_LOCK_UNTIL, 0L);
                if (now < lockUntil) {
                     mainHandler.post(() -> loginResult.setValue(LoginResult.LOCKED));
                    Log.d("AuthVM", "emit " + LoginResult.LOCKED + " at " + System.nanoTime());

                    return;
                }

                String saltB64 = prefs.getString(KEY_PIN_SALT, null);
                String hashB64 = prefs.getString(KEY_PIN_HASH, null);

                 // Fallback legacy: se non migrato, usa ancora user_pin
                /*if (saltB64 == null || hashB64 == null) {
                String legacy = prefs.getString("user_pin", null);
                boolean ok = legacy != null && inputPin.equals(legacy);
                if (ok) {
                     prefs.edit()
                          .putInt(KEY_FAILS, 0)
                          .putInt(KEY_BACKOFF_STEP, 0)
                          .putLong(KEY_LOCK_UNTIL, 0L)
                          .apply();
                     mainHandler.post(() -> loginResult.setValue(LoginResult.SUCCESS));
                    Log.d("AuthVM", "emit " +  LoginResult.SUCCESS + " at " + System.nanoTime());

                } else {
                     int fails = prefs.getInt(KEY_FAILS, 0) + 1;
                     prefs.edit().putInt(KEY_FAILS, fails).apply();
                     mainHandler.post(() -> loginResult.setValue(LoginResult.INCORRECT_PIN));
                    Log.d("AuthVM", "emit " + LoginResult.INCORRECT_PIN + " at " + System.nanoTime());

                }
                return;
                }*/
/*
                // carica salt/hash
                byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
                byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
                // verifica con confronto a tempo costante
                char[] pinChars = inputPin.toCharArray();
                boolean ok;
                try {
                    ok = SecurityUtils.verifyPin(pinChars, salt, expected);
                } finally {
                    java.util.Arrays.fill(pinChars, '\0');
                }

                if (ok) {
                    prefs.edit()
                         .putInt(KEY_FAILS, 0)
                         .putInt(KEY_BACKOFF_STEP, 0)
                         .putLong(KEY_LOCK_UNTIL, 0L)
                         .apply();
                    mainHandler.post(() -> loginResult.setValue(LoginResult.SUCCESS));

                    Log.d("AuthVM", "loginResult = SUCCESS (via PIN)");
                    Log.d("AuthVM", "emit " + LoginResult.SUCCESS + " at " + System.nanoTime());

                } else {
                    int fails = prefs.getInt(KEY_FAILS, 0) + 1;
                    int step  = prefs.getInt(KEY_BACKOFF_STEP, 0);
                    if (fails >= 5) {
                             long[] backoff = {30_000L, 120_000L, 600_000L, 3_600_000L}; // 30s, 2m, 10m, 1h
                             long duration = backoff[Math.min(step, backoff.length - 1)];
                             prefs.edit()
                                  .putInt(KEY_FAILS, 0)
                                  .putInt(KEY_BACKOFF_STEP, step + 1)
                                  .putLong(KEY_LOCK_UNTIL, now + duration)
                                  .apply();
                             mainHandler.post(() -> loginResult.setValue(LoginResult.LOCKED));
                        Log.d("AuthVM", "emit " + LoginResult.LOCKED + " at " + System.nanoTime());

                    } else {
                             prefs.edit().putInt(KEY_FAILS, fails).apply();
                             mainHandler.post(() -> loginResult.setValue(LoginResult.INCORRECT_PIN));
                        Log.d("AuthVM", "emit " + LoginResult.INCORRECT_PIN + " at " + System.nanoTime());

                    }
                }

            } catch (Exception e) {
                mainHandler.post(() -> loginResult.setValue(LoginResult.INCORRECT_PIN));
                Log.d("AuthVM", "emit " + LoginResult.INCORRECT_PIN + " at " + System.nanoTime());

            }*/
            PinManager.PinResult result = pinManager.verifyPin(inputPin);

            mainHandler.post(() -> {
                switch (result) {
                    case SUCCESS:
                        loginResult.setValue(LoginResult.SUCCESS);
                        break;
                    case LOCKED:
                        loginResult.setValue(LoginResult.LOCKED);
                        break;
                    case INCORRECT:
                        loginResult.setValue(LoginResult.INCORRECT_PIN);
                        break;
                    default:
                        // Gestione errore generico come PIN errato per sicurezza UI
                        loginResult.setValue(LoginResult.INCORRECT_PIN);
                        break;
                }
            });
        });
    }

    /** Salva in modo sicuro il PIN (durante creazione o modifica) ed esegue eventuali operazioni post-creazione */
    public void createPin(@NonNull String newPin) {
        executor.execute(() -> {
            try {
                SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(getApplication());
                // Verifica se c'era già un PIN (se sì, siamo in modifica PIN)
               // wasPinExisting = prefs.getString(KEY_PIN_HASH, null) != null;

                // Salvataggio sicuro del PIN (NB: EncryptedSharedPreferences cifra automaticamente il valore)
                byte[] salt = SecurityUtils.generateSalt();//genera un Salt casuale
                char[] pinChars = newPin.toCharArray();
                byte[] hash = SecurityUtils.hashPin(pinChars, salt);// PBKDF2-HMAC-SHA512 (32 byte). Calcola l'hash dal PIN+Salt...il pin viene passato in array di Char così può essere eliminato/azzerato dopo l'uso (non si può fare con String)
                //Salvo il salt e l'hash del pin in EncryptedSharedPreferences. Li codifico in Base64 perchè le SharedPreferences memorizzano String e non byte []
                prefs.edit().putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                        .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                        .remove("user_pin") // migrazione: elimina lo schema vecchio
                        .apply();//applica le modifiche in modo asincrono
                Arrays.fill(hash, (byte) 0); // azzero il contenuto di hash...forse dovrei azzerare anche il newPin, ma come fare??? DA RIVEDERE
                java.util.Arrays.fill(pinChars, '\0'); // azzero il contenuto di pinChars
                //  prepara (se possibile) la chiave biometrica AES-GCM nel Keystore, senza UI
                try {
                    BiometricHelper.BiometricCapability cap =
                            BiometricHelper.ensureBiometricKey(getApplication());
                    // Non fare altro qui: se cap == NOT_ENROLLED, ci penserà il Fragment a forzare l’enrollment.
                } catch (Exception e) {
                    android.util.Log.w("AuthViewModel", "ensureBiometricKey failed", e);
                }
                // (Facoltativo) Si potrebbe impostare una flag "onboarding completato" qui
                mainHandler.post(() -> pinCreated.setValue(true));
            } catch (Exception e) {
                mainHandler.post(() -> pinCreated.setValue(false));
            }
        });
    }

    /** Valuta la robustezza del PIN (0=debole, 100=forte) in base a lunghezza e pattern.
     * legge la lunghezza minima da risorsa (@integer/min_pin_length),
     * restituisce 0 se il PIN è più corto del minimo,
     * calcola un punteggio base in funzione della lunghezza,
     * taglia il punteggio per pattern deboli: tutti uguali, sequenza ascendente o discendente, ≤2 cifre distinte, prefisso da “anno”,
     * dà un piccolo bonus se è lungo e non presenta pattern banali. */
    public int calculatePinStrength(@NonNull String pin) {
        if (pin.isEmpty()) return 0;
        final int len = pin.length();
        final int minLen = getApplication().getResources().getInteger(R.integer.min_pin_length);
        // Sotto la lunghezza minima mostriamo 0 (chiarissimo all'utente)
        if (len < minLen) return 0;

        // Base dalla lunghezza: cresce fino a ~96 a 8 cifre, poi cappata
        int score = Math.min(len * 12, 96);

        // Pattern deboli: tutti uguali, sequenza ↑ o ↓, poche cifre distinte
        boolean allSame = true;
        boolean ascending = true;
        boolean descending = true;
        boolean[] seen = new boolean[10];
        seen[charToDigit(pin.charAt(0))] = true;
        for (int i = 1; i < len; i++) {
             char c = pin.charAt(i);
             int d = charToDigit(c);
             seen[d] = true;
             if (c != pin.charAt(0)) allSame = false;
             if (c != pin.charAt(i - 1) + 1) ascending = false;
             if (c != pin.charAt(i - 1) - 1) descending = false;
        }
        int unique = 0;
        for (boolean b : seen) if (b) unique++;

        if (allSame || ascending || descending) {
            score = Math.min(score, 20);     // es. 111111, 123456, 654321
        } else if (unique <= 2) {
            score = Math.min(score, 40);     // usa solo 1-2 cifre diverse complessivamente
        }
        if (len >= 4 && (pin.startsWith("19") || pin.startsWith("20"))) {
            score = Math.min(score, 50);     // pattern da "anno" penalizzato
        }

        // Piccolo bonus se abbastanza lungo e non banale
        if (!(allSame || ascending || descending) && unique >= 3 && len >= minLen + 2) {
            score = Math.min(score + 8, 100);
        }
        return Math.max(0, Math.min(score, 100));
    }

    private static int charToDigit(char c) {
        int d = c - '0';
        return (d >= 0 && d <= 9) ? d : 0;
    }

    // Ritorna il timestamp (ms) fino al quale l'utente è bloccato; 0 se nessun lock.
    /*public long getLockUntilMillis() {
        SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(getApplication());
        return prefs.getLong(KEY_LOCK_UNTIL, 0L);
    }*/
    // Ritorna i ms residui di lock; 0 se non c'è lock attivo o già scaduto.
        public long getLockRemainingMillis() {

           /* long until = getLockUntilMillis();
            long now = System.currentTimeMillis();
            return Math.max(0L, until - now);*/
            return pinManager.getLockRemainingMillis();
    }

}
