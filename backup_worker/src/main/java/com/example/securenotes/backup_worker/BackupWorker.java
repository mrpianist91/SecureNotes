package com.example.securenotes.backup_worker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.securenotes.backup_worker.R;

import com.example.securenotes.core.AppDatabase;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.SecurityUtils;
import com.example.securenotes.core.VaultFile;
import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


//il Worker è la classe base di Jetpack WorkManager. Incapsula una singola unità di lavoro differibile ma garantito (anche se l’app viene arrestata). Il metodo specifico è il doWork() e i valori di ritorno sono Result.success(), .failure(), .retry()


public class BackupWorker extends Worker {


    //Serve per fare il logging (Log.e, Log.d)
    private static final String TAG = "BackupWorker";

    //KEY_URI e PASSWORD servono per relazionare questo BackupWorker al SettingsFragment
    //Infatti verranno richiamate (sono static!) nel SettingsFragment per associare il percorso del file e la password scelti dall’utente alle etichette “backup_uri” e “backup_password”. Servono per scambiare informazioni in modo comodo.
    public static final String KEY_URI = "backup_uri";//etichetta del path del backup scelto dall’utente
    public static final String KEY_PASSWORD = "backup_password";//etichetta della pass scelta dall’utente

    //Da Android 8 (API 26) le notifiche devono appartenere ad un canale per poter permettere all’utente di disabilitare quelle di un canale piuttosto che di un altro
    private static final String CHANNEL_ID = "backup_channel";

    //Un intero univoco che identifica la notifica “attiva”
    private static final int NOTIFICATION_ID = 1;

    // Costanti Header Backup (V1) necessari per Ripristino backup
    //Se i byte non corrispondono a SNBACKUP_V1, l'app rifiuterà il file con un errore "File non valido o corrotto". NB V1 sta per (Versione 1) per mantenere la retrocompatibilità in caso di modifiche
    private static final byte[] MAGIC_HEADER = "SNBACKUP_V1".getBytes(StandardCharsets.UTF_8);
    //32 byte = 256 bit è lo standard consigliato da OWASP
    private static final int SALT_SIZE = 32;
    //In AES-GCM,  l’IV a 16 byte comporta un calo di prestazioni. NIST raccomanda 12 byte ed è il raccomandato per AES-GCM..
    private static final int IV_SIZE = 12;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /*CRUCIALE PER RETROCOMPATIBILITÀ DI setExpedited(). Su Android < 12, WorkManager chiama questo metodo per avviare un Foreground Service come fallback. Se manca, l'app crasha con "Worker failed to provide ForegroundInfo". */
    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() { return createForegroundInfo(); }

    //Metodo principale del Worker, chiamato su un thread secondario
    @NonNull
    @Override
    public Result doWork() {
        // 1. Recupero contesto e input
        Context context = getApplicationContext();//ci serve per accedere al DB, ai file su disco e per aprire lo stream verso l’URI di destinazione tramite ContentResolver.
        // Estrae i dati che il SettingsFragment ha "impacchettato" dentro l'oggetto Data  quando ha creato la richiesta (OneTimeWorkRequest).
        String uriString = getInputData().getString(KEY_URI);
        String password = getInputData().getString(KEY_PASSWORD);

        if (uriString == null || password == null) return Result.failure();

        // Segnala al sistema che stiamo lavorando in Foreground (Obbligatorio per task lunghi/dati). Il metodo mostra la Notifica persistente "Backup in corso" nella barra di stato.
        // Chiama internamente getForegroundInfo()
        setForegroundAsync(createForegroundInfo());

        try {
            Uri destUri = Uri.parse(uriString);
            performBackup(context, destUri, password);//qui avviene la logica di cifratura, zip, SQLCipher…se finisce senza eccezioni allora il backup è andato a buon fine
            return Result.success();
        } catch (Exception e) {//se avviene qualsiasi problema durante il backup
            Log.e(TAG, "Backup fallito", e);
            return Result.failure(new Data.Builder().putString("error", e.getMessage()).build());
        }
    }

