package com.example.securenotes.feature_auth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
//CLASSE DA ELIMINARE...ASSOLUTAMENTE ININFLUENTE! MORTA!!!!!!!!!
public class BiometricHelper {
    // Stato che il chiamante userà per decidere cosa fare:
// - AVAILABLE: biometria presente+configurata e chiave pronta (o creata ora)
// - NOT_ENROLLED: hardware presente ma utente non ha registrato impronte/volto
// - NO_HARDWARE: nessun supporto biometrico (o errore grave)
    public enum BiometricCapability { AVAILABLE, NOT_ENROLLED, NO_HARDWARE }
    private static final String KEY_NAME = "SecureNotesBiometricKey";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128; // 128-bit tag
    private static BiometricHelper instance;
    private final BiometricPrompt biometricPrompt;
    private final BiometricPrompt.PromptInfo promptInfo;
    private BiometricAuthListener authListener;
    private final Context appContext;
    // Traccia l'operazione crittografica attesa quando si costruisce il CryptoObject.
    private enum Operation { NONE, ENCRYPT, DECRYPT }
    private volatile Operation currentOp = Operation.NONE;

    // Listener per comunicare il risultato al chiamante (LoginFragment)
    public interface BiometricAuthListener {
        void onBiometricAuthenticated();
        void onBiometricError(int errorCode, CharSequence errMsg);
        void onBiometricFailed();
    }

