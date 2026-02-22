package com.example.securenotes.feature_auth;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.securenotes.core.AuthManager;
import com.example.securenotes.core.Event;
import com.example.securenotes.core.SecurityUtils;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthViewModel extends AndroidViewModel {
    // Risultati possibili per il login PIN
    public enum LoginResult { SUCCESS, INCORRECT_PIN, LOCKED}

    private final MutableLiveData<LoginResult> loginResult = new MutableLiveData<>();

    private final MutableLiveData<Event<Boolean>> pinCreated = new MutableLiveData<>();

    //LiveData per l'esito del cambio PIN
    private final MutableLiveData<Event<Boolean>> pinChanged = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    //Delega la logica di autenticazione
    //private final PinManager pinManager;
    private final AuthManager authManager;

    /*private int pinFailCount = 0;
    private boolean wasPinExisting = false;*/

// Chiavi gestite da SecurityUtils/AuthManager)
    private static final String KEY_PIN_SALT = SecurityUtils.KEY_PIN_SALT;
    private static final String KEY_PIN_HASH = SecurityUtils.KEY_PIN_HASH;

    //Variabile volatile per tenere il vecchio PIN in memoria durante la transizione
    // tra SettingsFragment e CreatePinFragment. Verrà azzerata subito dopo l'uso.
    private String tempOldPinForChange = null;


    public AuthViewModel(@NonNull Application application) {
        super(application);
        //this.pinManager = new PinManager(application);
        this.authManager = AuthManager.getInstance(application);
        Log.d("AuthVM", "loginResult id=" + System.identityHashCode(loginResult));
    }

    /** LiveData per osservare l'esito del login (PIN) */
    public LiveData<LoginResult> getLoginResult() {
        return loginResult;
    }


    /** LiveData per osservare l'esito della creazione/modifica PIN */
    public LiveData<Event<Boolean>> getPinCreated() {
        return pinCreated;
    }

    //Getter per osservare il cambio PIN
    public LiveData<Event<Boolean>> getPinChanged() { return pinChanged; }


    /** Verifica se esiste già un PIN configurato */
    public boolean isPinSet() {
        try {
            SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(getApplication());
            return prefs.getString(KEY_PIN_HASH, null) != null;
        } catch (Exception e) {
            // In caso di errore di lettura, per sicurezza consideriamo non impostato
            return false;
        }
    }

    /** Verifica in background il PIN inserito confrontandolo con quello salvato (hash PBKDF2) */
    public void verifyPin(@NonNull String inputPin) {
        Log.d("AuthVM", "VM id=" + System.identityHashCode(this));
        executor.execute(() -> {

            //PinManager.PinResult result = pinManager.verifyPin(inputPin);
            AuthManager.AuthResult result = authManager.verifyPin(inputPin);
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
                        // Gestione errore generico come "PIN errato"
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
                Arrays.fill(hash, (byte) 0); // azzero il contenuto di hash
                java.util.Arrays.fill(pinChars, '\0'); // azzero il contenuto di pinChars
                //  prepara (se possibile) la chiave biometrica AES-GCM nel Keystore, senza UI
                try {// Tenta enrollment chiave biometrica (se possibile)
                    BiometricHelper.BiometricCapability cap =
                            BiometricHelper.ensureBiometricKey(getApplication());
                    // Non fare altro qui: se cap == NOT_ENROLLED, ci penserà il Fragment a forzare l’enrollment.
                } catch (Exception e) {
                    android.util.Log.w("AuthViewModel", "ensureBiometricKey failed", e);
                }
                mainHandler.post(() -> pinCreated.setValue(new Event<>(true)));
            } catch (Exception e) {
                mainHandler.post(() -> pinCreated.setValue(new Event<>(false)));
            }
        });
    }

    //LOGICA CAMBIO PIN (Utente Esistente)

    /**
     * Da chiamare in SettingsFragment DOPO aver validato il vecchio PIN.
     * Memorizza temporaneamente il vecchio PIN per usarlo nel Re-Wrap.
     */
    public void setTempOldPinForChange(String oldPin) {
        this.tempOldPinForChange = oldPin;
    }

    /**
     * Esegue il Re-Wrap della Master Key usando il vecchio PIN memorizzato e il nuovo PIN fornito.
     */
    public void changePin(@NonNull String newPin) {
        if (tempOldPinForChange == null) {
            pinChanged.setValue(new Event<>(false));
            return;
        }

        executor.execute(() -> {
            try {
                // Esecuzione Re-Wrap (Decifra DB key con vecchio PIN, Cifra con nuovo PIN)
                // Questa funzione in SecurityUtils aggiorna anche il Salt e l'Hash del PIN nelle Prefs
                SecurityUtils.reWrapMasterKey(getApplication(), tempOldPinForChange, newPin);

                // Successo
                mainHandler.post(() -> pinChanged.setValue(new Event<>(true)));
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> pinChanged.setValue(new Event<>(false)));
            } finally {
                // Rimuoviamo il vecchio PIN dalla memoria
                tempOldPinForChange = null;
            }
        });
    }

    /**Valuta la robustezza del PIN su una scala 0–100.
     Restituisce 0 se il PIN è vuoto o più corto di min_pin_length.
     Il punteggio base cresce con la lunghezza (10 pt per cifra, max 80).
     Vengono rilevati e penalizzati (score ≤ 20) quattro pattern deboli:
     cifre tutte uguali, sequenza strettamente ascendente, sequenza strettamente
     discendente, blocco ripetuto (es. "12341234"). Se si usano ≤ 2 cifre distinte
     il cap scende a 40. La data di un anno (19xx / 20xx) porta il cap a 50.
     Se il PIN è abbastanza lungo, privo di pattern banali e usa ≥ 4 cifre distinte,
     viene aggiunto un bonus di 8 punti (max 100).
     Il risultato finale è sempre nel range [0, 100] */
    public int calculatePinStrength(@NonNull String pin) {
        if (pin.isEmpty()) return 0;//stringa vuota -> 0 immediato
        final int len = pin.length();
        final int minLen = getApplication().getResources().getInteger(R.integer.min_pin_length);
        // Sotto la lunghezza minima mostriamo 0 (chiarissimo all'utente)
        if (len < minLen) return 0;

        // Base dalla lunghezza: cresce fino a 80 a 8 cifre (non di più).
        //  - 4 cifre → 40
        //  - 6 cifre → 60
        //  - 8+ cifre → 80 (massimo base)
        //  - Il cap a 80 riserva spazio per il bonus finale e impedisce che la sola lunghezza raggiunga 100.
        int score = Math.min(len * 10, 80);

        // Pattern deboli: tutti uguali, sequenza ascendente o discendente, poche cifre distinte
        boolean allSame = true;
        boolean ascending = true;
        boolean descending = true;
        boolean[] seen = new boolean[10];//array di 10 booleani per tracciare quali cifre compaiono ("seen")
        seen[charToDigit(pin.charAt(0))] = true;//registra subito la prima cifra
        /*Scorre il PIN dalla seconda cifra in poi:
        - seen[d] = true → segna ogni cifra come "vista", impostando a "true" il corrispondente elemento dell'array.
        - allSame: diventa false appena una cifra differisce dalla prima (1111 → rimane true).
        - ascending: diventa false se una cifra non è esattamente +1 rispetto alla precedente (1234 → rimane true; 1235 → diventa false).
        - descending: diventa false, per sequenze discendenti "4321...".*/
        for (int i = 1; i < len; i++) {
             char c = pin.charAt(i);
             int d = charToDigit(c);
             seen[d] = true;
             if (c != pin.charAt(0)) allSame = false;
             if (c != pin.charAt(i - 1) + 1) ascending = false;
             if (c != pin.charAt(i - 1) - 1) descending = false;
        }
        //Conta le cifre distinte usate nel PIN (es. "1122" → unique = 2), perchè le uniche cifre diverse che appaiono sono 1 e 2.
        int unique = 0;
        for (boolean b : seen) if (b) unique++;

        // Blocco ripetuto dall'inizio: es. "12341234", "56785678", "321321XX".
        // Conta quante volte il blocco di lunghezza p si ripete consecutivamente a partire
        // dalla prima cifra; penalizza se copre almeno metà del PIN (reps*p*2 >= len).
        boolean repeatedBlock = false;
        for (int p = 1; p <= len / 2; p++) {
            String block = pin.substring(0, p);
            int reps = 0;
            for (int j = 0; j + p <= len; j += p) {
                if (pin.startsWith(block, j)) reps++;
                else break;
            }
            if (reps >= 2 && reps * p * 2 >= len) {
                repeatedBlock = true;
                break;
            }
        }

        if (allSame || ascending || descending || repeatedBlock) {
            score = Math.min(score, 20);     // es. 111111, 123456, 654321, 12341234
        } else if (unique <= 2) {
            score = Math.min(score, 40);     // usa solo 1-2 cifre diverse complessivamente
        }
        if (len >= 4 && (pin.startsWith("19") || pin.startsWith("20"))) {
            score = Math.min(score, 50);     // pattern da "anno" penalizzato
        }

        // Piccolo bonus se abbastanza lungo e non banale
        if (!repeatedBlock && !(allSame || ascending || descending) && unique >= 4 && len >= minLen + 2) {
            score = Math.min(score + 8, 100);
        }
        return Math.max(0, Math.min(score, 100));
    }

    /** Restituisce un suggerimento testuale sul motivo per cui il PIN è debole,
     *  oppure stringa vuota se il PIN è accettabile (strength ≥ 60). */
    public String getPinHint(@NonNull String pin) {
        final int len = pin.length();
        if (len < 2) return ""; // Con una sola cifra i flag allSame/ascending/descending sarebbero trivialmente true

        boolean allSame = true, ascending = true, descending = true;
        boolean[] seen = new boolean[10];
        seen[charToDigit(pin.charAt(0))] = true;
        for (int i = 1; i < len; i++) {
            char c = pin.charAt(i);
            seen[charToDigit(c)] = true;
            if (c != pin.charAt(0))        allSame    = false;
            if (c != pin.charAt(i - 1) + 1) ascending  = false;
            if (c != pin.charAt(i - 1) - 1) descending = false;
        }
        int unique = 0;
        for (boolean b : seen) if (b) unique++;

        boolean repeatedBlock = false;
        for (int p = 1; p <= len / 2; p++) {
            String block = pin.substring(0, p);
            int reps = 0;
            for (int j = 0; j + p <= len; j += p) {
                if (pin.startsWith(block, j)) reps++;
                else break;
            }
            if (reps >= 2 && reps * p * 2 >= len) { repeatedBlock = true; break; }
        }

        if (allSame)      return "Evita cifre tutte uguali";
        if (ascending)    return "Evita sequenze crescenti (es. 1234)";
        if (descending)   return "Evita sequenze decrescenti (es. 4321)";
        if (repeatedBlock) return "Evita blocchi ripetuti (es. 1212, 321321)";
        if (unique <= 2)  return "Usa almeno 3 cifre diverse";
        if (len >= 4 && (pin.startsWith("19") || pin.startsWith("20")))
            return "Evita anni come prefisso (es. 1990)";
        if (calculatePinStrength(pin) < 60) return "Allungalo o usa più cifre diverse";
        return "";
    }

    //Torniamo il valore numerico (int) dato il char in input
    private static int charToDigit(char c) {
        int d = c - '0';//In pratica è una sottrazione tra interi (anche se sono char!).
        return (d >= 0 && d <= 9) ? d : 0;//in pratica non ritorna mai 0, perchè l'utente può inserire solo numeri
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
            return authManager.getLockRemainingMillis();
    }

}