    private void performBackup(Context context, Uri destUri, String password) throws Exception {
        // 1. Preparazione Crittografia Portabile
        // Genera Salt casuale per questo backup. Ogni backup deve avere un salt diverso.
        byte[] salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(salt);

        // Deriva la chiave (AES a 256 bit) dalla password utente (PBKDF2)
        // Usiamo SecurityUtils per coerenza, ma adattiamo i parametri se necessario
        // Nota: SecurityUtils usa 600k iterazioni, ottimo per backup.
        SecretKey backupKey = SecurityUtils.deriveKey(password.toCharArray(), salt);


        // Genera IV per AES-GCM. L’IV garantisce che ogni cifratura sia unica.
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);

        // Setup Cipher (cifrario/cifratore).
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, backupKey, new GCMParameterSpec(128, iv));//128 è la lunghezza del MAC (TAG di autenticazione) che viene aggiunto al backup cifrato per garantirne l’integrità (128 è il massimo e più sicuro)

        // 2. Apertura Stream di Destinazione
        //NB “try () {}” è un try-with-resources. Significa che alla fine del blocco (o se c'è un errore), Java chiuderà automaticamente il file. Fondamentale per non corrompere i dati.
        try (OutputStream fileOut = context.getContentResolver().openOutputStream(destUri)) {
            if (fileOut == null) throw new IllegalArgumentException("Impossibile scrivere sull'URI");

            // SCRITTURA HEADER (IN CHIARO)
            // Formato: [MAGIC] [SALT] [IV] [CIPHER_STREAM...]
            fileOut.write(MAGIC_HEADER);
            fileOut.write(salt);
            fileOut.write(iv);
            //INIZIO CIFRATURA

            /*Struttura a "Matrioska":
ZipOutputStream (zos): È l'imbuto superiore.
Tu gli dai dei file (note, JSON, PDF).
Lui li comprime e li trasforma in byte compressi.
Questi byte vanno dentro cos.
CipherOutputStream (cos): È il tunnel crittografico.
Riceve i byte compressi dallo Zip.
Li cifra usando AES-GCM.
Questi byte vanno dentro fileOut.
OutputStream (fileOut): È il rubinetto finale.
Scrive i byte cifrati fisicamente sul disco del telefono.
Poiché lo ZipOutputStream è dentro il CipherOutputStream, tutto ciò che riguarda lo Zip (inclusi i nomi dei file) viene cifrato. Un attaccante vedrà solo un unico blob di dati illeggibile.
*/
            try (CipherOutputStream cos = new CipherOutputStream(fileOut, cipher);
                 ZipOutputStream zos = new ZipOutputStream(cos)) {

                // A. Backup Note (JSON). Interroga il DB, crea una stringa JSON (notes.json) e la pusha dentro zos
                backupNotes(zos);

                // B. Backup Vault (File Decifrati e Ricifrati)...Prende i file cifrati dal disco, li decifra (con la chiave del telefono) e li pusha dentro zos.
                backupVault(context, zos);
            }//Qui si chiudono automaticamente zos, cos e fileOut. NB “cos” calcola il MAC (Authentication Tag) finale di GCM per garantire che il file non sia stato manomesso e lo accoda.
        }
    }


    /*Estraiamo i dati strutturati (le Note) dal Database SQL per trasformarli in un formato portabile (JSON) da iniettare nel flusso cifrato.*/
    private void backupNotes(ZipOutputStream zos) throws Exception {

        // Query diretta (sincrona) sul DB (Room consente accesso sincrono da Worker thread)
        // Non possiamo usare getAllNotes.getValue() che torna un LiveData perchè è main-thread only. Qui siamo in background. Bisogna quindi aggiungere il metodo .getAllNotesSync()
        List<Note> notes = AppDatabase.getInstance().noteDao().getAllNotesSync();

        // Serializzazione JSON con GSON
        Gson gson = new Gson();
        String json = gson.toJson(notes);

        //Creazione “Etichetta” (nome) nel file Zip
        ZipEntry entry = new ZipEntry("notes.json");
        //Creazione dell’entry (istanza)
        zos.putNextEntry(entry);
        //Scrittura dei byte (coversione json->bytes, compressione e cifratura)
        zos.write(json.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void backupVault(Context context, ZipOutputStream zos) throws Exception {
        // Recupera file dal DB (Sincrono). Guarda spiegone su getAllNotesSync() al metodo sopra, e similmente è per getAllFilesSync().
        List<VaultFile> files = AppDatabase.getInstance().vaultDao().getAllFilesSync();
        // 2. Loop sui file
        for (VaultFile vf : files) {
            File localFile = new File(vf.path);

            // Controllo esistenza fisica (nel caso fosse stato cancellato dal disco per qualsiasi motivo)
            if (!localFile.exists()) continue;

            try {
                // 3. Configurazione Decifratura Locale (similmente a VaultRepositoryImpl)
                MasterKey mainKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                EncryptedFile ef = new EncryptedFile.Builder(
                        context,
                        localFile,
                        mainKey,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build();

                // 4. Creazione Entry nello Zip
                // Usiamo “ID + Nome originale” per garantire unicità e leggibilità al ripristino. Se due file hanno nome uguale non si crea problema grazie all’ID.
                String entryName = "vault/" + vf.id + "_" + vf.name;
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);

                // 5. Streaming: Decrypt (Locale) -> Buffer -> Encrypt (Backup) -> il CipherOutputStream scrive sul file finale .snbackup (come stabilito nella callback di pressione del btn_backup. GUARDA IN FONDO)
                try (InputStream is = ef.openFileInput()) {//.openFileInput apre (decifrandolo) uno stream dal file
                    byte[] buffer = new byte[4096]; // 4KB Buffer
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        // Scrittura nello Zip (che è avvolto nel CipherOutputStream)
                        zos.write(buffer, 0, bytesRead);
                    }
                }

                // 6. Chiusura Entry
                zos.closeEntry();

            } catch (Exception e) {
                // Logghiamo l'errore ma NON interrompiamo il backup per un solo file corrotto
                Log.e("BackupWorker", "Impossibile backuppare il file: " + vf.name, e);
            }
        }
    }

    //Questo metodo torna la ForegroundInfo che permette al Worker di sopravvivere in background e di attivare una notifica persistente.
    @NonNull
    private ForegroundInfo createForegroundInfo() {
        String title = "Backup in corso";//titolo della notifica

        // 1. Creiamo e registriamo il canale di notifica (Safe su API 26+)
        createNotificationChannel();


        // 2. Costruiamo la notifica (e il suo oggetto UI con NotificationCompat)
        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText("Cifratura ed esportazione dati...")
                .setSmallIcon(R.drawable.ic_save_24)//NON DIMENTICARE DI CREARLA
                .setOngoing(true)//rende la notifica non eliminabile con lo swipe
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)//dice al sistema di mostrare subito la notifica (di norma si deve attendere qualche secondo).
                .build();//valido da Android 12+.

        // Distinguiamo il tipo di servizio tra le varie API
        if (Build.VERSION.SDK_INT >= 29) {
// API 29+ (Android 10, 11, 12, 13, 14): La costante DATA_SYNC esiste.
            // Su Android 14 è obbligatoria, su 10-13 è best practice.
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        else {
// API 26-28 (Android 8.0 - 9.0): I tipi di servizio non esistevano/non erano gestiti.
// Usiamo il costruttore base.
            return new ForegroundInfo(NOTIFICATION_ID, notification);
        }
    }


    //Serve a registrare presso il sistema operativo la "categoria" delle notifiche.
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { //O=Oreo -> API 26+
            //Configurazione del canale/channel
            //NB “Backup Services” è l’etichetta che l’utente vedrà nella sezione Notifiche delle Impostazioni del telefono di SecureNotes. Qui potrebbe scegliere anche di disattivare il canale.
//RICORDA NotificationManager.IMPORTANCE_HIGH: La notifica suona e vibra.
//IMPORTANCE_DEFAULT: La notifica suona ma non vibra (dipende dalle impostazioni).
//IMPORTANCE_LOW: La notifica appare nella barra di stato e nel menu a tendina, ma non emette suoni, non vibra e non illumina lo schermo.

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Backup Services", NotificationManager.IMPORTANCE_LOW);
            /*Chiede al contesto globale dell'app (getApplicationContext()) di fornirci il riferimento al NotificationManager, che gestisce le notifiche di tutto il telefono.*/
            NotificationManager manager = getApplicationContext().getSystemService(NotificationManager.class);
            if (manager != null) {
                //NB .createNotificationChannel() crea il canale solo la prima volta, le altre non farà nulla (ignora la chiamata)
                manager.createNotificationChannel(channel);//Creazione effettiva del canale
            }
        }
    }
}