    /** Costruttore privato: inizializza BiometricPrompt e PromptInfo una sola volta */
    private BiometricHelper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        biometricPrompt = new BiometricPrompt((androidx.fragment.app.FragmentActivity) context,
                ContextCompat.getMainExecutor(context), //È un Executor che esegue i task (le callback onAuthentication...) sul thread principale (UI thread) dell’app, ricavato dal context. In pratica equivale a dire: “consegna le callback di BiometricPrompt sul main thread”.
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        final BiometricAuthListener listener = authListener; // snapshot per thread-safety
                        if (listener == null) { currentOp = Operation.NONE; return; }
                        try {
                            BiometricPrompt.CryptoObject co = result.getCryptoObject();
                            if (co != null && co.getCipher() != null) {
                                final Cipher cipher = co.getCipher();
                                final SharedPreferences prefs = getEncryptedPrefs();
                                switch (currentOp) {
                                    case DECRYPT: {
                                        final String encTokenBase64 = prefs.getString("biometric_token", null);
                                        final String ivBase64 = prefs.getString("biometric_iv", null);
                                        if (encTokenBase64 == null || ivBase64 == null) {
                                            // Stato inconsistente: considera come errore di provisioning del token
                                            listener.onBiometricError(BiometricPrompt.ERROR_VENDOR, "Token biometrico mancante o corrotto");
                                            break;
                                        }
                                        final byte[] encToken = Base64.decode(encTokenBase64, Base64.NO_WRAP);
                                        final byte[] plain = cipher.doFinal(encToken);
                                        final String token = new String(plain, StandardCharsets.UTF_8);
                                        if ("SECURENOTES_AUTH".equals(token)) {
                                            listener.onBiometricAuthenticated();
                                        } else {
                                            listener.onBiometricError(BiometricPrompt.ERROR_VENDOR, "Token biometrico non valido");
                                        }
                                        break;
                                    }
                                    case ENCRYPT: {
                                        final byte[] tokenBytes = "SECURENOTES_AUTH".getBytes(StandardCharsets.UTF_8);
                                        final byte[] enc = cipher.doFinal(tokenBytes);
                                        prefs.edit()
                                                .putString("biometric_token", Base64.encodeToString(enc, Base64.NO_WRAP))
                                                .putString("biometric_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                                                .apply();
                                        listener.onBiometricAuthenticated();
                                        break;
                                    }
                                    case NONE:
                                        default:
                                            // CryptoObject presente ma nessuna operazione attesa: considera successo "solo gating"
                                            listener.onBiometricAuthenticated();
                                }
                            } else {
                                // Autenticazione senza CryptoObject: successo "solo gating"
                                listener.onBiometricAuthenticated();
                            }
                        } catch (Exception e) {
                            final BiometricAuthListener l = authListener;
                            if (l != null) l.onBiometricError(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, "Errore crittografia biometrica");
                        } finally {
                            // HARDENING: i callback success/error sono terminali → azzera stato e listener
                            currentOp = Operation.NONE;
                            authListener = null;
                        }

                    }
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {

                        final BiometricAuthListener listener = authListener;
                        if (listener != null) listener.onBiometricError(errorCode, errString);
                        // HARDENING: errore terminale → azzera stato e listener
                        currentOp = Operation.NONE;
                        authListener = null;

                    }
                    @Override
                    public void onAuthenticationFailed() {
                        // Tentativo non terminale: NON azzerare il listener, il prompt resta aperto
                        final BiometricAuthListener listener = authListener;
                        if (listener != null) listener.onBiometricFailed();
                    }
                });
        // Configura le informazioni di prompt (titolo, messaggi, pulsanti)
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Accesso biometrico")
                .setSubtitle("Sblocca SecureNotes")
                .setNegativeButtonText("Usa PIN")
                .build();
    }

    /** Ottiene l'istanza singleton della classe, inizializzandola se necessario */
    public static synchronized BiometricHelper getInstance(@NonNull androidx.fragment.app.FragmentActivity activity) {
        if (instance == null) {
            instance = new BiometricHelper(activity);
        }
        return instance;
    }

    // Verifica stato biometrici e, se enrolled, crea la chiave AES-GCM una volta sola.

    // Crea (se serve) la chiave AES-GCM vincolata all’autenticazione biometrica.
    // Ritorna lo "stato" per guidare il flusso (enrollment forzato, fallback PIN-only, ecc.).
    public static BiometricCapability ensureBiometricKey(@NonNull Context ctx) {
        try {
            // 1) Apri il provider Keystore "AndroidKeyStore" e caricalo.
            //    load(null) è obbligatorio anche se non c’è un file fisico da caricare.
            final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 2a) API 30+ (Android 11+): usa i flag moderni di BiometricManager.
                //     Qui chiediamo BIOMETRIC_STRONG (impronta/volto "robusti").
                BiometricManager bm = BiometricManager.from(ctx);
                int res = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

                // 2b) Hardware ok ma nessun dato biometrico registrato → lo segnaliamo
                //     così il chiamante può FORZARE l'enrollment.
                if (res == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                    return BiometricCapability.NOT_ENROLLED;
                }
                // 2c) Qualsiasi altro esito ≠ SUCCESS (no hardware, sensore assente/rotto, ecc.) → fallback PIN-only.
                if (res != BiometricManager.BIOMETRIC_SUCCESS) {
                    return BiometricCapability.NO_HARDWARE;
                }

                // 3) Se arrivi qui, biometria STRONG disponibile e già configurata.
                //    Verifica se la chiave esiste già: se sì, NON fare nulla (idempotente).
                if (!ks.containsAlias(KEY_NAME)) {
                    // 4) Prepara un generatore di chiavi AES nel provider del Keystore.
                    KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

                    // 5) Specifica di generazione:
                    //    - AES/GCM/NoPadding (AEAD: confidenzialità + integrità via tag GCM)
                    //    - RandomizedEncryptionRequired: impone IV casuale ad ogni encrypt (corretto per GCM)
                    //    - InvalidatedByBiometricEnrollment: se l'utente AGGIUNGE un nuovo dato biometrico,
                    //      la chiave diventa inutilizzabile (costringeremo al re-provisioning).
                    //    - setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG):
                    //      "0 secondi" = PER-OPERAZIONE (niente finestra di validità), SOLO biometria strong.
                    KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                            KEY_NAME,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .setInvalidatedByBiometricEnrollment(true)
                            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                            .build();

                    // 6) Genera la chiave dentro al Keystore (non esce mai in chiaro).
                    kg.init(spec);
                    kg.generateKey();
                }

                // 7) Tutto ok: biometria pronta e chiave presente.
                return BiometricCapability.AVAILABLE;

            } else {
                // 2a') API 26–29: c’è solo la canAuthenticate() "vecchia" (senza flag).
                //      Su queste versioni SUCCESS implica hardware presente e enrollment già fatto.
                BiometricManager bm = BiometricManager.from(ctx);
                int res = bm.canAuthenticate(); // deprecata in 30+, corretta qui
                if (res == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                    return BiometricCapability.NOT_ENROLLED;
                }
                if (res != BiometricManager.BIOMETRIC_SUCCESS) {
                    return BiometricCapability.NO_HARDWARE;
                }

                // 3') Idem come sopra: crea la chiave se manca (idempotente).
                if (!ks.containsAlias(KEY_NAME)) {
                    KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

                    // Nota: su API <30 non esistono i "tipi" di autenticatore: possiamo solo
                    // richiedere genericamente che l'utente sia autenticato (biometria o credenziale).
                    // La tua policy non usa DEVICE_CREDENTIAL nel prompt, ma a livello Keystore
                    // qui specifichiamo il requisito generico (è il massimo disponibile su queste API).
                    KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                            KEY_NAME,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .setInvalidatedByBiometricEnrollment(true)
                            .setUserAuthenticationRequired(true) // equivalente "generico" (<30)
                            .build();

                    kg.init(spec);
                    kg.generateKey();
                }

                return BiometricCapability.AVAILABLE;
            }

        } catch (Exception e) {
            // 8) Qualsiasi eccezione (provider non disponibile, KeyStore corrotto, ecc.)
            //    la traduciamo in NO_HARDWARE: il chiamante può ricadere sul PIN-only.
            Log.w("BiometricHelper", "ensureBiometricKey failed", e);
            return BiometricCapability.NO_HARDWARE;
        }
    }


    /** Intent per forzare l'enrollment biometrico (impronta/volto) */
    public static Intent enrollmentIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            // ACTION_BIOMETRIC_ENROLL apre direttamente la schermata di enroll biometrico.
            Intent i = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
            // EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED “consiglia” quali fattori abilitare:
            // qui chiediamo BIOMETRIC_STRONG (coerente con la policy: niente device_credential).
            i.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG);
            return i;
        }
        // Sotto API 30 non c’è l’Intent specifico: apriamo le impostazioni di sicurezza generiche.
        // L’utente dovrà entrare nella sezione impronte/volto dal menu.
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    /** Cipher per cifrare: IV generato automaticamente; lo leggerai con cipher.getIV() DOPO il prompt */
    public static Cipher getEncryptCipher() throws GeneralSecurityException, IOException {
        // 1) Apri Keystore e caricalo
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        // 2) Recupera la chiave simmetrica AES generata da ensureBiometricKey()
        SecretKey key = (SecretKey) ks.getKey(KEY_NAME, null);

        // 3) Istanzia un Cipher AES/GCM senza padding (AEAD: confidenzialità + integrità via tag)
        Cipher c = Cipher.getInstance(TRANSFORMATION);

        // 4) Inizializza in ENCRYPT_MODE: il provider genera un IV casuale *per questa operazione*
        //    (richiesto da GCM). Questo Cipher va poi passato nel CryptoObject del BiometricPrompt.
        c.init(Cipher.ENCRYPT_MODE, key);

        return c;
    }

    /** Cipher per decifrare: devi passare lo stesso IV usato in encrypt */
    public static Cipher getDecryptCipher(byte[] iv) throws GeneralSecurityException, IOException {
        // 1) Keystore → load
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        // 2) Recupera la stessa chiave AES
        SecretKey key = (SecretKey) ks.getKey(KEY_NAME, null);

        // 3) Istanzia il Cipher AES/GCM
        Cipher c = Cipher.getInstance(TRANSFORMATION);

        // 4) Prepara i parametri GCM con lo stesso IV e tag length (128 bit tipico)
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

        // 5) Inizializza in DECRYPT_MODE: questo Cipher, passato al BiometricPrompt,
        //    verrà "sbloccato" solo DOPO l'autenticazione (gating per operazione).
        c.init(Cipher.DECRYPT_MODE, key, spec);
        return c;
    }

    /** Avvia l'autenticazione biometrica usando i Cipher moderni (AES/GCM) */
    public void authenticate(@NonNull BiometricAuthListener listener) {
        this.authListener = listener;
        try {
            // 1) Garantisce la chiave e verifica capability
            final BiometricCapability cap = ensureBiometricKey(appContext);
            final SharedPreferences prefs = getEncryptedPrefs();
            if (cap != BiometricCapability.AVAILABLE) {
                currentOp = Operation.NONE;
                biometricPrompt.authenticate(promptInfo); // gating senza CryptoObject
                return;
            }
            // 2) Sceglie il Cipher: DECRYPT se esiste un token, altrimenti ENCRYPT
            BiometricPrompt.CryptoObject cryptoObject;
            String encTokenBase64 = prefs.getString("biometric_token", null);
            if (encTokenBase64 != null) {
                // DECRYPT: usa lo stesso IV salvato a provisioning
                String ivBase64 = prefs.getString("biometric_iv", null);
                if (ivBase64 == null) {
                    // Stato inconsistente: forziamo un nuovo provisioning
                    currentOp = Operation.ENCRYPT;
                    cryptoObject = new BiometricPrompt.CryptoObject(getEncryptCipher());
                    } else {
                    currentOp = Operation.DECRYPT;
                    byte[] iv = Base64.decode(ivBase64, Base64.NO_WRAP);
                    cryptoObject = new BiometricPrompt.CryptoObject(getDecryptCipher(iv));
                }
            } else {
                // ENCRYPT: primo run → crea token
                currentOp = Operation.ENCRYPT;
                cryptoObject = new BiometricPrompt.CryptoObject(getEncryptCipher());
            }
            // 3) Prompt con CryptoObject (uso del Cipher consentito solo dopo autenticazione)
            biometricPrompt.authenticate(promptInfo, cryptoObject);
        } catch (Exception e) {
            // Fallback "soft": prompt senza CryptoObject
            currentOp = Operation.NONE;
            biometricPrompt.authenticate(promptInfo);
        }
    }


    /** Restituisce SharedPreferences cifrate per memorizzare PIN/token in sicurezza */
    private SharedPreferences getEncryptedPrefs() throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                appContext,
                "secure_notes_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }
}
