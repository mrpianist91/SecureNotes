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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityUtils {

    private static final String PREFS_FILE = "secure_notes_prefs";

    // Costanti SharedPreferences (Allineate con AuthViewModel/AuthManager)
    public static final String KEY_PIN_SALT = "pin_salt";
    public static final String KEY_PIN_HASH = "pin_hash";

    // Costanti per il Wrapping del DB
    private static final String DB_WRAP_SALT_PIN = "db_wrap_salt_pin";//Salt del Pin
    private static final String DB_WRAP_IV_PIN   = "db_wrap_iv_pin";//IV usato per cifrare/decifrare, con la chiave derivata dal Pin, la masterkey del DB
    private static final String DB_WRAP_CT_PIN   = "db_wrap_ct_pin";//masterkey del DB, cifrata tramite chiave derivata dal Pin
    private static final String DB_WRAP_IV_BIO   = "db_wrap_iv_bio";//IV usato per cifrare/decifrare, tramite Biometria nel chip interno, la masterkey del DB
    private static final String DB_WRAP_CT_BIO   = "db_wrap_ct_bio";//masterkey del DB, cifrata tramite Bio nel chip interno
    private static final String DB_WRAP_METHOD   = "db_wrap_method";//può assumere i valori “Pin”, “Bio”, “Both”.

    // Parametri Crittografici (OWASP 2025)
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 32; // Standard 256-bit
    private static final int GCM_TAG_LENGTH = 128; //Serve per garantire l’integrità dei dati.
    private static final String SW_TRANSFORMATION = "AES/GCM/NoPadding";

    private SecurityUtils() { /* no instances */ }

    // ======= EncryptedSharedPreferences =======

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
            throw new IllegalStateException("Init EncryptedSharedPreferences fallito", e);
        }
    }

    // ======= Utilità random / salt =======

    public static byte[] generateRandom(int nBytes) {//nBytes==32
        byte[] out = new byte[nBytes];
        new SecureRandom().nextBytes(out);
        return out;
    }

    public static byte[] generateSalt() {
        return generateRandom(SALT_LENGTH_BYTES);
    }

    // ======= KDF & Hashing =======

    /** Deriva/ottiene una chiave AES-256 (“Kdf”) da PIN+salt via PBKDF2(HMAC-SHA-256). */
    public static SecretKey deriveKey(char[] pin, byte[] salt) throws GeneralSecurityException {
        //Specifichiamo come deve essere tale chiave derivata da Pin+salt
        KeySpec spec = new PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        //Specifichiamo la factory/algoritmo che genererà la chiave
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        //Generiamo una chiave con “PBKDF2WithHmacSHA256”. NB non è specificato si tratti di una chiave AES
        SecretKey tmp = factory.generateSecret(spec);
// La classe PBEKeySpec mantiene internamente il riferimento all'array char[] del PIN. Il metodo clearPassword() esegue un ciclo for su quell'array e imposta ogni carattere a 0
        ((PBEKeySpec) spec).clearPassword();
        return new SecretKeySpec(tmp.getEncoded(), "AES"); //generiamo una SecretKey che specifica il tipo di chiave.
//Tale chiave dovrà essere passata ad un Cipher che vuole sapere di che chiave si tratta, per questo è NECESSARIO fare il return di SecretKeySpec e non semplicemente SecretKey.
    }

    /** Helper per ottenere array di byte dalla SecretKey (per poter salvare la chiave AES “kdf” dentro l’EncryptedSharedPreferences bisogna prima ottenere l’array di byte e convertirla in String tramite l’encoding a Base64) */
    public static byte[] kdfKeyFromPin(@NonNull char[] pin, @NonNull byte[] salt) throws GeneralSecurityException {
        return deriveKey(pin, salt).getEncoded();
    }

    //Usato/chiamato in verifyPin() per controllare se le chiavi AES risultanti dal Pin inserito  //dall’utente in fase di auth e da quello conservato nelle EncryptedSharedPreferences sono //uguali.
    public static byte[] hashPin(@NonNull char[] pin, @NonNull byte[] salt) throws GeneralSecurityException {
        // Usiamo la chiave AES derivata (raw bytes) come “hash” del PIN
        return kdfKeyFromPin(pin, salt);
    }

    public static boolean verifyPin(@NonNull char[] candidatePin, @NonNull byte[] salt, @NonNull byte[] expectedHash) throws GeneralSecurityException {
        byte[] h = hashPin(candidatePin, salt);
        try {
//confronto a tempo costante (dà un risultato sempre dopo il controllo di TUTTI i bit delle chiavi) per evitare che, in caso il confronto fallisse, un possibile attaccante capisca in quale punto di tali hash (chiavi AES!) il confronto non vada a buon fine
            return MessageDigest.isEqual(h, expectedHash);
        } finally {
            zeroize(h);
        }
    }

    // ======= AES-GCM software =======

    public static android.util.Pair<byte[], byte[]> aesGcmEncrypt(@NonNull byte[] key, @NonNull byte[] plaintext) throws GeneralSecurityException {
//Cipher è un oggetto “cifrario”
        Cipher c = Cipher.getInstance(SW_TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] ct = c.doFinal(plaintext);
        return new android.util.Pair<>(c.getIV(), ct);//torniamo il ciphertext e l’IV in chiaro (non è necessario nasconderlo), ma da conservare per poter poi decifrare il ciphertext.
    }

    public static byte[] aesGcmDecrypt(@NonNull byte[] key, @NonNull byte[] iv, @NonNull byte[] ct) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(SW_TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));//specifichiamo quanto è lungo il TAG che è stato (automaticamente) calcolato e concatenato al ciphertext, in fase di cifratura.
        return c.doFinal(ct);
    }

    // ======= MASTER KEY RE-WRAPPING (Nuovo) =======

    /**
     * Esegue il Re-Wrapping: Decifra la MasterKey col vecchio PIN e la ricifra col nuovo.
     * Gestisce anche l'aggiornamento dell'hash del PIN per il login.
     */
    public static void reWrapMasterKey(Context context, String oldPin, String newPin)
            throws GeneralSecurityException, IOException {

        SharedPreferences prefs = getEncryptedPrefs(context);

        // Variabili sensibili dichiarate FUORI dal try per visibilità nel finally
        char[] oldPinChars = null;
        char[] newPinChars = null;
        byte[] oldKek = null;
        byte[] newKek = null;
        byte[] newPinHash = null;
        byte[] masterKey = null;

        try {
            // 1. Carica wrapping vecchio (Salt del PIN, masterkeyDB cifrata con chiave derivata da PIN, e IV usato per cifrare/decifrare
            PinWrapData oldData = loadWrappedDbWithPin(context);
            if (oldData == null) throw new GeneralSecurityException("No PIN wrap found");

            // 2. Decifra Master Key (Vecchio PIN)
            oldPinChars = oldPin.toCharArray();
            oldKek = kdfKeyFromPin(oldPinChars, oldData.salt);
            // NOTA: masterKey ora è in chiaro in RAM!
            masterKey = aesGcmDecrypt(oldKek, oldData.iv, oldData.ct);

            // 3. Prepara Nuovo PIN
            byte[] newSalt = generateSalt();
            newPinChars = newPin.toCharArray();

            // 4. Generazione Segreti (Doppia chiamata per sicurezza memoria)
            newPinHash = hashPin(newPinChars, newSalt); // <--- Allocazione Memoria A per auth
            newKek = kdfKeyFromPin(newPinChars, newSalt); // <--- Allocazione Memoria B per cambio pin

            // 5. Ricifra Master Key (Nuovo PIN)
            android.util.Pair<byte[], byte[]> newWrap = aesGcmEncrypt(newKek, masterKey);

            // 6. Salvataggio (Commit Atomico)
            // Qui creiamo la Stringa Base64. newPinHash array non serve più dopo questa riga.
            prefs.edit()
                    .putString(KEY_PIN_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                    .putString(KEY_PIN_HASH, Base64.encodeToString(newPinHash, Base64.NO_WRAP)) // <--- Ultimo utilizzo
                    .putString(DB_WRAP_SALT_PIN, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                    .putString(DB_WRAP_IV_PIN, Base64.encodeToString(newWrap.first, Base64.NO_WRAP))
                    .putString(DB_WRAP_CT_PIN, Base64.encodeToString(newWrap.second, Base64.NO_WRAP))
                    .commit();

        } finally {
            // =============================================================
            // ZONA DI PULIZIA (Viene eseguita SEMPRE, anche se crasha tutto)
            // =============================================================
            zeroize(oldPinChars);
            zeroize(newPinChars);
            zeroize(oldKek);
            zeroize(newKek);
            zeroize(masterKey);

            // ECCOLO: Puliamo l'array hash.
            // La Stringa Base64 nelle SharedPreferences purtroppo resta (limite Android),
            // ma almeno abbiamo distrutto la copia "raw" in nostro possesso.
            zeroize(newPinHash);
        }
    }    // ======= Wrapping Utils =======
    //Una nested class che funziona come una structure di C: inseriamo il Salt del Pin e la masterkey del DB (cifrata tramite chiave derivata dal PIN) + IV usato per cifrare/decifrare. Tali info servono per fare autenticazione e cambio pin.
    public static final class PinWrapData {
        public final byte[] salt;
        public final byte[] iv;//IV necessario per decifrare il ct (ciphertext)
        public final byte[] ct;//è la master key (AES) che cifra il DB, cifrata dalla chiave (anch’essa AES) derivata dal Pin
        public PinWrapData(byte[] salt, byte[] iv, byte[] ct) {
            this.salt = salt; this.iv = iv; this.ct = ct;
        }
    }

    //Il seguente metodo serve per prelevare dalle EncryptedSharedPreference le informazioni per il Login tramite Pin o il “Cambio Pin”
    @Nullable
    public static PinWrapData loadWrappedDbWithPin(@NonNull Context ctx) {
        SharedPreferences p = getEncryptedPrefs(ctx);
        String sSalt = p.getString(DB_WRAP_SALT_PIN, null);//Salt del Pin
        String sIv   = p.getString(DB_WRAP_IV_PIN,   null);//IV con cui cifriamo/decifriamo la masterkey del DB
        String sCt   = p.getString(DB_WRAP_CT_PIN,   null);//masterkey (del DB) cifrata con la chiave derivata dal PIN
        if (sSalt == null || sIv == null || sCt == null) return null;
        return new PinWrapData(
                Base64.decode(sSalt, Base64.NO_WRAP),
                Base64.decode(sIv,   Base64.NO_WRAP),
                Base64.decode(sCt,   Base64.NO_WRAP)
        );
    }

    //Salviamo le info necessarie per il wrapping tramite PIN della chiave master del DB
    public static void saveWrappedDbWithPin(Context ctx, byte[] salt, byte[] iv, byte[] ct) throws GeneralSecurityException, IOException {
        SharedPreferences p = getEncryptedPrefs(ctx);
        boolean hasBio = hasBioWrap(ctx);
        p.edit()
                .putString(DB_WRAP_SALT_PIN, Base64.encodeToString(salt, Base64.NO_WRAP)) //Salt del Pin
                .putString(DB_WRAP_IV_PIN, Base64.encodeToString(iv, Base64.NO_WRAP)) //IV con cui cifriamo/decifriamo la masterkey del DB
                .putString(DB_WRAP_CT_PIN, Base64.encodeToString(ct, Base64.NO_WRAP)) //masterkey (del DB) cifrata con la chiave derivata dal PIN
                .putString(DB_WRAP_METHOD, hasBio ? "both" : "pin")
                .apply();
    }

    //Salviamo le info necessarie per il wrapping tramite biometria della chiave master del DB
    public static void saveWrappedDbWithBiometrics(Context ctx, byte[] iv, byte[] ct) throws GeneralSecurityException, IOException {
        SharedPreferences p = getEncryptedPrefs(ctx);
        boolean hasPin = hasPinWrap(ctx);
        p.edit()
                .putString(DB_WRAP_IV_BIO, Base64.encodeToString(iv, Base64.NO_WRAP))//IV per cifrare/decifrare la masterkey cifrata del DB
                .putString(DB_WRAP_CT_BIO, Base64.encodeToString(ct, Base64.NO_WRAP))//Masterkey (cifrata tramite Biometria) del DB
                .putString(DB_WRAP_METHOD, hasPin ? "both" : "bio")
                .apply();
    }

    //Chiamato in LoginFragment  per effettuare lo sblocco del DB tramite biometria.
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

    //Controlla se è presente il wrapping tramite biometria (è chiamato in saveWrappedDbWithPin())
    public static boolean hasBioWrap(Context ctx) {
        return getEncryptedPrefs(ctx).getString(DB_WRAP_CT_BIO, null) != null;
    }
    //Controlla se è presente il wrapping tramite Pin (è chiamato in saveWrappedDbWithBio())
    public static boolean hasPinWrap(Context ctx) {
        return getEncryptedPrefs(ctx).getString(DB_WRAP_CT_PIN, null) != null;
    }

    // ======= Cleanup =======

    public static void zeroize(@Nullable byte[] data) {
        if (data != null) Arrays.fill(data, (byte) 0);
    }
    public static void zeroize(@Nullable char[] data) {
        if (data != null) Arrays.fill(data, '\0');
    }
}
