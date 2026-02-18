package com.example.securenotes.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AuthManager: Single Source of Truth per l'autenticazione.
 * Gestisce verifica PIN, Lockout e stato Biometrico.
 */
public class AuthManager {

    public enum AuthResult { SUCCESS, INCORRECT, LOCKED, ERROR }

    private static volatile AuthManager instance; // Volatile per thread-safety nel double-checked locking
    private final Context context;
    private final PreferenceManager prefsManager;

    // Costanti Lockout
    private static final String KEY_FAILS = "pin_fail_count";
    private static final String KEY_LOCK_UNTIL = "pin_lock_until";
    private static final String KEY_BACKOFF_STEP = "pin_backoff_step";

    // Costanti Keystore
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String BIOMETRIC_KEY_ALIAS = "SecureNotes_BioKey";//Chiave biometrica

    private AuthManager(@NonNull Context context) {
        // Prevenire Activity Leak usando getApplicationContext()
        //if (context == null) throw new IllegalArgumentException("Context cannot be null");
        this.context = context.getApplicationContext();
        this.prefsManager = new PreferenceManager(this.context);
    }

    public static synchronized AuthManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AuthManager.class) {
                if (instance == null) {
                    instance = new AuthManager(context);
                }
            }
        }
        return instance;
    }

    // PIN & LOCKOUT

    public AuthResult verifyPin(String inputPin) {
        try {
            if (isLocked()) return AuthResult.LOCKED;

            byte[] salt = prefsManager.getPinSalt();
            byte[] expectedHash = prefsManager.getPinHash();//in pratica è la chiave AES derivata dal Pin (Key Encryption Key). Possiamo usarla per effettuare l’autenticazione, perché se un utente inserisce un pin diverso, la chiave derivata sarà chiaramente diversa.

            if (salt == null || expectedHash == null) return AuthResult.ERROR;

            boolean isMatch = SecurityUtils.verifyPin(inputPin.toCharArray(), salt, expectedHash);

            if (isMatch) {
                resetFailCount();
                return AuthResult.SUCCESS;
            } else {
                handleFailure();
                return isLocked() ? AuthResult.LOCKED : AuthResult.INCORRECT;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return AuthResult.ERROR;
        }
    }

    private void handleFailure() {
        SharedPreferences prefs = SecurityUtils.getEncryptedPrefs(context);
        long now = System.currentTimeMillis();
        int fails = prefs.getInt(KEY_FAILS, 0) + 1;
        int step = prefs.getInt(KEY_BACKOFF_STEP, 0);

        if (fails >= 5) {
            long[] backoff = {30_000L, 120_000L, 600_000L, 3_600_000L}; // 30s, 2m, 10m, 1h
            long duration = backoff[Math.min(step, backoff.length - 1)];

            //ogni 5 errori azzeriamo il contatore dei fallimenti e aumentiamo il backoff (cioè il tempo in cui l’utente non può più tentare l’autenticazione).
            prefs.edit()
                    .putInt(KEY_FAILS, 0)
                    .putInt(KEY_BACKOFF_STEP, step + 1)//indice del backoff
                    .putLong(KEY_LOCK_UNTIL, now + duration)
                    .apply();
        } else {
            prefs.edit().putInt(KEY_FAILS, fails).apply();
        }
    }

    private void resetFailCount() {
        SecurityUtils.getEncryptedPrefs(context).edit()
                .putInt(KEY_FAILS, 0)
                .putInt(KEY_BACKOFF_STEP, 0)
                .putLong(KEY_LOCK_UNTIL, 0L)
                .apply();
    }

    public long getLockRemainingMillis() {
        try {
            long until = SecurityUtils.getEncryptedPrefs(context).getLong(KEY_LOCK_UNTIL, 0L);
            return Math.max(0L, until - System.currentTimeMillis());
        } catch (Exception e) { return 0L; }
    }

    public boolean isLocked() {
        return getLockRemainingMillis() > 0;
    }

    //BIOMETRIA

    public boolean isBiometricEnabled() {
        return prefsManager.isBiometricEnabled();
    }

    //Richiamato quando si attiva o disattiva il flag biometrico nelle Impostazioni
    public void setBiometricEnabled(boolean enabled) {
        prefsManager.setBiometricEnabled(enabled);
        if (!enabled) deleteBiometricKey();//Principio del minimo privilegio: se l’utente toglie la biometria, eliminiamo la chiave biometrica
    }

    // Torna il Cipher (cifrario/cifratore) per Cifratura (Setup/Wrap)
    public Cipher getBiometricEncryptCipher() throws Exception {
        ensureBiometricKey();//Assicura che esista la chiave biometrica.
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(BIOMETRIC_KEY_ALIAS, null);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c;
    }

    // Torna il Cipher per Decifratura (Accesso)
    public Cipher getBiometricDecryptCipher(byte[] iv) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(BIOMETRIC_KEY_ALIAS, null);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));//128 è la lunghezza del Tag associato (concatenato) alla masterkey cifrata per garantirne l’integrità
        return c;
    }

    // Crea la chiave biometrica (sistema biometrico) con Hardening di sicurezza
    private void ensureBiometricKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        //Se non è presente la chiave biometrica nel keystore, allora ne crea una
        if (!ks.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    BIOMETRIC_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)//con questo set a “true” imponiamo che la chiave venga usata solo in successione alla “prova dell’impronta” (in pratica non può essere usata da un malware in background se l’utente non ha appena toccato il sensore biometrico.
                    // SICUREZZA CRITICA: il successivo set invalida la chiave se viene aggiunto un nuovo dito nel sistema Android.
                    // Protegge contro chi conosce il PIN di sblocco telefono e aggiunge la propria impronta.
                    .setInvalidatedByBiometricEnrollment(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: Richiede autenticazione biometrica "Strong" (Classe 3)
                // 0 secondi = Autenticazione richiesta per OGNI operazione crittografica
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                // Fallback per API < 30
                builder.setUserAuthenticationValidityDurationSeconds(-1);//La chiave biometrica (BIOMETRIC_KEY_ALIAS = "SecureNotes_BioKey") può essere usata solo una volta, al momento dell’autenticazione biometrica, per sbloccare il DB dell’app…nel caso API > 30 è il primo parametro di builder.setUserAuthenticationParameters(0…) -> cioè 0 secondi ti utilizzo, cioè uguale al caso -1.
            }
            kg.init(builder.build());
            kg.generateKey();
        }
    }

    private void deleteBiometricKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            ks.deleteEntry(BIOMETRIC_KEY_ALIAS);
        } catch (Exception e) { e.printStackTrace(); }
    }


    /**
     * Restituisce l'Intent corretto per portare l'utente alle impostazioni di registrazione biometrica
     * in base alla versione di Android.
     */
    public Intent getEnrollmentIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: Apre direttamente la registrazione biometrica
            Intent intent = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
//Specifichiamo che l’unico tipo di enrollment biometrico (attivazione del fingerprint) potrà essere eseguito (è ALLOWED=consentito) solo se tale sistema è di tipo BIOMETRIC_STRONG
            intent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);

            return intent;
        } else {
            // API < 30: Fallback alle impostazioni di sicurezza generiche
            return new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
    }
}
