package com.example.securenotes.feature_auth;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.securenotes.core.PreferenceManager;
import com.example.securenotes.feature_auth.AuthViewModel;
import com.example.securenotes.feature_auth.R;
import com.example.securenotes.feature_auth.databinding.FragmentLoginBinding;
import java.util.concurrent.Executor;
import android.os.CountDownTimer;
import javax.crypto.Cipher;

public class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private AuthViewModel authViewModel;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private NavController navController;
    private CountDownTimer lockTimer;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable
    ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle
            savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        navController = NavHostFragment.findNavController(this);
        PreferenceManager prefs = new PreferenceManager(requireContext());
        authViewModel = new ViewModelProvider(this, new AuthViewModelFactory(prefs)).get(AuthViewModel.class);
        setupListeners();
        observeViewModel();
        setupBiometrics();
    }
    private void setupListeners() {
        binding.loginButton.setOnClickListener(v -> {
            String pin = binding.loginPinEditText.getText().toString();
            authViewModel.loginWithPin(pin);
        });
        binding.loginBiometricButton.setOnClickListener(v -> {
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void observeViewModel() {
        authViewModel.getLoginState().observe(getViewLifecycleOwner(), state ->
        {
            binding.loginPinInputLayout.setError(null);
            switch (state.status) {
               /* case SUCCESS -> {
                    cancelTimer();
                    navController.navigate(R.id.action_login_to_home);
                }
                case FAILURE -> binding.errorText.setText(
                        getString(R.string.err_attempts, st.attempts));
                case LOCKED  -> startLockoutCountDown(st.millisLeft);*/
                case LOCKED: setUiLoading(true); break;
                case SUCCESS: setUiLoading(false); navigateToMainApp(); break;
                case FAILURE: setUiLoading(false);
                    handleLoginError(state.getError()); break;
                default: setUiLoading(false);
            }
        });
    }

    private void handleLoginError(String error) {
        if (error == null) return;
        if (error.contains("Troppi tentativi falliti")) {
            try {
                String secondsStr = error.replaceAll("\\D+", "");
                long seconds = Long.parseLong(secondsStr);
                startLockoutTimer(seconds);
            } catch (NumberFormatException e) {
                binding.loginPinInputLayout.setError(error);
            }
        } else {
            binding.loginPinInputLayout.setError(error);
        }
    }

    private void startLockoutTimer(long seconds) {
        setUiLoading(true);
        new CountDownTimer(seconds * 1000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long remaining = millisUntilFinished / 1000;
                binding.loginPinInputLayout.setError(getString(R.string.error_too_many_attempts, remaining));
            }
            @Override
            public void onFinish() {
                binding.loginPinInputLayout.setError(null);
                setUiLoading(false);
            }
        }.start();
    }

    private void cancelTimer() {
        if (lockTimer != null) lockTimer.cancel();
    }

    private void setupBiometrics() {
// Controlla se l'utente ha abilitato la biometria e se è disponibile sul dispositivo
        if (authViewModel.isBiometricEnabled() && authViewModel.isBiometricAuthAvailable(requireContext())) {
            binding.loginBiometricButton.setVisibility(View.VISIBLE);
            Cipher cipher = getCipherForDecryption();
            if (cipher == null) return;

            Executor executor = ContextCompat.getMainExecutor(requireContext());
            biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull
                        CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
// L'utente ha annullato o c'è stato un errore non recuperabile
                            Toast.makeText(getContext(), "Errore di autenticazione: " +
                                    errString, Toast.LENGTH_SHORT).show();
                        }
                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            Cipher unlockedCipher = result.getCryptoObject().getCipher();
                            // TODO: Usare 'unlockedCipher' per decifrare la passphrase di SQLCipher
// Successo!
                            authViewModel.successfulBiometricLogin();
                        }
                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
// L'impronta/volto non è stato riconosciuto
                            Toast.makeText(getContext(), "Autenticazione fallita",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
            promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.biometric_prompt_title))
                    .setSubtitle("Accedi a SecureNotes")
                    .setNegativeButtonText("Usa PIN")
                    .build();
// Avvia il prompt biometrico automaticamente all'apertura della schermata
            biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));

        }
    }
    private void setUiLoading(boolean isLoading) {
        binding.loginButton.setEnabled(!isLoading);
        binding.loginBiometricButton.setEnabled(!isLoading);
        binding.loginPinEditText.setEnabled(!isLoading);
    }
    private void navigateToMainApp() {
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_loginFragment_to_main_app);
    }

    private static final class AuthViewModelFactory implements ViewModelProvider.Factory {
        private final PreferenceManager prefs;
        AuthVmFactory(PreferenceManager p) { prefs = p; }

        @NonNull @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> cls) {
            if (cls.isAssignableFrom(AuthViewModel.class)) {
                return (T) new AuthViewModel(prefs);
            }
            throw new IllegalArgumentException("Unknown ViewModel: " + cls);
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}