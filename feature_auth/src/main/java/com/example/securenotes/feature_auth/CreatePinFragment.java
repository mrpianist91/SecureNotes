package com.example.securenotes.feature_auth;

import static java.lang.Integer.getInteger;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.example.securenotes.core.AuthManager;
import com.example.securenotes.core.SecurityUtils;
import com.example.securenotes.feature_auth.databinding.FragmentCreatePinBinding;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
        * CreatePinFragment:
        * 1) Valida PIN + conferma e salva l'hash+salt via ViewModel.
        * 2) Provisioning passphrase DB:
        *    - se biometria AVAILABLE: wrappa con Keystore AES-GCM gated da BiometricPrompt (salva IV+CT).
        *    - se biometria NOT_ENROLLED: forzi enrollment (non prosegui).
        *    - se NO_HARDWARE: fallback software con KDF(PIN)+AES-GCM (salva salt+IV+CT).
        */
public class CreatePinFragment extends Fragment {
    private FragmentCreatePinBinding binding;
    private AuthViewModel authViewModel;

    // Launcher per mandare l’utente alle Impostazioni a fare l’enrollment biometrico
    private ActivityResultLauncher<Intent> enrollLauncher;

    // Flag per la modalità (onboarding vs cambio pin)
    private boolean isChangeMode = false;

    // Questo metodo serve per dire al sistema che vuoi gestire la Toolbar (per il tasto Back)
    /*@Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }*/

    // 2. Aggiungi questo metodo per intercettare il click sulla freccia
    /*@Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // android.R.id.home è l'ID di sistema della freccia "Indietro" nella Toolbar
        if (item.getItemId() == android.R.id.home) {
            // Chiamiamo il nostro metodo helper che fa il popBackStack() sicuro
            navigateBack();
            return true; // Indichiamo che abbiamo gestito l'evento
        }
        return super.onOptionsItemSelected(item);
    }*/
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCreatePinBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    private void updateCreatePinEnabled() {
        String pin = binding.etPin.getText().toString().trim();
        String confirm = binding.etConfirmPin.getText().toString().trim();
        int minLen = getResources().getInteger(R.integer.min_pin_length);
        int strength = authViewModel.calculatePinStrength(pin);
        boolean ok = pin.length() >= minLen && pin.equals(confirm) && strength >= 60; // soglia a piacere
        binding.btnCreatePin.setEnabled(ok);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);

        // 1. Recupera l'argomento dal Navigation Component
        if (getArguments() != null) {
            isChangeMode = CreatePinFragmentArgs.fromBundle(getArguments()).getIsChangeMode();
        }

        // Adatta la UI in base alla modalità
        if (isChangeMode) {
            binding.tvCreatePin.setText("Imposta Nuovo PIN");
            binding.btnCreatePin.setText("Cambia PIN");
        }

