package com.example.securenotes.core;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

/**
 * Suite di test (strumentali) di sicurezza per verificare la che "Nessun dato sia visibile in SQLite o filesystem".
 * Esegue tentativi di accesso ai dati simulando un attaccante senza chiavi.
 */

/*Spiegazione Tecnica dei Test

verifyDatabaseFileIsNotPlainSQLite: SQLCipher funziona criptando le tabelle del database. I primi 16 byte di un file SQLite contengono una firma fissa.
 SQLCipher sostituisce questa firma con un "Salt" casuale necessario per derivare la chiave di cifratura. Verificando che l'header non sia standard, proviamo che il motore di cifratura è attivo.

verifyStandardSQLiteCannotOpenDatabase: Se usiamo il driver SQLite di sistema (che non conosce password),
 deve considerare il file come "corrotto" o "non valido". Se lo apre, significa che SQLCipher non è stato agganciato correttamente.

verifySharedPreferencesAreNotPlaintext: EncryptedSharedPreferences cifra sia le chiavi che i valori.
 Leggendo il file XML (come farebbe un malware con accesso root), dobbiamo vedere solo stringhe incomprensibili
 e non secret_token o MY_SUPER_SECRET_VALUE.

 NB per evitare che le operazioni di scrittura asincrona del DB rompessero i test ho fatto ricorso al metodo di lettura "sincrona" che già avevo usato per i Backup: getAllNotesSync()*/

@RunWith(AndroidJUnit4.class)// AndroidJUnit4 è un "runner" speciale che sa come installare l'app sul telefono, lanciarla e iniettare i comandi di test.
public class DataAtRestSecurityTest {

    private Context context;
    // Dati di test sensibili
    private static final byte[] FAKE_PASSPHRASE = new byte[32]; // 32 byte di zeri per test
    private static final String SENSITIVE_PREF_KEY = "secret_token";
    private static final String SENSITIVE_PREF_VALUE = "MY_SUPER_SECRET_VALUE";

    @Before //Questo metodo viene eseguito automaticamente prima di ogni singolo test (@Test).
    // È fondamentale per partire da una "tabula rasa" (stato pulito).
    public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();//Ci dà il Context dell'app installata sul telefono con cui accedere ai file, al DB ed EncryptedSharedPreferences reali
        // Generiamo una passphrase fittizia
        Arrays.fill(FAKE_PASSPHRASE, (byte) 1);

