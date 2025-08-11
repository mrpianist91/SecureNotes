package com.example.securenotes.feature_auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
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
import javax.crypto.spec.IvParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public class BiometricHelper {
    private static final String KEY_NAME = "SecureNotesBiometricKey";
    private static BiometricHelper instance;
    private final BiometricPrompt biometricPrompt;
    private final BiometricPrompt.PromptInfo promptInfo;
    private BiometricAuthListener authListener;
    private final Context appContext;

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
                ContextCompat.getMainExecutor(context),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        BiometricPrompt.CryptoObject cryptoObject = result.getCryptoObject();
                        if (authListener == null) return;
                        try {
                            if (cryptoObject != null && cryptoObject.getCipher() != null) {
                                Cipher cipher = cryptoObject.getCipher();
                                SharedPreferences prefs = getEncryptedPrefs();
                                if (cipher.getAlgorithm().contains("AES")) {
                                    // Se il cipher era in modalità DECRYPT, prova a decrittografare il token salvato
                                    if (cipher.getMode() == Cipher.DECRYPT_MODE) {
                                        String encTokenBase64 = prefs.getString("biometric_token", null);
                                        String ivBase64 = prefs.getString("biometric_iv", null);
                                        byte[] encToken = Base64.decode(encTokenBase64, Base64.NO_WRAP);
                                        byte[] decryptedBytes = cipher.doFinal(encToken);
                                        String decryptedToken = new String(decryptedBytes, StandardCharsets.UTF_8);
                                        if ("SECURENOTES_AUTH".equals(decryptedToken)) {
                                            authListener.onBiometricAuthenticated();
                                        } else {
                                            // Token non valido (potenzialmente manomissione) -> errore
                                            authListener.onBiometricError(BiometricPrompt.ERROR_VENDOR, "Token biometrico non valido");
                                        }
                                    }
                                    // Se il cipher era in modalità ENCRYPT, genera e salva un nuovo token
                                    else {
                                        byte[] tokenBytes = "SECURENOTES_AUTH".getBytes(StandardCharsets.UTF_8);
                                        byte[] encryptedToken = cipher.doFinal(tokenBytes);
                                        // Salva token cifrato e IV per i futuri login
                                        prefs.edit()
                                                .putString("biometric_token", Base64.encodeToString(encryptedToken, Base64.NO_WRAP))
                                                .putString("biometric_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                                                .apply();
                                        authListener.onBiometricAuthenticated();
                                    }
                                } else {
                                    // CryptoObject presente ma non riconosciuto (caso improbabile)
                                    authListener.onBiometricAuthenticated();
                                }
                            } else {
                                // CryptoObject non utilizzato: autenticazione biometrica classica
                                authListener.onBiometricAuthenticated();
                            }
                        } catch (Exception e) {
                            authListener.onBiometricError(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, "Errore crittografia biometrica");
                        }
                    }
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        if (authListener != null) {
                            authListener.onBiometricError(errorCode, errString);
                        }
                    }
                    @Override
                    public void onAuthenticationFailed() {
                        if (authListener != null) {
                            authListener.onBiometricFailed();
                        }
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

    /** Avvia l'autenticazione biometrica con (eventuale) CryptoObject */
    public void authenticate(@NonNull BiometricAuthListener listener) {
        this.authListener = listener;
        try {
            // Prepara il Cipher in modalità appropriata (decrypt se token salvato, altrimenti encrypt)
            Cipher cipher;
            Key key = getOrCreateBiometricKey();
            SharedPreferences prefs = getEncryptedPrefs();
            String encTokenBase64 = prefs.getString("biometric_token", null);
            if (encTokenBase64 != null) {
                // Token già presente: inizializza cipher in modalità DECRYPT con IV salvata
                byte[] iv = Base64.decode(prefs.getString("biometric_iv", ""), Base64.NO_WRAP);
                cipher = getCipher();
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            } else {
                // Nessun token salvato: inizializza cipher in modalità ENCRYPT
                cipher = getCipher();
                cipher.init(Cipher.ENCRYPT_MODE, key);
            }
            BiometricPrompt.CryptoObject cryptoObject = new BiometricPrompt.CryptoObject(cipher);
            biometricPrompt.authenticate(promptInfo, cryptoObject);
        } catch (Exception e) {
            // In caso di errore (chiave non disponibile, ambiente non sicuro, ecc.), effettua fallback senza CryptoObject
            biometricPrompt.authenticate(promptInfo);
        }
    }

    /** Crea (se non esiste) e/o recupera la chiave crittografica per l'autenticazione biometrica */
    private Key getOrCreateBiometricKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        // Se la chiave non esiste ancora, la genera
        if (!keyStore.containsAlias(KEY_NAME)) {
            KeyGenerator keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setUserAuthenticationRequired(true) // richiede autenticazione biometrica per usare la chiave
                    .setInvalidatedByBiometricEnrollment(true) // invalida la chiave se le biometrie cambiano
                    .build();
            keyGen.init(keySpec);
            keyGen.generateKey();
        }
        return keyStore.getKey(KEY_NAME, null);
    }

    /** Restituisce un Cipher AES/CBC/PKCS7 pronto all'uso */
    private Cipher getCipher() throws GeneralSecurityException {
        return Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_CBC + "/"
                + KeyProperties.ENCRYPTION_PADDING_PKCS7);
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
