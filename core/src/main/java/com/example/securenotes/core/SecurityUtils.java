package com.example.securenotes.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

/**
 * SecurityUtils (pulito)
 * - Nessuna passphrase DB salvata in chiaro.
 * - Wrapping biometrico (Keystore) → salviamo IV+CT.
 * - Fallback PIN-only (PBKDF2→AES-GCM in software) → salviamo SALT+IV+CT nel seguente modo
 *  // Testo → Base64
 *      * String original = "PIN 🔐";
 *      * byte[] utf8 = original.getBytes(StandardCharsets.UTF_8);   // String → byte[]
 *      * String b64 = Base64.encodeToString(utf8, Base64.NO_WRAP);  // byte[] → String(Base64)
 *      * e recuperiamo il PIN nel seguente..
 *      * // Base64 → Testo
 *      * byte[] back = Base64.decode(b64, Base64.NO_WRAP);          // String(Base64) → byte[]
 *      * String again = new String(back, StandardCharsets.UTF_8);   // byte[] → String
 *      * // again == "PIN 🔐"
 *      *
 * - EncryptedSharedPreferences come storage a riposo.
 */
public final class SecurityUtils {

    private SecurityUtils() { /* no instances */ }

    // ======= EncryptedSharedPreferences =======

    private static final String PREFS_FILE = "secure_notes_prefs";

    public static SharedPreferences getEncryptedPrefs(@NonNull Context ctx) {
        try {
            MasterKey mk = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_FILE,
                    mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallire qui è grave: meglio rendere evidente il problema a crash/telemetria
            throw new IllegalStateException("EncryptedSharedPreferences init failed", e);
        }
    }

    // ======= Chiavi di persistenza =======

    // Metodo attuale di wrapping: "bio" (Keystore+Biometria) | "pin" (fallback software)
    private static final String DB_WRAP_METHOD   = "db_wrap_method";

    // Wrapping biometrico (Keystore AES-GCM): IV + CT
    private static final String DB_WRAP_IV_BIO   = "db_wrap_iv_bio";
    private static final String DB_WRAP_CT_BIO   = "db_wrap_ct_bio";

    // Wrapping PIN-only (software AES-GCM): SALT (PBKDF2) + IV + CT
    private static final String DB_WRAP_SALT_PIN = "db_wrap_salt_pin";
    private static final String DB_WRAP_IV_PIN   = "db_wrap_iv_pin";
    private static final String DB_WRAP_CT_PIN   = "db_wrap_ct_pin";

    // ======= Utilità random / salt =======

    /** Genera n byte crittograficamente casuali. */
    public static byte[] generateRandom(int nBytes) {
        if (nBytes <= 0) throw new IllegalArgumentException("nBytes must be > 0");
        byte[] out = new byte[nBytes];
        new SecureRandom().nextBytes(out);
        return out;
    }

    /** Salt random (16 byte di default). */
    public static byte[] generateSalt() {
        return generateRandom(16);
    }

    // ======= KDF dal PIN (PBKDF2) per fallback software =======

    private static final int PBKDF2_ITER   = 310_000; // robusto per 2025
    private static final int KEY_LEN_BITS  = 256;

    /** Deriva una chiave AES-256 da PIN+salt via PBKDF2(HMAC-SHA-512). */
    public static byte[] kdfKeyFromPin(@NonNull char[] pin, @NonNull byte[] salt)
            throws GeneralSecurityException {
        // 1) Costruisci una “password-based key spec” (PBEKeySpec) cioè un oggetto che specifica i dati di input della funzione di derivazione (della chiave AES):
        //    - pin: password come char[]
        //    - salt: sale casuale (es. 16 byte) salvato insieme ai dati wrappati
        //    - PBKDF2_ITER: numero di iterazioni di applicazione dell'algoritmo (es. 310_000) per rallentare il brute-force
        //    - KEY_LEN_BITS: lunghezza della chiave finale (256 bit per AES-256)
        PBEKeySpec spec = new PBEKeySpec(pin, salt, PBKDF2_ITER, KEY_LEN_BITS);
        try {
            // 2) Chiedi al provider JCE di eseguire PBKDF2(HMAC-SHA512).
            //    generateSecret(spec) esegue i 310k round e produce una chiave binaria.
            byte[] aes_key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                    .generateSecret(spec).getEncoded();
            // 3) Ritorna i 32 byte della chiave derivata (usala per AES-GCM).
            return aes_key;
        } finally {
            // 4) Sicurezza in RAM: pulisce la password interna trattenuta dalla spec.
            //    (NB: NON pulisce l’array 'pin' passato dal chiamante: quello va azzerato fuori.)
            spec.clearPassword();
        }
    }

