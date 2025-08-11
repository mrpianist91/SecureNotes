package com.example.securenotes.feature_auth;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthViewModel extends AndroidViewModel {
    // Risultati possibili per il login PIN
    public enum LoginResult { SUCCESS, INCORRECT_PIN, LOCKED }

    private final MutableLiveData<LoginResult> loginResult = new MutableLiveData<>();
    private final MutableLiveData<Boolean> pinCreated = new MutableLiveData<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int pinFailCount = 0;
    private boolean wasPinExisting = false;

    public AuthViewModel(@NonNull Application application) {
        super(application);
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
    public boolean wasPinExisting() {
        return wasPinExisting;
    }

    /** Verifica se esiste già un PIN configurato */
    public boolean isPinSet() {
        try {
            SharedPreferences prefs = getEncryptedPrefs();
            return prefs.getString("user_pin", null) != null;
        } catch (Exception e) {
            // In caso di errore di lettura, per sicurezza consideriamo non impostato
            return false;
        }
    }

    /** Verifica in background il PIN inserito confrontandolo con quello salvato (cifrato) */
    public void verifyPin(@NonNull String inputPin) {
        executor.execute(() -> {
            try {
                SharedPreferences prefs = getEncryptedPrefs();
                String savedPin = prefs.getString("user_pin", null);
                if (savedPin == null) {
                    // Nessun PIN salvato (non dovrebbe accadere se arriva qui)
                    mainHandler.post(() -> loginResult.setValue(LoginResult.INCORRECT_PIN));
                    return;
                }
                // Confronto PIN (qui salvato in chiaro nelle prefs cifrate; si potrebbe utilizzare hash per maggior sicurezza)
                if (inputPin.equals(savedPin)) {
                    pinFailCount = 0;
                    mainHandler.post(() -> loginResult.setValue(LoginResult.SUCCESS));
                } else {
                    pinFailCount++;
                    if (pinFailCount >= 5) {
                        // Dopo 5 tentativi falliti -> LOCKED
                        pinFailCount = 0;
                        mainHandler.post(() -> loginResult.setValue(LoginResult.LOCKED));
                    } else {
                        mainHandler.post(() -> loginResult.setValue(LoginResult.INCORRECT_PIN));
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> loginResult.setValue(LoginResult.INCORRECT_PIN));
            }
        });
    }

    /** Salva in modo sicuro il PIN (durante creazione o modifica) ed esegue eventuali operazioni post-creazione */
    public void createPin(@NonNull String newPin) {
        executor.execute(() -> {
            try {
                SharedPreferences prefs = getEncryptedPrefs();
                // Verifica se c'era già un PIN (se sì, siamo in modifica PIN)
                wasPinExisting = prefs.getString("user_pin", null) != null;
                // Salvataggio sicuro del PIN (NB: EncryptedSharedPreferences cifra automaticamente il valore)
                prefs.edit().putString("user_pin", newPin).apply();
                // Genera/ottiene la chiave biometrica se disponibile, senza mostrare prompt (preparazione)
                try {
                    BiometricHelper helper = BiometricHelper.getInstance(AuthViewModel.this.getApplication());
                    helper.getClass().getDeclaredMethod("getOrCreateBiometricKey").invoke(helper);
                } catch (Exception ignored) { }
                // (Facoltativo) Si potrebbe impostare una flag "onboarding completato" qui
                mainHandler.post(() -> pinCreated.setValue(true));
            } catch (Exception e) {
                mainHandler.post(() -> pinCreated.setValue(false));
            }
        });
    }

    /** Valuta empiricamente la robustezza di un PIN in base a lunghezza e pattern (0 = debole, 100 = molto forte) */
    public int calculatePinStrength(@NonNull String pin) {
        if (pin.isEmpty()) return 0;
        int length = pin.length();
        int score = Math.min(length * 20, 100);  // base score dalla lunghezza (ogni cifra ~20 punti, max 100)
        // Penalizzazioni per pattern deboli:
        boolean allSame = true;
        boolean sequential = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) != pin.charAt(0)) {
                allSame = false;
            }
            if (pin.charAt(i) != pin.charAt(i-1) + 1) {
                sequential = false;
            }
        }
        if (allSame || sequential) {
            // Se tutti i numeri uguali (es. 1111) o sequenziali (es. 1234) -> riduce robustezza
            score = Math.min(score, 40);
        }
        if (length < 4) {
            score = 0;  // PIN troppo corto
        } else if (length == 4 && (allSame || sequential)) {
            score = 20; // PIN 4 cifre debole
        }
        return score;
    }

    /** Ottiene le SharedPreferences cifrate (usando MasterKey nel Keystore) */
    private SharedPreferences getEncryptedPrefs() throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(getApplication())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                getApplication(),
                "secure_notes_prefs",       // stesso file usato in SecurityUtils per coerenza
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }
}
