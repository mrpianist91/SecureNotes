package com.example.securenotes.feature_auth;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.example.securenotes.core.AppDatabase;
import com.example.securenotes.core.AuthManager;
import com.example.securenotes.core.SecurityUtils;
import com.example.securenotes.feature_auth.databinding.FragmentLoginBinding;
import com.scottyab.rootbeer.RootBeer;  // Libreria per rilevare il root (aggiungere dipendenza RootBeer)

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;


public class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private AuthViewModel authViewModel;
    private Handler handler = new Handler(Looper.getMainLooper());
    private CountDownTimer lockTimer;

    // Callback verso l'Activity per notificare il successo del login al SessionObserver.
    private AuthListener authListener;
    //onAttach(Context context) è il primo metodo del ciclo di vita del Fragment a essere invocato.
    // Il Fragment viene associato al suo Host (l'Activity). Il parametro context passato dal sistema è l'Activity ospitante.
    //onAttach() rappresenta l'inizializzazione delle dipendenze esterne (il genitore), mentre onCreate dovrebbe occuparsi dell'inizializzazione dello stato interno del Fragment (variabili, ViewModel, ecc.).
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Recupera il listener della (Main)Activity (context) ospitante per poter notificare il successo del processo di autenticazione al Main
        if (context instanceof AuthListener) {
            authListener = (AuthListener) context;//la MainActivity implementa l’interface AuthListener
        } else {
            throw new RuntimeException(context.toString() + " deve implementare AuthListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        authListener = null; // Evita memory leaks
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        Log.d("LoginFragment", "VM id=" + System.identityHashCode(authViewModel));
        // Se esiste già un lock (da tentativi precedenti), riflettiamolo subito in UI
        startLockCountdown(authViewModel.getLockRemainingMillis());
        // Controllo di sicurezza: blocca l'accesso se il dispositivo è rootato.
        // Usiamo solo i check ad alta affidabilità per evitare falsi positivi
        // - detectRootManagementApps: app di root presenti (SuperSU, Magisk Manager, ecc.)
        // - checkForMagiskBinary: binary di Magisk rilevato
        // - checkSuExists: SU esistente/attivo
        RootBeer rootBeer = new RootBeer(requireContext());
        boolean isRooted = rootBeer.detectRootManagementApps()
                || rootBeer.checkForMagiskBinary()
                || rootBeer.checkSuExists();
        if (isRooted) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Dispositivo non sicuro")
                    .setMessage("Il dispositivo risulta rootato. L'app verrà chiusa per motivi di sicurezza.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> requireActivity().finishAffinity())
                    .show();
            return;
        }
       //Se premo "Invio" quando digito il Pin, in automatico performa il click sul "btnLogin" (effettua il Login)
        binding.etPin.setOnEditorActionListener((v, actionId, event) -> {
            //Quando in una tastiera (IME=Input Method Editor) si preme un tasto, a seconda del device può essere generato un "actionId" o un "Event". Nel seguente if controlliamo se, quando premiamo "Invio/Fatto" viene generato un actionID o un Event.
            if (actionId == EditorInfo.IME_ACTION_DONE //controlliamo che l'action ID sia la pressione di "Fatto/Invio"
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP)) {//l'evento ACTION_UP viene lanciato quando viene "rilasciato" il tasto premuto
                binding.btnLogin.performClick();
                return true;
            }
            return false;
        });


        Log.d("LoginFragment", "loginResult id=" + System.identityHashCode(authViewModel.getLoginResult()));
        // Osserva il risultato del login (successo/errore PIN)
        authViewModel.getLoginResult().observe(getViewLifecycleOwner(), result -> {
            Log.d("LoginFragment", "loginResult observed: " + result);
            if (result == AuthViewModel.LoginResult.SUCCESS) {
                // Login PIN riuscito -> decripta la passphrase del DB con il PIN e apri il database
                String pinInput = binding.etPin.getText().toString().trim();
                if (!pinInput.isEmpty()) {
                    try {
                        /*E’ una final class definita dentro SecurityUtils.java. Funge da contenitore semplice per SALT + IV + CipherText del PIN. */
                        SecurityUtils.PinWrapData wrapped = SecurityUtils.loadWrappedDbWithPin(requireContext());
                        if (wrapped != null) {
                            // Deriva la chiave AES a 256 bit dal PIN e salt salvati tramite algoritmo PBKDF2(HMAC-SHA-512)
                            char[] pinChars = pinInput.toCharArray();
                            byte[] aesKey = SecurityUtils.kdfKeyFromPin(pinChars, wrapped.salt);
                            SecurityUtils.zeroize(pinChars); // Pulisce il PIN in memoria
                            // Decifra la passphrase del DB usando IV e CT salvati
                            byte[] passphrase = SecurityUtils.aesGcmDecrypt(aesKey, wrapped.iv, wrapped.ct);
                            Arrays.fill(aesKey, (byte) 0); // Azzera la chiave derivata
                           // Apre il database cifrato con la passphrase ottenuta
                            AppDatabase.openWithPassphrase(requireContext(), passphrase);
                            SecurityUtils.zeroize(passphrase); // Pulisce la passphrase dopo l'uso
                            //Notifica successo PIN per l'Observer
                            notifyLoginSuccess();
                        }
                    } catch (GeneralSecurityException e) {
                        Toast.makeText(requireContext(), "Errore decrittazione database", Toast.LENGTH_LONG).show();
                                e.printStackTrace();
                        // In caso di errore critico, si potrebbe interrompere qui
                    }
                }
                // Naviga alla schermata principale (lista note), rimuovendo le schermate di auth dallo stack
                //NavController nav = NavHostFragment.findNavController(LoginFragment.this);
                //nav.navigate(R.id.action_loginFragment_to_notesListFragment);
            } else if (result == AuthViewModel.LoginResult.LOCKED) {
                // lock con durata variabile, basato su KEY_LOCK_UNTIL
                startLockCountdown(authViewModel.getLockRemainingMillis());
            } else if (result == AuthViewModel.LoginResult.INCORRECT_PIN) {
                // PIN errato
                Toast.makeText(requireContext(), "PIN errato", Toast.LENGTH_SHORT).show();

            }
        });

        // Gating iniziale: prova lo sblocco BIOMETRICO solo se:
        // 1) l'hardware è disponibile e 2) ESISTE l'envelop biometrico del DB salvato.
        BiometricManager biometricManager = BiometricManager.from(requireContext());
        boolean bioAvailable = (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS);
        //Controlliamo il flag in AuthManager
        boolean isEnabledInSettings = AuthManager.getInstance(requireContext()).isBiometricEnabled();
        boolean hasBioWrap = SecurityUtils.hasBioWrap(requireContext());
        if (bioAvailable && isEnabledInSettings && hasBioWrap) {
            showBiometricPromptForDbUnlock();          // prompt con decrypt dell'envelop biometrico
            binding.pinGroup.setVisibility(View.GONE); // nasconde PIN finché non serve
            binding.btnUsePin.setVisibility(View.VISIBLE);
        } else {
            // Nessun envelop bio o hardware non disponibile → vai di PIN
           showPinLoginUI();

        }

        // Pulsante "Usa PIN" (fallback esplicito dall'utente)
        binding.btnUsePin.setOnClickListener(v -> {
            cancelBiometricPrompt();   // Assicura la chiusura di eventuale prompt biometrico attivo
            showPinLoginUI();
        });

        // Pulsante Conferma PIN
        binding.btnLogin.setOnClickListener(v -> {
            String pinInput = binding.etPin.getText().toString().trim();
            if (pinInput.isEmpty()) {
                Toast.makeText(requireContext(), "Inserisci il PIN", Toast.LENGTH_SHORT).show();
            } else {
                authViewModel.verifyPin(pinInput);  // verifica il PIN in background (risultato via LiveData)
                Log.d("LoginFragment", "verifica del pin conclusa");
                Log.d("LoginFragment", "VM id=" + System.identityHashCode(authViewModel));


            }
        });
    }


    /**
     * Mostra il BiometricPrompt per lo sblocco dell'envelop biometrico del DB. Su successo emette l'evento di nav via VM.
     * Se l'utente annulla o c'è un errore → fallback immediato al PIN.
     */
    private void showBiometricPromptForDbUnlock() {
        try {
            // Carica (IV, CT) dell'envelop biometrico salvata
            Pair<byte[], byte[]> wrap = SecurityUtils.loadWrappedDbWithBiometrics(requireContext());
            if (wrap == null || wrap.first == null || wrap.second == null) {
                showPinLoginUI();
                return;
            }
            byte[] iv = wrap.first;
            byte[] ct = wrap.second;



            // Otteniamo il Cipher da AuthManager
            // Questo metodo lancia KeyPermanentlyInvalidatedException se sono state aggiunte impronte!
            final Cipher dec = AuthManager.getInstance(requireContext()).getBiometricDecryptCipher(iv);

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.biometric_prompt_title))
                    .setSubtitle(getString(R.string.biometric_prompt_message))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(getString(R.string.action_use_pin)) // pulsante esplicito per PIN
                    .build();
            new BiometricPrompt(requireActivity(), ContextCompat.getMainExecutor(requireContext()),
                    new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    try {
                        Cipher armed = result.getCryptoObject() != null ? result.getCryptoObject().getCipher() : null;
                        if (armed == null) {
                            showPinLoginUI();
                            return;
                        }
                        // Decifra la passphrase DB; si userà per aprire il DB
                        byte[] pass = armed.doFinal(ct);
                        // Apre il database cifrato con SQLCipher (istanza singleton)
                        AppDatabase.openWithPassphrase(requireContext(), pass);
                        SecurityUtils.zeroize(pass); //azzera per sicurezza
                        //Notifica successo Biometrico
                        notifyLoginSuccess();
                    } catch (GeneralSecurityException e) {
                        Toast.makeText(requireContext(), R.string.bio_generic_crypto_error, Toast.LENGTH_SHORT).show();
                        showPinLoginUI();
                    }
                }
                @Override
                public void onAuthenticationError(int code, @NonNull CharSequence err) {
                    // Negative/Cancel → passa subito al PIN; altri errori → mostra messaggio + PIN
                    if (code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            && code != BiometricPrompt.ERROR_USER_CANCELED
                            && code != BiometricPrompt.ERROR_CANCELED) {
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show();
                    }
                    showPinLoginUI();
                }
                @Override
                public void onAuthenticationFailed() {
                    Toast.makeText(requireContext(), R.string.biometric_try_again, Toast.LENGTH_SHORT).show();
                }
            }
            ).authenticate(info, new BiometricPrompt.CryptoObject(dec));
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {

            //GESTIONE SICUREZZA "EVIL MAID" (attacco della "ragazza gelosa")
            Log.e("LoginFragment", "Chiave invalidata: nuove impronte rilevate.", e);

            // 1. Disabilita la biometria nell'app (la chiave crittografica è persa per sempre)
            AuthManager.getInstance(requireContext()).setBiometricEnabled(false);

            // 2. Avvisa l'utente
            new AlertDialog.Builder(requireContext())
                    .setTitle("Sicurezza Biometrica")
                    .setMessage("Sono state rilevate nuove impronte digitali nelle impostazioni del dispositivo. " +
                            "Per sicurezza, l'accesso biometrico è stato disattivato. Accedi con il PIN.")
                    .setPositiveButton("OK", (d, w) -> showPinLoginUI())
                    .setCancelable(false)
                    .show();

        } catch (Exception e) {
            Log.e("LoginFragment", "Errore setup biometria", e);
            showPinLoginUI();
        }
    }

    /** Helper centralizzato per gestire il successo del login */
    private void notifyLoginSuccess() {
        // 1. Notifica l'Activity (che avvierà SessionObserver)
        if (authListener != null) {
            authListener.onAuthSuccess();
        }

        // 2. Naviga alla schermata Note, rimuovendo le schermate di auth dallo stack
        NavController nav = NavHostFragment.findNavController(LoginFragment.this);
        nav.navigate(R.id.action_loginFragment_to_notesListFragment);
    }


    /** Mostra i campi di input PIN (chiamato quando l'utente opta per il PIN) */
    private void showPinLoginUI() {
        binding.tvStatus.setText("Inserisci il PIN per accedere:");
        binding.pinGroup.setVisibility(View.VISIBLE);
        binding.btnUsePin.setVisibility(View.GONE);
    }

    /** Abilita/Disabilita i controlli PIN in blocco */
    private void enablePinInputs(boolean enabled) {
        binding.etPin.setEnabled(enabled);
        binding.btnLogin.setEnabled(enabled);
    }
    /** Avvia/aggiorna il countdown di lock basato sui ms residui ritornati dal ViewModel */
    private void startLockCountdown(long remainingMs) {
        // Cancella eventuale timer attivo per evitare doppi aggiornamenti
        if (lockTimer != null) {
            lockTimer.cancel();
            lockTimer = null;
        }
        if (remainingMs <= 0L) {
            // Nessun lock attivo: UI pronta
            enablePinInputs(true);
            // (Mantieni lo status corrente; se preferisci, reimposta il titolo standard)
            return;
        }
        enablePinInputs(false);
        // Mostra subito lo stato iniziale arrotondato ai secondi
        long initialSec = (remainingMs + 999) / 1000;
        // Riusa la tua stringa esistente con %d (es. "Troppi tentativi. Riprova tra %1$d s.")
        binding.tvStatus.setText(getString(R.string.error_too_many_attempts, initialSec));
        lockTimer = new CountDownTimer(remainingMs, 1000L) {
            @Override public void onTick(long msLeft) {
                long sec = (msLeft + 999) / 1000;
                binding.tvStatus.setText(getString(R.string.error_too_many_attempts, sec));
            }
            @Override public void onFinish() {
                enablePinInputs(true);
                binding.tvStatus.setText(getString(R.string.login_title));
            }
        };
        lockTimer.start();
    }

    /** Cancella il prompt biometrico se attivo (ad esempio quando l'utente passa a PIN) */
    private void cancelBiometricPrompt() {}

    @Override
    public void onResume() {
        super.onResume();
        Runnable r = new Runnable(){ public void run(){ Log.d("MAIN","tick"); handler.postDelayed(this, 500);} };
        handler.post(r);

    }
    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (lockTimer != null) {
            lockTimer.cancel();
            lockTimer = null;
        }
        binding = null;
    }
}
