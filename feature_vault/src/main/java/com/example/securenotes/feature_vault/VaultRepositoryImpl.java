package com.example.securenotes.feature_vault;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.core.content.FileProvider;
import androidx.lifecycle.LiveData;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import com.example.securenotes.core.VaultDao;
import com.example.securenotes.core.VaultFile;
import com.example.securenotes.core.VaultRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VaultRepositoryImpl implements VaultRepository {
    private final VaultDao vaultDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VaultRepositoryImpl(VaultDao dao) {
        this.vaultDao = dao;
    }

    @Override
    public LiveData<List<VaultFile>> getAllFiles() {
        return vaultDao.getAllFiles();
    }

    @Override
    public void addFileFromUri(Context context, Uri sourceUri, CompletionListener listener) {
        executor.execute(() -> {
            try {
                //Logica di recupero nome file e cifratura...

                // 1. Recupera metadati (nome)...
                // Su Android, un'app non può accedere direttamente ai file di un'altra app (es. Google Drive o Foto) usando un percorso classico come C:/Documenti/foto.jpg. Invece, usa un URI (es. content://com.google.android.apps.photos.contentprovider/...). Tu non chiedi al file system, chiedi al "ContentResolver": "Risolvi questo indirizzo per me e dammi uno stream di dati". Lui contatta l'app proprietaria del file, verifica i permessi e ti apre il "rubinetto" (InputStream)
                //Quando fai una query() al ContentResolver per avere info su un file, lui non ti ridà un oggetto "File", ma ti ridà un Cursor. Il Cursor è letteralmente un puntatore che scorre sopra le righe di una tabella virtuale dei risultati (in Android qualsiasi fonte di dati viene trattata come se fosse un DB relazionale).
                String fileName = "unknown_file";//stringa di destinazione
                try (Cursor cursor = context.getContentResolver().query(sourceUri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {//il Cursore è inizialmente posizionato prima della prima riga (indice -1). Dobbiamo dire moveToFirst() per dirgli: "Spostati sulla riga 0"
                        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);//OpenableColumns.DISPLAY_NAME è una costante standard di Android che contiene il nome della colonna (una stringa, solitamente _display_name) dove il Content Provider memorizza il nome del file
                        if(index >= 0) fileName = cursor.getString(index);
                    }
                }

                // 2. Setup destinazione cifrata
                String fileId = UUID.randomUUID().toString();
                //Creiamo (o carichiamo) la cartella di destinazione se non esiste
                File secureDir = new File(context.getFilesDir(), "secure_vault");
                if (!secureDir.exists()) secureDir.mkdirs();
                //Creiamo il file di destinazione
                File destFile = new File(secureDir, fileId);

                // Setup MasterKey (AES256_GCM)
                MasterKey mainKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                // Setup EncryptedFile
                EncryptedFile encryptedFile = new EncryptedFile.Builder(
                        context, destFile, mainKey,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB //HKDF (HMAC-based Key Derivation Function) è un metodo per "derivare" chiavi. Invece di usare la MasterKey grezza per cifrare tutto il file, la libreria usa la MasterKey per generare delle sotto-chiavi specifiche.
                ).build();

                // 3. Copia e Cifra (Streaming)
                try (InputStream is = context.getContentResolver().openInputStream(sourceUri);
                     OutputStream os = encryptedFile.openFileOutput()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                // 4. Salva Metadati in DB
                VaultFile vf = new VaultFile();
                vf.id = fileId;
                vf.name = fileName;
                vf.path = destFile.getAbsolutePath();
                vf.addedAt = System.currentTimeMillis();
                vf.mimeType = context.getContentResolver().getType(sourceUri);

                vaultDao.insertVaultFile(vf);//INSERT

                // FIX: Notifica completamento su Main Thread
                if (listener != null) mainHandler.post(listener::onComplete);

            } catch (Exception e) {
                if (listener != null) mainHandler.post(() -> listener.onError(e));
            }
        });
    }

    // Assicurati che deleteFile usi lo stesso pattern se vuoi gestire il caricamento lì
    @Override
    public void deleteFile(VaultFile file) {
        executor.execute(() -> {
            try {
                File f = new File(file.path);
                if (f.exists()) f.delete();
                vaultDao.deleteById(file.id);
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    //Prepariamo una versione leggibile del file cifrato in cache (cartella) temporanea
    @Override
    public void decryptFileForViewing(Context context, VaultFile file, OnFileDecryptedListener listener) {

        executor.execute(() -> {
            try {
                // 1. Prepara cartella cache temporanea (verrà pulita al logout/exit)
                File cacheDir = new File(context.getCacheDir(), "vault_temp");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                // Usiamo il nome originale per aiutare le app esterne a riconoscere il tipo
                File tempFile = new File(cacheDir, file.name);

                // 2. Configura Decrittazione
                // NB Il metodo .build() recupera dall'Android Keystore la chiave creata in precedenza nel medesimo context
                MasterKey mainKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                EncryptedFile encryptedFile = new EncryptedFile.Builder(
                        context, new File(file.path), mainKey,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build();

                // 3. Decifra in Cache (Cleartext temporaneo)
                try (InputStream is = encryptedFile.openFileInput();
                     OutputStream os = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                }

                // 4. Genera URI (speciale) sicuro tramite FileProvider...NB Non passiamo all'app esterna il percorso fisico (/data/user/0/.../vault_temp/file.pdf). Le app moderne non hanno il permesso di leggere i file privati le une delle altre.
                // Il provider authority (secondo argomento di .getUriForFile()) deve corrispondere a quello nel Manifest...è il nome del dominio del provider (es. com.example.securenotes.provider) Quando passi l'URI a un'altra app (es. il PDF Viewer), l'URI sarà: content://com.example.securenotes.provider/vault_temp/miofile.pdf.
                // Quando il PDF Viewer prova ad aprirlo, il sistema Android controlla l'authority (com.example.securenotes.provider), capisce che appartiene alla TUA app, e verifica se tu hai concesso il permesso (FLAG_GRANT_READ_URI_PERMISSION). Se l'authority non combacia tra codice e Manifest, il sistema non trova il "garante" del file e lancia un crash (SecurityException).
                Uri uri = /*androidx.core.content.*/FileProvider.getUriForFile(context, context.getPackageName() + ".provider", tempFile); // Permetti all'app che riceve questo Intent (es. il PDF Viewer) di leggere questo specifico "tempfile", ma solo per questa volta

                // Callback su Main Thread
                mainHandler.post(() -> listener.onDecrypted(uri));

            } catch (Exception e) {
                mainHandler.post(() -> listener.onError(e));
            }
        });
    }
}