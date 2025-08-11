package com.example.securenotes.feature_auth;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import com.example.securenotes.feature_auth.databinding.FragmentLoginBinding;
import com.scottyab.rootbeer.RootBeer;  // Libreria per rilevare il root (aggiungere dipendenza RootBeer)


public class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private AuthViewModel authViewModel;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);

        // Controllo di sicurezza: blocca l'accesso se il dispositivo è rootato
        RootBeer rootBeer = new RootBeer(requireContext());
        if (rootBeer.isRooted()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Dispositivo non sicuro")
                    .setMessage("Il dispositivo risulta rootato. L'app verrà chiusa per motivi di sicurezza.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> requireActivity().finishAffinity())
                    .show();
            return;
        }

        // Osserva il risultato del login (successo/errore PIN)
        authViewModel.getLoginResult().observe(getViewLifecycleOwner(), result -> {
            if (result == AuthViewModel.LoginResult.SUCCESS) {
                // Login riuscito -> naviga alla schermata principale (lista note), rimuovendo dal back-stack le schermate di auth
                NavController nav = NavHostFragment.findNavController(LoginFragment.this);
                nav.navigate(R.id.action_loginFragment_to_notesListFragment, null,
                        new NavOptions.Builder().setPopUpTo(R.id.auth_graph, true).build());
            } else if (result == AuthViewModel.LoginResult.LOCKED) {
                // Troppi tentativi falliti -> lock-out: chiude l'app
                Toast.makeText(requireContext(), "Troppi tentativi falliti. Applicazione bloccata.", Toast.LENGTH_LONG).show();
                requireActivity().finishAffinity();
            } else if (result == AuthViewModel.LoginResult.INCORRECT_PIN) {
                // PIN errato
                Toast.makeText(requireContext(), "PIN errato", Toast.LENGTH_SHORT).show();
                // (Opzionale) Si potrebbe indicare il numero di tentativi rimanenti
            }
        });

        // Configura UI in base alla disponibilità della biometria
        BiometricManager biometricManager = BiometricManager.from(requireContext());
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometria disponibile: avvia subito il prompt biometrico
            showBiometricPrompt();
            binding.pinGroup.setVisibility(View.GONE);  // nasconde campo PIN finché non serve
            // (Il prompt biometrico ha un pulsante "Usa PIN" per fallback)
        } else {
            // Biometria non disponibile: mostra subito il campo PIN
            binding.tvStatus.setText("Biometria non disponibile. Inserire il PIN:");
            binding.pinGroup.setVisibility(View.VISIBLE);
            binding.btnUsePin.setVisibility(View.GONE);
        }

        // Pulsante "Usa PIN" (visibile solo se l'utente rifiuta la biometria)
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
            }
        });
    }

    /** Mostra il prompt biometrico usando BiometricHelper */
    private void showBiometricPrompt() {
        BiometricHelper helper = BiometricHelper.getInstance(requireActivity());
        helper.authenticate(new BiometricHelper.BiometricAuthListener() {
            @Override
            public void onBiometricAuthenticated() {
                // Autenticazione biometrica riuscita
                NavController nav = NavHostFragment.findNavController(LoginFragment.this);
                nav.navigate(R.id.action_loginFragment_to_notesListFragment, null,
                        new NavOptions.Builder().setPopUpTo(R.id.auth_graph, true).build());
            }
            @Override
            public void onBiometricError(int errorCode, @NonNull CharSequence errMsg) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // L'utente ha premuto "Usa PIN" nel prompt biometrico -> mostra interfaccia PIN
                    showPinLoginUI();
                } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                    // Utente ha annullato il prompt o troppi tentativi biometrici falliti -> chiudi app
                    Toast.makeText(requireContext(), "Accesso annullato.", Toast.LENGTH_SHORT).show();
                    requireActivity().finishAffinity();
                } else {
                    // Altri errori biometrici (hardware non disponibile, etc.)
                    Toast.makeText(requireContext(), "Errore biometrico: " + errMsg, Toast.LENGTH_SHORT).show();
                    showPinLoginUI();
                }
            }
            @Override
            public void onBiometricFailed() {
                // Impronta non riconosciuta (tentativo fallito, il prompt rimane aperto per riprovare)
                Toast.makeText(requireContext(), "Impronta non riconosciuta. Riprova.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Mostra i campi di input PIN (chiamato quando l'utente opta per il PIN) */
    private void showPinLoginUI() {
        binding.tvStatus.setText("Inserisci il PIN per accedere:");
        binding.pinGroup.setVisibility(View.VISIBLE);
        binding.btnUsePin.setVisibility(View.GONE);
    }

    /** Cancella il prompt biometrico se attivo (ad esempio quando l'utente passa a PIN) */
    private void cancelBiometricPrompt() {
        // BiometricPrompt di Android si chiude automaticamente quando l'utente preme "Usa PIN" o esce,
        // quindi in genere non è necessario annullarlo manualmente.
        // Questa funzione può rimanere vuota o gestire un CancellationSignal se implementato.
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