        // Al rientro dalle Impostazioni: ricontrolla biometria e, se ok, completa il wrapping via prompt
        enrollLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {//lambda invocata quando si torna dalle Impostazioni; "result" è il valore tornato

                    /*BiometricHelper.BiometricCapability cap = BiometricHelper.ensureBiometricKey(requireContext());
                    if (cap == BiometricHelper.BiometricCapability.AVAILABLE) {
                      // Enrollment completato: puoi proseguire col provisioning sicuro della passphrase DB usando la biometria:
                        provisionDatabaseSecretWithBiometrics();
                    } else { //Enrollment non andato a buon fine
                        Toast.makeText(requireContext(), "Completa l'attivazione biometrica per continuare", Toast.LENGTH_LONG).show();
                    }*/

                    // Controlliamo solo se l'utente ha configurato qualcosa
                    BiometricManager bm = BiometricManager.from(requireContext());
                    int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                    // Enrollment completato: puoi proseguire col provisioning sicuro della passphrase DB usando la biometria:
                    if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                        // Se siamo in onboarding, facciamo il provisioning completo
                        if (!isChangeMode) {
                            provisionDatabaseSecretWithBiometrics();
                        } else {
                            // Se siamo in cambio PIN, la chiave è già wrappata col PIN.
                            // Possiamo provare a ri-wrapparla con la biometria, ma attenzione a NON rigenerarla.
                            // Per semplicità e sicurezza, torniamo indietro.
                            navigateBack();
                        }
                    } else { //Enrollment non andato a buon fine
                        Toast.makeText(requireContext(), "Attivazione biometrica non completata", Toast.LENGTH_LONG).show();
                        // Se fallisce l'enrollment durante il cambio pin, torniamo comunque indietro con successo parziale (PIN cambiato, bio no)
                        if(isChangeMode) navigateBack(); //navigateToLoginClearingAuthGraph();
                    }


                });

        // Aggiorna barra di robustezza ad ogni digitazione del PIN
        binding.etPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String pin = s.toString();
                int strength = authViewModel.calculatePinStrength(pin);//BISOGNA RIVEDERE L'ALGORITMO, SENZA SENSO
                binding.strengthBar.setProgress(strength);
                // Cambia colore/etichetta in base al valore di robustezza
                if (strength < 34) {
                    binding.tvStrengthLabel.setText("Debole");
                    binding.strengthBar.setIndicatorColor(getResources().getColor(android.R.color.holo_red_light));
                } else if (strength < 67) {
                    binding.tvStrengthLabel.setText("Media");
                    binding.strengthBar.setIndicatorColor(getResources().getColor(android.R.color.holo_orange_light));
                } else {
                    binding.tvStrengthLabel.setText("Forte");
                    binding.strengthBar.setIndicatorColor(getResources().getColor(android.R.color.holo_green_light));
                }

                updateCreatePinEnabled();

            }
        });

        // Verifica in tempo reale la corrispondenza tra PIN e Conferma PIN
        binding.etConfirmPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String pin = binding.etPin.getText().toString();
                if (pin.length() > 0 && s.length() > 0 && !s.toString().equals(pin)) {
                    binding.etConfirmPin.setError("I PIN non coincidono");
                } else {
                    binding.etConfirmPin.setError(null);
                }
                updateCreatePinEnabled();
            }
        });


        // Pulsante per creare/aggiornare il PIN
        binding.btnCreatePin.setOnClickListener(v -> {
            String pin = binding.etPin.getText().toString().trim();//trim toglie gli spazi vuoti all'inizio e alla fine della stringa...forse è ridondante perchè teoricamnte la textview accetta solo numeri
            String confirm = binding.etConfirmPin.getText().toString().trim();
            int minLen = getResources().getInteger(R.integer.min_pin_length);
            if (pin.length() < minLen) {
                     Toast.makeText(requireContext(), getString(R.string.error_pin_too_short, minLen), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!pin.equals(confirm)) {
                Toast.makeText(requireContext(), "I PIN inseriti non coincidono", Toast.LENGTH_SHORT).show();
                return;
            }
            // DIRAMAZIONE LOGICA
            if (isChangeMode) {
                // MODALITA' CAMBIO PIN
                authViewModel.changePin(pin);
            } else {
                // MODALITA' CREAZIONE (Onboarding)
                authViewModel.createPin(pin);
            }

            // Salva il PIN in modo sicuro tramite ViewModel
           // authViewModel.createPin(pin);

            /*// 2) Provisioning del segreto DB in base alla biometria
            BiometricHelper.BiometricCapability cap = BiometricHelper.ensureBiometricKey(requireContext());
            if (cap == BiometricHelper.BiometricCapability.NOT_ENROLLED) {
                // Biometria presente ma non configurata → forzi enrollment (non si prosegue)
                enrollLauncher.launch(BiometricHelper.enrollmentIntent());
            } else if (cap == BiometricHelper.BiometricCapability.AVAILABLE) {
                provisionDatabaseSecretWithBiometrics();
            } else { // NO_HARDWARE
                provisionDatabaseSecretWithPinFallback(pin);
            }*/


            // 2) Controllo Biometria
            /*BiometricManager bm = BiometricManager.from(requireContext());
            int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

            if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                // Biometria possibile ma non configurata -> Mandiamo l'utente alle impostazioni
                // Usiamo l'Intent fornito da AuthManager
                Intent enrollIntent = AuthManager.getInstance(requireContext()).getEnrollmentIntent();
                enrollLauncher.launch(enrollIntent);
            } else if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                provisionDatabaseSecretWithBiometrics();
            } else {
                // Hardware non disponibile o altri errori -> Fallback PIN
                provisionDatabaseSecretWithPinFallback(pin);
            }*/

        });

        // OSSERVAZIONE RISULTATI

        // 1. Creazione (Esistente)
        authViewModel.getPinCreated().observe(getViewLifecycleOwner(), event -> {
            Boolean success = event.getContentIfNotHandled();
            if (success != null) {
                if (success) {
                    handlePostPinCreation(binding.etPin.getText().toString().trim());
                }
             else { // false
                Toast.makeText(requireContext(), "Errore creazione PIN", Toast.LENGTH_SHORT).show();
             }
            }
        });

        // 2. Cambio (Settings)
        authViewModel.getPinChanged().observe(getViewLifecycleOwner(), event -> {
            // Stessa logica per il cambio PIN
            Boolean success = event.getContentIfNotHandled();
            if (success!=null) {
                if (success) {
                    Toast.makeText(requireContext(), "PIN modificato con successo", Toast.LENGTH_SHORT).show();
                    // Il PIN è cambiato e il DB è stato re-wrappato.
                    // Non serve rifare il provisioning biometrico completo se l'utente non lo desidera,
                    // ma per sicurezza manteniamo la logica esistente: controlliamo se si può usare la bio.
                   // handlePostPinCreation(binding.etPin.getText().toString().trim());
                    navigateBack();
                } else { // false
                    Toast.makeText(requireContext(), "Errore durante il cambio PIN", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // --- FIX NAVIGAZIONE TOOLBAR (FRECCIA INDIETRO) ---
        // Recuperiamo la Toolbar dell'Activity
        com.google.android.material.appbar.MaterialToolbar toolbar =
                requireActivity().findViewById(getResources().getIdentifier("toolbar", "id", requireContext().getPackageName()));

        if (toolbar != null) {
            // Sovrascriviamo il comportamento del click sulla freccia.
            // Invece di "navigateUp" (che crasha), forziamo "navigateBack" (che fa il popBackStack sicuro).
            toolbar.setNavigationOnClickListener(v -> {
                navigateBack(); // Richiama il tuo metodo helper esistente
            });
        }

    }

    /**
     * Gestisce cosa fare dopo che il PIN è stato salvato (sia Create che Change).
     * Controlla la biometria e decide se fare provisioning o uscire.
     */
    private void handlePostPinCreation(String pin) {
        // 2) Controllo Biometria
        BiometricManager bm = BiometricManager.from(requireContext());
        int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            // Biometria possibile ma non configurata -> Mandiamo l'utente alle impostazioni
            // Usiamo l'Intent fornito da AuthManager
            Intent enrollIntent = AuthManager.getInstance(requireContext()).getEnrollmentIntent();
            enrollLauncher.launch(enrollIntent);
        } else if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            provisionDatabaseSecretWithBiometrics();
        } else {
            // Hardware non disponibile o altri errori -> Fallback PIN
            provisionDatabaseSecretWithPinFallback(pin);
        }
    }
    private static String codeName(int code) {
        switch (code) {
            case BiometricPrompt.ERROR_HW_UNAVAILABLE: return "ERROR_HW_UNAVAILABLE";
            case BiometricPrompt.ERROR_UNABLE_TO_PROCESS: return "ERROR_UNABLE_TO_PROCESS";
            case BiometricPrompt.ERROR_TIMEOUT: return "ERROR_TIMEOUT";
            case BiometricPrompt.ERROR_NO_SPACE: return "ERROR_NO_SPACE";
            case BiometricPrompt.ERROR_CANCELED: return "ERROR_CANCELED";
            case BiometricPrompt.ERROR_LOCKOUT: return "ERROR_LOCKOUT";
            case BiometricPrompt.ERROR_VENDOR: return "ERROR_VENDOR";
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT: return "ERROR_LOCKOUT_PERMANENT";
            case BiometricPrompt.ERROR_USER_CANCELED: return "ERROR_USER_CANCELED";
            case BiometricPrompt.ERROR_NO_BIOMETRICS: return "ERROR_NO_BIOMETRICS";
            case BiometricPrompt.ERROR_HW_NOT_PRESENT: return "ERROR_HW_NOT_PRESENT";
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON: return "ERROR_NEGATIVE_BUTTON";
            case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL: return "ERROR_NO_DEVICE_CREDENTIAL";
            case BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED: return "ERROR_SECURITY_UPDATE_REQUIRED";
            default: return "UNKNOWN";
        }
    }
    /** Wrapping con Keystore AES-GCM gated da BiometricPrompt; salva IV+CT. */
    private void provisionDatabaseSecretWithBiometrics() {
        // 1) Genera 32 byte casuali per la passphrase del DB (SQLCipher).
        //    Nota: è in chiaro SOLO in RAM finché non la cifriamo.
        byte[] passphrase = SecurityUtils.generateRandom(32); // 32 byte per SQLCipher
        try {
            // 1a) Wrappa SUBITO con PIN (fallback garantito anche se la biometria viene annullata)
            String pin = binding.etPin.getText().toString().trim();
            byte[] salt = SecurityUtils.generateSalt();                 // 128 bit
            byte[] pinKey = SecurityUtils.kdfKeyFromPin(pin.toCharArray(), salt);
            Pair<byte[], byte[]> pinWrap = SecurityUtils.aesGcmEncrypt(pinKey, passphrase); // (iv, ct)
            Arrays.fill(pinKey, (byte)0);
            SecurityUtils.saveWrappedDbWithPin(requireContext(), salt, pinWrap.first, pinWrap.second);
            // 2) Prepara un Cipher AES/GCM per CIFRARE con la chiave del Keystore.
            //    getEncryptCipher() fa: Cipher.getInstance("AES/GCM/NoPadding") + init(ENCRYPT_MODE, key)
            //    e genera un IV random interno (lo leggerai con cipher.getIV() DOPO il prompt).

            //Cipher enc = BiometricHelper.getEncryptCipher(); // IV random interno
            // MODIFIED: Otteniamo il Cipher da AuthManager invece che da BiometricHelper
            Cipher enc = AuthManager.getInstance(requireContext()).getBiometricEncryptCipher();
            // 3) Costruisci il BiometricPrompt: le callback arrivano sul main thread (executor compat).
            BiometricPrompt prompt = new BiometricPrompt(
                    requireActivity(),
                    androidx.core.content.ContextCompat.getMainExecutor(requireContext()),
                    new BiometricPrompt.AuthenticationCallback() {
                        // 4) L'utente si è autenticato con successo:
                        @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            try {
                                // 4a) Recupera lo STESSO Cipher "sbloccato" dal CryptoObject del risultato.
                                Cipher armed = result.getCryptoObject().getCipher();
                                // 4b) Cifra la passphrase: ct contiene ANCHE il tag GCM (AEAD).
                                byte[] ct = armed.doFinal(passphrase); // include tag GCM
                                // 4c) Preleva l'IV usato in questa cifratura (necessario per la futura decrypt).
                                byte[] iv = armed.getIV();
                                // 4d) Salva IV + ciphertext a riposo (EncryptedSharedPreferences).
                                SecurityUtils.saveWrappedDbWithBiometrics(requireContext(), iv, ct);

                                // Notifichiamo all'AuthManager che la biometria è ufficialmente attiva
                                AuthManager.getInstance(requireContext()).setBiometricEnabled(true);

                                // 4e) Provisioning completato (solo PIN o PIN+BIO): vai oltre (es. torna al Login).
                                navigateToLoginClearingAuthGraph();
                            } catch (GeneralSecurityException | IOException e) {
                                // Se succede qualcosa durante doFinal(), mostra un errore "cifratura".
                                Log.e("CreatePin", "BIO wrap error", e);
                                Toast.makeText(requireContext(), "Errore cifratura biometrica", Toast.LENGTH_SHORT).show();
                            } finally {
                                // 4f) In TUTTI i casi: azzera la passphrase in RAM (difesa opportuna).
                                Arrays.fill(passphrase, (byte)0);
                            }
                        }
                        // 5) L'utente ha annullato/chiuso il prompt o c'è stato un errore "di canale".
                        @Override public void onAuthenticationError(int code, @NonNull CharSequence err) {
                            // Utente ha annullato / errore HW: resti PIN-only e prosegui comunque
                            Arrays.fill(passphrase, (byte)0);
                            navigateToLoginClearingAuthGraph();
                        }
                    });
            // 6) Configura il prompt: SOLO biometria "strong", coerente con la tua policy.
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Proteggi SecureNotes")
                    .setSubtitle("Autenticati per proteggere la chiave del database")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Annulla") // obbligatorio senza DEVICE_CREDENTIAL
                    .build();
            // 7) Avvia il prompt passando il Cipher nel CryptoObject: l'uso del Cipher
            //    (cioè la cifratura) sarà consentito SOLO dopo l'autenticazione.
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(enc));
        } catch (Exception e) {
            // 8) Se non riesci neppure ad arrivare al prompt (es. chiave mancante),
            //    notifica l'errore e azzera la passphrase.
            Log.e("CreatePin", "Biometric crypto error", e);
            Arrays.fill(passphrase, (byte)0);
            Toast.makeText(requireContext(), "Biometria non disponibile", Toast.LENGTH_SHORT).show();
            // Se fallisce l'inizializzazione del Cipher, procediamo comunque col PIN (che abbiamo già salvato)
            navigateToLoginClearingAuthGraph();
        }
    }

    /** Fallback software: KDF(PIN) + AES-GCM; salva salt+IV+CT. */
    private void provisionDatabaseSecretWithPinFallback(@NonNull String pin) {
        byte[] passphrase = SecurityUtils.generateRandom(32);
        try {
            byte[] salt = SecurityUtils.generateSalt();
            byte[] key  = SecurityUtils.kdfKeyFromPin(pin.toCharArray(), salt);
            Pair<byte[], byte[]> out = SecurityUtils.aesGcmEncrypt(key, passphrase); // (iv, ct)
            Arrays.fill(key, (byte)0);
            SecurityUtils.saveWrappedDbWithPin(requireContext(), salt, out.first, out.second);
            navigateToLoginClearingAuthGraph();
        } catch (GeneralSecurityException e) {
            Toast.makeText(requireContext(), "Errore cifratura PIN", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            Arrays.fill(passphrase, (byte)0);
        }
    }

    /** Helper per tornare indietro o al login */
    private void navigateBack() {
        NavHostFragment.findNavController(this).popBackStack();
    }

    /** Torna al Login e fa pop dell’intero grafo di autenticazione. */
    private void navigateToLoginClearingAuthGraph() {
        NavController nav = NavHostFragment.findNavController(this);
        nav.navigate(R.id.loginFragment, null,
                new NavOptions.Builder().setPopUpTo(R.id.auth_nav, true).build());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