    /**
     * Calcola l'hash del PIN: PBKDF2(HMAC-SHA512) -> 256 bit.
     * @param pin  PIN come char[] (così la password può essere "wipata" dal KDF)
     * @param salt salt casuale (es. 16 byte) da salvare insieme all'hash
     * @return digest (32 byte)
     */
    public static byte[] hashPin(@NonNull char[] pin, @NonNull byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, PBKDF2_ITER, KEY_LEN_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword(); // pulizia best-effort
        }
    }

    /**
     * Verifica un PIN contro (salt, hash) salvati.
     * Confronto a tempo costante e wipe del digest temporaneo.
     */
    public static boolean verifyPin(@NonNull char[] candidatePin,
                                    @NonNull byte[] salt,
                                    @NonNull byte[] expectedHash)
            throws GeneralSecurityException {
        byte[] h = hashPin(candidatePin, salt);
        try {
            return MessageDigest.isEqual(h, expectedHash); // confronto costante
        } finally {
            java.util.Arrays.fill(h, (byte) 0); // wipe del digest calcolato
        }
    }

    // ======= AES-GCM software (fallback) =======

    private static final String SW_TRANSFORMATION = "AES/GCM/NoPadding";

    /** Cifra plaintext con AES-GCM (software). Ritorna (IV, CT) – il tag è dentro CT. */
    @NonNull
    public static android.util.Pair<byte[], byte[]> aesGcmEncrypt(@NonNull byte[] key, @NonNull byte[] plaintext)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(SW_TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] ct = c.doFinal(plaintext);
        return new android.util.Pair<>(c.getIV(), ct);
    }

    /** Decifra il ciphertext (ct) con AES-GCM (software) usando la stessa IV. */
    @NonNull
    public static byte[] aesGcmDecrypt(@NonNull byte[] key, @NonNull byte[] iv, @NonNull byte[] ct)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(SW_TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }

    // ======= Wrapping BIOMETRICO (Keystore AES-GCM) =======


    /** Salva/aggiorna la busta BIOMETRICA senza rimuovere quella PIN (fallback). */
    public static void saveWrappedDbWithBiometrics(Context ctx, byte[] iv, byte[] ct) throws GeneralSecurityException, IOException {
        SharedPreferences p = getEncryptedPrefs(ctx);
        boolean hasPin = p.getString(DB_WRAP_CT_PIN, null) != null
                && p.getString(DB_WRAP_IV_PIN, null) != null
                && p.getString(DB_WRAP_SALT_PIN, null) != null;
        p.edit()
                .putString(DB_WRAP_IV_BIO, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(DB_WRAP_CT_BIO, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(DB_WRAP_METHOD, hasPin ? "both" : "bio")
                .apply();
    }

    /** Carica IV+CT wrappati con Keystore; null se non presenti. */
    @Nullable
    public static android.util.Pair<byte[], byte[]> loadWrappedDbWithBiometrics(@NonNull Context ctx) {
        SharedPreferences p = getEncryptedPrefs(ctx);
        String iv = p.getString(DB_WRAP_IV_BIO, null);
        String ct = p.getString(DB_WRAP_CT_BIO, null);
        if (iv == null || ct == null) return null;
        return new android.util.Pair<>(
                Base64.decode(iv, Base64.NO_WRAP),
                Base64.decode(ct, Base64.NO_WRAP)
        );
    }

    // ======= Wrapping PIN-only (fallback software AES-GCM) =======

    /** Contenitore semplice per SALT + IV + CT del fallback PIN. */
    public static final class PinWrapData {
        public final byte[] salt;
        public final byte[] iv;
        public final byte[] ct;
        public PinWrapData(byte[] salt, byte[] iv, byte[] ct) {
            this.salt = salt; this.iv = iv; this.ct = ct;
        }
    }


    /** REMINDER per passare dal PIN a stringa in base64 e viceversa:
     * // Testo → Base64
     * String original = "PIN 🔐";
     * byte[] utf8 = original.getBytes(StandardCharsets.UTF_8);   // String → byte[]
     * String b64 = Base64.encodeToString(utf8, Base64.NO_WRAP);  // byte[] → String(Base64)
     *
     * // Base64 → Testo
     * byte[] back = Base64.decode(b64, Base64.NO_WRAP);          // String(Base64) → byte[]
     * String again = new String(back, StandardCharsets.UTF_8);   // byte[] → String
     * // again == "PIN 🔐"
     *
     * Salva/aggiorna la busta PIN senza rimuovere quella BIO (fallback biometrico). */
    public static void saveWrappedDbWithPin(Context ctx, byte[] salt, byte[] iv, byte[] ct) throws GeneralSecurityException, IOException {
        SharedPreferences p = getEncryptedPrefs(ctx);
        boolean hasBio = p.getString(DB_WRAP_CT_BIO, null) != null
                && p.getString(DB_WRAP_IV_BIO, null) != null;
        p.edit()
                .putString(DB_WRAP_SALT_PIN, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(DB_WRAP_IV_PIN, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(DB_WRAP_CT_PIN, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(DB_WRAP_METHOD, hasBio ? "both" : "pin")
                .apply();
    }
    /** Presenza delle buste, per gating BIO→PIN in login/onboarding. */
    public static boolean hasBioWrap(Context ctx) {
        SharedPreferences p = getEncryptedPrefs(ctx);
        return p.getString(DB_WRAP_CT_BIO, null) != null
                && p.getString(DB_WRAP_IV_BIO, null) != null;
    }
    public static boolean hasPinWrap(Context ctx) {
        SharedPreferences p = getEncryptedPrefs(ctx);
        return p.getString(DB_WRAP_CT_PIN, null) != null
                && p.getString(DB_WRAP_IV_PIN, null) != null
                && p.getString(DB_WRAP_SALT_PIN, null) != null;
    }

    /** Carica SALT+IV+CT del fallback PIN; null se non presenti. */
    @Nullable
    public static PinWrapData loadWrappedDbWithPin(@NonNull Context ctx) {
        SharedPreferences p = getEncryptedPrefs(ctx);
        String sSalt = p.getString(DB_WRAP_SALT_PIN, null);
        String sIv   = p.getString(DB_WRAP_IV_PIN,   null);
        String sCt   = p.getString(DB_WRAP_CT_PIN,   null);
        if (sSalt == null || sIv == null || sCt == null) return null;
        return new PinWrapData(
                Base64.decode(sSalt, Base64.NO_WRAP),// String(Base64) → byte[]
                Base64.decode(sIv,   Base64.NO_WRAP),// String(Base64) → byte[]
                Base64.decode(sCt,   Base64.NO_WRAP)// String(Base64) → byte[]
        );
    }

    // ======= Info e manutenzione =======

    /** Ritorna "bio" | "pin" | null (nessun wrapping esistente). */
    @Nullable
    public static String currentWrapMethod(@NonNull Context ctx) {
        return getEncryptedPrefs(ctx).getString(DB_WRAP_METHOD, null);
    }

    /** Cancella tutti i dati di wrapping (utile per reset/migrazioni). */
    public static void clearWrappedDb(@NonNull Context ctx) {
        getEncryptedPrefs(ctx).edit()
                .remove(DB_WRAP_METHOD)
                .remove(DB_WRAP_IV_BIO)
                .remove(DB_WRAP_CT_BIO)
                .remove(DB_WRAP_SALT_PIN)
                .remove(DB_WRAP_IV_PIN)
                .remove(DB_WRAP_CT_PIN)
                .apply();
    }

    /**
     * Azzera in-place un array di byte contenente segreti (best-effort).
     * Utile per ridurre la permanenza della passphrase in RAM dopo l'uso.
     */
    public static void zeroize(@Nullable byte[] data) {
        if (data == null) return;
        java.util.Arrays.fill(data, (byte) 0);
    }
    /** Variante per char[], utile quando gestisci PIN come char[]. */
    public static void zeroize(@Nullable char[] data) {
        if (data == null) return;
        java.util.Arrays.fill(data, '\0');
    }
}
