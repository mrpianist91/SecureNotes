/* SecureNotes – AuthViewModel
 * ------------------------------------------------------------
 * Gestisce: creazione del PIN di fallback, verifica del PIN,
 * lock-out a 5 tentativi, integrazione con BiometricPrompt.
 * Tutte le operazioni crittografiche vengono effettuate su threads secondari.
 */
package com.example.securenotes.feature_auth;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import android.os.Handler;
import android.os.Looper;
import com.example.securenotes.core.PreferenceManager;
import com.example.securenotes.core.SecurityUtils;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AuthViewModel extends ViewModel {
    /* ---- costanti di sicurezza ---- */
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 30_000L; // 30 secondi
    /* ---- dipendenze ---- */
    private final PreferenceManager prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainH = new Handler(Looper.getMainLooper());
    /* ---- stato esposto alla UI ---- */
    private final MutableLiveData<PinCreationState> _createState = new MutableLiveData<>();
    public LiveData<PinCreationState> createState = _createState;
    private final MutableLiveData<LoginState> _loginState = new MutableLiveData<>();
    public LiveData<LoginState> loginState = _loginState;


    public AuthViewModel(@NonNull PreferenceManager prefs) {
        this.prefs = prefs;
    }

    /* Getters osservabili dalla UI */
    public LiveData<PinCreationState> getPinCreationState() { return createState; }
    public LiveData<LoginState>       getLoginState()       { return loginState; }


    public boolean isPinSet() {
        return prefs.isPinSet();
    }
    public void setBiometricEnabled(boolean enabled) {
        prefs.setBiometricEnabled(enabled);
    }

    public boolean isBiometricEnabled() {
        return prefs.isBiometricEnabled();
    }

    public void successfulBiometricLogin() {
        _loginState.setValue(LoginState.SUCCESS);
    }

    /* ==========================================================
    =============== CREAZIONE PIN ==========================
    ========================================================== */
    public void createPin(@NonNull String pin, @NonNull String confirmPin) {
        if (pin.isEmpty() || confirmPin.isEmpty()) {
            _createState.setValue(PinCreationState.EMPTY_FIELDS);
            return;
        }
        if (!pin.equals(confirmPin)) {
            _createState.setValue(PinCreationState.MISMATCH);
            return;
        }
// Esecuzione off-thread
        char[] pinChars = pin.toCharArray();
        executor.execute(() -> {
            byte[] salt = null;
            byte[] hash = null;
            try {
                salt = SecurityUtils.generateSalt();
                hash = SecurityUtils.hashPin(pinChars, salt); // hashPin effettua già il wiping di pinChars
                prefs.savePin(hash, salt);
                mainH.post(() -> _createState.setValue(PinCreationState.SUCCESS));
            } catch (Exception e) {
                mainH.post(() -> _createState.setValue(PinCreationState.ERROR));
            } finally {
                if (salt != null) Arrays.fill(salt, (byte) 0);
                if (hash != null) Arrays.fill(hash, (byte) 0);
            }
        });
    }

    /* ==========================================================
    ================= LOGIN ================================
    ========================================================== */
    public void loginWithPin(@NonNull String pin) {
        long now = System.currentTimeMillis();
        long lockedUntil = prefs.getLockoutTimestamp();
        if (lockedUntil > now) {
            long remaining = lockedUntil - now;
            _loginState.setValue(LoginState.locked(remaining));
            return;
        }
        char[] pinChars = pin.toCharArray();
        executor.execute(() -> {
            byte[] salt = prefs.getPinSalt();
            byte[] storedHash = prefs.getPinHash();
            boolean success = false;
            try {
                if (salt != null && storedHash != null) {
                    success = SecurityUtils.verifyPin(pinChars, storedHash, salt);
                }
            } catch (Exception e) {
                mainH.post(() -> _loginState.setValue(LoginState.failure(0)));
            }
            finally {
                Arrays.fill(pinChars, '\0'); // wipe
                if (salt != null) Arrays.fill(salt, (byte) 0);
                if (storedHash != null) Arrays.fill(storedHash, (byte) 0);
            }
            if (success) {
                prefs.resetLoginAttempts();
                mainH.post(() -> _loginState.setValue(LoginState.SUCCESS));
            } else {
// 1. Leggo il contatore corrente dal PreferenceManager
                int attempts = prefs.getLoginAttempts() + 1;
// 2. Persiste il nuovo contatore (metodo void)
                prefs.setLoginAttempts(attempts);
// 3. Verifica lock-out e notifica la UI
                if (attempts >= MAX_ATTEMPTS) {
                    prefs.setLockoutTimestamp(now + LOCKOUT_MS);
                    prefs.resetLoginAttempts();
                    mainH.post(() -> _loginState.setValue(LoginState.locked(LOCKOUT_MS)));
                } else {
                    mainH.post(() -> _loginState.setValue(LoginState.failure(attempts)));//3
                }
            }
        });
    }

    /*public void calculatePinStrength(String pin) {
        _pinStrengthState.setValue(PinStrengthState.calculate(pin));
    }
    public boolean isBiometricAuthAvailable(Context context) {
        BiometricManager biometricManager = BiometricManager.from(context);
        int canAuthenticate =
                biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRON
                        G | BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }*/
    /* ==========================================================
    ================= CLEAN-UP =============================
    ========================================================== */
    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /* ==========================================================
    ================= ENUM / CLASSI DI STATO =================
    ========================================================== */
    /* Classi di stato per una gestione pulita della UI
    class PinCreationState {
        enum Status { IDLE, LOADING, SUCCESS, ERROR }
        private final Status status;
        private final String error;
        private PinCreationState(Status status, String error) { this.status =
                status; this.error = error; }
        public Status getStatus() { return status; }
        public String getError() { return error; }
        public static PinCreationState idle() { return new
                PinCreationState(Status.IDLE, null); }
        public static PinCreationState loading() { return new
                PinCreationState(Status.LOADING, null); }
        public static PinCreationState success() { return new
                PinCreationState(Status.SUCCESS, null); }
        public static PinCreationState error(String message) { return new
                PinCreationState(Status.ERROR, message); }
    }
    */

    public enum PinCreationState {
        SUCCESS, // PIN creato e salvato
        EMPTY_FIELDS, // uno o entrambi i campi vuoti
        MISMATCH, // pin ≠ confirmPin
        ERROR // eccezione (es. I/O, Security)
    }

    public static final class LoginState {
        public enum Status {SUCCESS, FAILURE, LOCKED}

        public final Status status;
        public final int attempts; // usato solo se FAILURE
        public final long millisLeft; // usato solo se LOCKED

        private LoginState(Status s, int a, long m) {
            status = s;
            attempts = a;
            millisLeft = m;
        }

        public static LoginState SUCCESS = new LoginState(Status.SUCCESS, 0, 0);

        public static LoginState failure(int atts) {

            return new LoginState(Status.FAILURE, atts, 0);
        }

        public static LoginState locked(long ms) {
            return new LoginState(Status.LOCKED, 0, ms);
        }
/*
        public Status getStatus() { return status; }
        public int getAttempts() { return attempts; }
        public long getMillisLeft() { return millisLeft; }*/
    }

}