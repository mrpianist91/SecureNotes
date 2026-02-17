package com.example.securenotes;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.securenotes.backup_worker.BackupWorker;
import com.example.securenotes.core.AuthManager;
import com.example.securenotes.core.Event;
import com.example.securenotes.core.PreferenceManager;
import com.example.securenotes.core.SecurityUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsViewModel extends AndroidViewModel {
//per ciascun oggetto osservato usiamo il MutableLiveData per poterlo gestire (osservare e modificare) internamente a questa classe (SettingsViewModel), mentre usiamo il LiveData per poterlo fare (solo) osservare dall’UI.
    // --- Stati UI (Observable) ---
// isLoading è un semaforo. =“true”: Stiamo facendo un'operazione pesante (es. ricifratura //del database). Il Fragment deve mostrare una rotellina e bloccare i click. =”false”: Tutto //fermo. L'utente può interagire.

    private final MutableLiveData<Boolean> _isLoading = new MutableLiveData<>(false);
    public LiveData<Boolean> isLoading = _isLoading;

    // Eventi One-Shot (Snackbar, Toast, Navigation)… Usiamo il wrapper Event<> per evitare //che, se ruoti lo schermo, il messaggio appaia di nuovo. L'evento si "consuma" una volta sola.
    //statusMessage serve per inviare messaggi temporanei come Toast o Snackbar ("Password errata", "Backup completato").
    private final MutableLiveData<Event<String>> _statusMessage = new MutableLiveData<>();
    public LiveData<Event<String>> statusMessage = _statusMessage;

    // Richiesta di Autenticazione (Il Fragment deve lanciare il BiometricPrompt)
    // Contiene l'azione pendente (PendingAction) da eseguire dopo il successo
// NB PendingAction è l’enum definito poco sotto
    private final MutableLiveData<Event<PendingAction>> _authRequest = new MutableLiveData<>();
    public LiveData<Event<PendingAction>> authRequest = _authRequest;

    private final MutableLiveData<Event<PendingAction>> _actionToExecute = new MutableLiveData<>();
    public LiveData<Event<PendingAction>> actionToExecute = _actionToExecute;

    // Auth Fallback - Risultato verifica PIN manuale da comunicare/osservato dal Fragment
    private final MutableLiveData<Event<AuthManager.AuthResult>> _authPinResult = new MutableLiveData<>();
    public LiveData<Event<AuthManager.AuthResult>> authPinResult = _authPinResult;

    // --- Dipendenze ---
    private final AuthManager authManager;//gestisce la logica reale (Keystore, PIN, contatori)
    private final PreferenceManager prefsManager;// Gestisce l’EncryptedSharedPreference
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final WorkManager workManager; // Istanza WorkManager

    // Observer mantenuto come campo per poterlo rimuovere se necessario (opzionale in VM)
    private final Observer<List<WorkInfo>> backupObserver;

    // Enum per tracciare cosa l'utente voleva fare prima/dopo del controllo biometrico
    public enum PendingAction { CHANGE_PIN, BACKUP }

    public SettingsViewModel(@NonNull Application application) {
        super(application);
        Context context = application.getApplicationContext();
        this.authManager = AuthManager.getInstance(context);
        this.prefsManager = new PreferenceManager(context);
        this.workManager = WorkManager.getInstance(context);

        // NEW: Inizializza l'Observer per il Backup
        backupObserver = workInfos -> {
            if (workInfos == null || workInfos.isEmpty()) return;

            WorkInfo info = workInfos.get(0);
            WorkInfo.State state = info.getState();

            // LOGICA DI STATO RICHIESTA:
            // 1. RUNNING/ENQUEUED -> isLoading = true (Blocca UI)
            boolean isWorking = (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED);
            _isLoading.setValue(isWorking);

            // 2. SUCCEEDED -> isLoading = false (già fatto sopra) + Messaggio Successo
            if (state == WorkInfo.State.SUCCEEDED) {
                _statusMessage.setValue(new Event<>("Backup completato con successo!"));
                // Opzionale: Pulisce lo stato per evitare messaggi ripetuti al riavvio
                workManager.pruneWork();
            }
            // 3. FAILED -> Messaggio Errore (estratto dai Data)
            else if (state == WorkInfo.State.FAILED) {
                String error = info.getOutputData().getString("error");
                _statusMessage.setValue(new Event<>("Backup fallito: " + (error != null ? error : "Errore generico")));
            }
        };

        // NEW: Attiva l'osservazione "Forever" (poiché siamo nel ViewModel e non abbiamo un LifecycleOwner View)
        // Usiamo il tag univoco "backup_unique" che definiremo nel triggerBackup
        workManager.getWorkInfosForUniqueWorkLiveData("backup_unique").observeForever(backupObserver);
    }

    // ================== LOGICA UTENTE ==================

    /**
     * Chiamato quando l'utente clicca su un'azione sensibile (Cambio PIN o Backup).
     * Decide se serve Biometria o se procedere diretti.
     */
    public void onSensitiveActionClicked(PendingAction action) {
        // Se stiamo caricando (es. backup in corso), ignora i click
        if (Boolean.TRUE.equals(_isLoading.getValue())) return;

        if (authManager.isBiometricEnabled()) {
            // Chiedi al Fragment di mostrare il prompt
            _authRequest.setValue(new Event<>(action));
        } else {
            // Niente biometria, procedi direttamente (o chiedi PIN vecchio)
            proceedWithAction(action);
        }
    }

    /**
     * Chiamato dal Fragment quando la biometria ha avuto successo (OPPURE se l'auth tramite pin ha avuto successo nel caso "Esporta backup cifrato").
     */
    public void onBiometricSuccess(PendingAction action) {
        proceedWithAction(action);
    }

    /**
     * Chiamato dal Fragment quando la biometria fallisce o viene annullata.
     */
    public void onBiometricError(String error) {
        _statusMessage.setValue(new Event<>("Autenticazione fallita: " + error));
    }

    // Smista l'azione effettiva
    private void proceedWithAction(PendingAction action) {
        _actionToExecute.setValue(new Event<>(action));

        // Questo metodo serve come "semaforo verde".
        // In un MVVM puro, anche la visualizzazione del Dialog dovrebbe essere un Evento,
        // ma per pragmatismo lasciamo che il Fragment gestisca i Dialog di input
        // e chiami il ViewModel per l'esecuzione finale.
    }

    //Auth Fallback - Verifica il PIN tramite AuthManager in background (CASO "Esporta backup cifrato")
    public void verifyAuthPin(String pin) {
        _isLoading.setValue(true); // Feedback visivo immediato
        executor.execute(() -> {
            try {
                // Utilizza la logica centralizzata di core:AuthManager (Hash check + Lockout)
                AuthManager.AuthResult result = authManager.verifyPin(pin);
                _authPinResult.postValue(new Event<>(result));
            } finally {
                _isLoading.postValue(false);
            }
        });
    }

    // ================== OPERAZIONI CRITICHE ==================

    /**
     * Esegue il Re-Wrapping della Master Key.
     * È thread-safe e gestisce lo stato di loading.
     */
    public void changePin(String oldPin, String newPin) {
        if (newPin.length() < 6) {
            _statusMessage.setValue(new Event<>("Il PIN deve essere di almeno 6 cifre"));
            return;
        }

        _isLoading.setValue(true); // UI Block

        executor.execute(() -> {
            try {
                // 1. Verifica preliminare vecchio PIN (Opzionale ma consigliata)
                if (authManager.verifyPin(oldPin) != AuthManager.AuthResult.SUCCESS) {
                    throw new SecurityException("Il vecchio PIN non è corretto.");
                }

                // 2. Esecuzione Re-Wrap (Critico)
                SecurityUtils.reWrapMasterKey(getApplication(), oldPin, newPin);

                // 3. Successo
                _statusMessage.postValue(new Event<>("PIN modificato e Database ricifrato con successo!"));

            } catch (Exception e) {
                e.printStackTrace();
                _statusMessage.postValue(new Event<>("Errore: " + e.getMessage()));
            } finally {
                _isLoading.postValue(false); // UI Unblock
            }
        });
    }

    /**
     * Avvia il Backup tramite WorkManager.
     * Deleghiamo un lavoro la gestione al WorkManager.
     */
    public void triggerBackup(Data inputData) {
//Richiediamo un lavoro da svolgere solo una volta e basta (OneTime…).
        OneTimeWorkRequest backupRequest = new OneTimeWorkRequest.Builder(BackupWorker.class)
                .setInputData(inputData) // Riceve URI e Password dal Fragment
                .addTag("backup_work")//è un’etichetta che serve per sovrascrivere eventuali “backup_work” ancora attivi (ad esempio se l’utente clicca l’opzione più volte)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)// In caso lo smartphone fosse in modalità Doze (risparmio energetico) questo metodo dice al sistema che il lavoro/backup è urgente, e va eseguito quanto prima. Solo che l’urgenza si basa su un sistema di quote/crediti. Se l’app non ha crediti a sufficienza però la policy che passiamo dice di eseguirlo lo stesso come NON_EXPEDITED_WORK)
                .build();

/// NEW: Usiamo enqueueUniqueWork con REPLACE.
        // Questo garantisce che ci sia UN SOLO lavoro attivo con questo nome.
        // Appena chiamato, lo stato diventa ENQUEUED e l'observer sopra blocca la UI.
        workManager.enqueueUniqueWork("backup_unique", ExistingWorkPolicy.REPLACE, backupRequest);

        // Non serve settare _statusMessage qui, l'observer gestirà tutto.

        // Monitoriamo lo stato del Worker per aggiornare la UI?
        // Possiamo farlo qui o lasciare che il Fragment osservi il WorkManager.
        // Per semplicità, notifichiamo l'avvio.
        //_statusMessage.setValue(new Event<>("Backup avviato in background..."));
    }

    // ================== PREFERENZE ==================

    public void setBiometricEnabled(boolean enabled) {
        authManager.setBiometricEnabled(enabled);
    }

    public boolean isBiometricEnabled() {
        return authManager.isBiometricEnabled();
    }

    //Salviamo il nuovo timeout di sessione
    public void setSessionTimeout(long ms) {
        prefsManager.setSessionTimeoutMs(ms);
        // Notifica per aggiornare il timer globale
        SessionObserver.updateTimeout(ms);
        // RESET DEL TIMER: Fondamentale!
        // Poiché l'utente sta interagendo ora. La sessione è attiva
        SessionObserver.resetSessionTimer();
        _statusMessage.setValue(new Event<>("Timeout aggiornato"));
    }

    public long getCurrentTimeout() {
        return prefsManager.getSessionTimeoutMs();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown(); // Pulizia thread
        // NEW: Importante rimuovere l'observerForever per evitare leak se il VM viene distrutto
        workManager.getWorkInfosForUniqueWorkLiveData("backup_unique").removeObserver(backupObserver);
    }
}
