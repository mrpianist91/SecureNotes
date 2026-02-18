package com.example.securenotes.feature_vault;

import android.app.Application;
import android.net.Uri;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.securenotes.core.*;
import java.util.List;

public class VaultViewModel extends AndroidViewModel {//NB A differenza di un normale ViewModel, questo (AndroidViewModel) ci dà accesso al metodo getApplication(). Ci serve perché il nostro Repository ha bisogno del Context per accedere al Database, ai File e alle SharedPreference (Keystore).
    private final VaultRepository repository;
    public final LiveData<List<VaultFile>> files; //Lista (pubblica) dei files in archivio, aggiornata automaticamente da Room.

    // STATO DEL GATEKEEPER
    // Manteniamo lo stato qui perché il ViewModel sopravvive alla rotazione,
    // mentre il Fragment no.
    private boolean isUnlocked = false;

    // STATO DEL "CARICAMENTO" DI UN FILE (DA AGGIUNGERE ALLA LISTA DEI FILES ARCHIVIATI)
    // _isLoading è privato ma modificabile: solo il ViewModel può cambiarlo.
    private final MutableLiveData<Boolean> _isLoading = new MutableLiveData<>(false);
    public LiveData<Boolean> isLoading = _isLoading; // isLoading è pubblico e immutabile: la UI può solo guardarlo.

    // EVENTO NAVIGAZIONE/APERTURA FILE (Single Live Event)
    // Contiene l'URI del file decifrato da aprire.
    private final MutableLiveData<Uri> _viewFileEvent = new MutableLiveData<>(); //del ViewModel
    // per la sola osservazione da parte della UI...sta per "il file è stato decifrato temporaneamente ed è pronto per essere visualizzato"
    public LiveData<Uri> viewFileEvent = _viewFileEvent;

    // Gestore PIN condiviso da :core
    //private final PinManager pinManager;
    private final AuthManager authManager;
    // LiveData per comunicare il risultato del PIN alla UI
    private final MutableLiveData<AuthManager.AuthResult> _pinResult = new MutableLiveData<>();
    public LiveData<AuthManager.AuthResult> pinResult = _pinResult;

    public VaultViewModel(@NonNull Application application) {
        super(application);
        // 1. Otteniamo l'istanza del DAO del Database Singleton
        VaultDao dao = AppDatabase.getInstance().vaultDao();
        // 2. Creiamo il Repository passandogli il DAO
        repository = new VaultRepositoryImpl(dao);
        // 3. Colleghiamo la lista file direttamente a Room
        files = repository.getAllFiles();// appena cambia qualcosa nel DB, questa lista si aggiorna automaticamente e la UI riceve la notifica.
        // Inizializza l'AuthManager
        this.authManager = AuthManager.getInstance(application);
    }

    public boolean isUnlocked() {
        return isUnlocked;
    }

    public void setUnlocked(boolean unlocked) {
        this.isUnlocked = unlocked;
    }

    // Verifica PIN
    public void verifyPin(String pin) {
        // Eseguiamo in un thread background se il calcolo hash è pesante (in PinManager)
        new Thread(() -> {
            AuthManager.AuthResult result = authManager.verifyPin(pin);
            // Post sul main thread
            _pinResult.postValue(result);
        }).start();
    }

    // Reset del risultato per evitare trigger multipli
    public void resetPinResult() {
        _pinResult.setValue(null);
    }

    //Aggiungiamo un nuovo file all'archivio
    public void importFile(Uri uri) {
        // 1. "stiamo caricando un file" -> mostra la ProgressBar
        _isLoading.setValue(true);
        //Usa la callback per resettare isLoading SOLO quando il lavoro è finito
        repository.addFileFromUri(getApplication(), uri, new VaultRepository.CompletionListener() {
            @Override
            public void onComplete() {
                // 3. Successo: Nascondi la ProgressBar
                _isLoading.setValue(false);
            }

            @Override
            public void onError(Exception e) {
                // 4. Errore: Nascondi ProgressBar e notifica utente
                _isLoading.setValue(false);
                Toast.makeText(getApplication(), "Errore importazione: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Chiedi al Repo di decifrare il file nella cache temporanea (per poterlo visualizzare)
    // Questo metodo è passato come callback dal VaultFragment nel setup dell'onItemClick() del RecyclerView/VaultAdapter
    public void requestOpenFile(VaultFile file) {
        // 1. UI Feedback: tramite _isLoading mostriamo la ProgressBar (la decifratura può impiegare secondi)
        _isLoading.setValue(true);
        repository.decryptFileForViewing(getApplication(), file, new VaultRepository.OnFileDecryptedListener() {
            @Override
            public void onDecrypted(Uri fileUri) {
                // 3. Successo: Nascondi loader e prepara l'evento (apertura file)
                _isLoading.setValue(false);
                _viewFileEvent.setValue(fileUri);//questo LiveData verrà osservato dal VaultFragment che gestirà il lancio dell'Intent verso l'app che aprirà effettivamente il file
            }

            @Override
            public void onError(Exception e) {
                _isLoading.setValue(false);
                Toast.makeText(getApplication(), "Errore apertura: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void deleteFile(VaultFile file) {
        repository.deleteFile(file); //Room aggiornerà la lista 'files' automaticamente appena il record viene cancellato.
    }

    public void onFileViewed() {
        // nel momento in cui il file è stato visualizzato, resettiamo il LiveData in modo da assicurarci che il file non venga riaperto automaticamente in seguito ad un cambio di stato dell'UI (es. rotazione schermo)
        _viewFileEvent.setValue(null);
    }

    @Override
    protected void onCleared() {
        // SECURITY: Pulizia aggressiva della cache quando si esce dal Vault.
        // Se l'utente preme "Indietro" o l'app viene chiusa, questo metodo parte.
        //onCleared() viene chiamato dal sistema Android quando il ViewModel sta per morire definitivamente.
        super.onCleared();
        VaultRepository.clearTempCache(getApplication());
    }
}