        // Pulizia preventiva (caso in cui un test precedente sia crashato senza tearDown)
        context.deleteDatabase("SecureNotes.db");
    }

    @After//Viene eseguito dopo ogni test, anche se il test fallisce o crasha. Serve a cancellare il DB, resettare le variabili in modo che il test successivo non trovi dati vecchi.
    public void tearDown() {
        // Pulizia: chiudiamo l'istanza del DB e cancelliamo i file per non sporcare i test successivi
        AppDatabase.closeInstance();
        context.deleteDatabase("SecureNotes.db");

        // Cancelliamo le preferenze
        File prefsFile = new File(context.getDataDir(), "shared_prefs/secure_notes_prefs.xml");
        if (prefsFile.exists()) {
            prefsFile.delete();
        }
    }

    /**
     * TEST 1: Verifica Cifratura Database (Analisi Header)
     * Bisogna confermare che il file su disco NON è un normale SQLite, leggendo i primi 16 byte del file.
     * Un SQLite normale inizia con "SQLite format 3" (https://sqlite.org/fileformat.html).
     */
    @Test
    public void verifyDatabaseFileIsNotPlainSQLite() {
        // 1. Inizializza in modo asincrono il DB cifrato tramite l'app
        AppDatabase.openWithPassphrase(context, FAKE_PASSPHRASE);

        // Usiamo un metodo SINCRONO per forzare l'attesa della creazione fisica del DB (del metodo precedente) e la cifratura.
        // Usiamo getAllNotesSync() che esegue subito la query..
        AppDatabase.getInstance().noteDao().getAllNotesSync();

        AppDatabase.closeInstance(); // Chiudiamo per rilasciare il lock sul file e assicurarci che i byte siano scritti

        // 2. Accedi al file fisico
        File dbFile = context.getDatabasePath("SecureNotes.db");
        assertThat(dbFile.exists()).isTrue();

        // 3. Leggi l'header (primi 16 byte)
        byte[] header = new byte[16];
        try (FileInputStream fis = new FileInputStream(dbFile)) {
            int read = fis.read(header);
            assertThat(read).isEqualTo(16);
        } catch (IOException e) {
            fail("Impossibile leggere il file database: " + e.getMessage());
        }

        // 4. Asserzione: L'header NON deve essere quello standard di SQLite ("SQLite format 3")
        String headerString = new String(header, StandardCharsets.UTF_8);

        // Debug: Stampiamo cosa abbiamo trovato (utile se il test fallisce)
        System.out.println("Header trovato nel DB: " + headerString);

        // Se è cifrato con SQLCipher, l'header sarà composto da byte random (Salt), quindi diverso dalla stringa standard.
        assertThat(headerString).isNotEqualTo("SQLite format 3\0");
    }

    /**
     * TEST 2: Verifica Cifratura Database (Attacco con API Standard)
     * Provare ad aprire il DB con le API Android standard (android.database.sqlite).
     * Risultato atteso: Fallimento (Eccezione) perché il file è cifrato.
     */
    @Test
    public void verifyStandardSQLiteCannotOpenDatabase() {
        // 1. Crea DB cifrato e forza la scrittura (Sync)
        AppDatabase.openWithPassphrase(context, FAKE_PASSPHRASE);
        AppDatabase.getInstance().noteDao().getAllNotesSync();
        AppDatabase.closeInstance();

        // 2. Tenta di aprirlo come se fosse in chiaro
        File dbPath = context.getDatabasePath("SecureNotes.db");
        try {
            SQLiteDatabase plainDb = SQLiteDatabase.openDatabase(
                    dbPath.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY
            );

            // Se arriviamo qui, siamo riusciti ad aprirlo: GRAVE FALLA DI SICUREZZA
            // Tentiamo una lettura per essere sicuri che non sia solo aperto "vuoto"
            plainDb.getVersion();
            plainDb.close();
            fail("ERRORE: Il database è stato aperto con le API SQLite standard (non cifrato!)");
        } catch (SQLiteException e) {
            // Successo: L'apertura è fallita come previsto
            System.out.println("Test passato: Tentativo di apertura fallito con errore: " + e.getMessage());
        }
    }

    /**
     * TEST 3: Verifica Cifratura SharedPreferences
     * Obiettivo: Scrivere un valore e verificare che nel file XML su disco sia illeggibile.
     */
    @Test
    public void verifySharedPreferencesAreNotPlaintext() throws Exception {
        // 1. Scriviamo un dato usando EncryptedSharedPreferences
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        EncryptedSharedPreferences encryptedPrefs = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                context,
                "secure_notes_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );

        encryptedPrefs.edit().putString(SENSITIVE_PREF_KEY, SENSITIVE_PREF_VALUE).commit();

        // 2. Troviamo il file XML fisico
        File prefsFile = new File(context.getDataDir(), "shared_prefs/secure_notes_prefs.xml");
        assertThat(prefsFile.exists()).isTrue();

        // 3. Leggiamo il contenuto del file come testo puro
        StringBuilder fileContent = new StringBuilder();//NB a differenza delle normali String, StringBuilder è un oggetto che permette di modificare la (zona di memoria della) stringa associata e di allungarla a piacere
        try (BufferedReader br = new BufferedReader(new FileReader(prefsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                fileContent.append(line);
            }
        }

        String rawXml = fileContent.toString();
        System.out.println("Contenuto XML Preference: " + rawXml);

        // 4. Asserzioni di Sicurezza
        // A. La chiave in chiaro NON deve esistere
        assertThat(rawXml).doesNotContain(SENSITIVE_PREF_KEY);
        // B. Il valore in chiaro NON deve esistere
        assertThat(rawXml).doesNotContain(SENSITIVE_PREF_VALUE);

        // C. Verifica che ci sia contenuto cifrato (EncryptedSharedPreferences usa tag <string> con nomi cifrati)
        assertThat(rawXml).contains("string");
    }
}