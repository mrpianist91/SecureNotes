package com.example.securenotes;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.securenotes.core.Event;
import com.example.securenotes.databinding.FragmentSettingsBinding; // Generato da Gradle
import com.google.android.material.snackbar.Snackbar;

public class SettingsFragment extends Fragment {

    private SettingsViewModel viewModel;

    // VIEW BINDING: Sostituisce tutti i findViewById
    private FragmentSettingsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate con Binding
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(SettingsViewModel.class);

        // --- INIZIALIZZAZIONE UI ---
        updateTimeoutText(viewModel.getCurrentTimeout());//inserisce il testo del timeout impostato in @+id/tv_timeout_value
        binding.switchBiometric.setChecked(viewModel.isBiometricEnabled());

        // --- LISTENERS (Click) ---
        //Al click del bottone del “Cambio Pin” e del “Backup”, non facciamo nulla se non chiamare il VM
        //Usiamo "binding.idDelComponente"

        binding.btnChangePin.setOnClickListener(v ->
                viewModel.onSensitiveActionClicked(SettingsViewModel.PendingAction.CHANGE_PIN));

        binding.btnBackup.setOnClickListener(v ->
                viewModel.onSensitiveActionClicked(SettingsViewModel.PendingAction.BACKUP));

        //mostra una Dialog che permette all’utente di scegliere il tempo di timeout
        binding.btnTimeout.setOnClickListener(v -> showTimeoutDialog());

        //Imposta il flag biometrico
        binding.switchBiometric.setOnCheckedChangeListener((v, isChecked) ->
                viewModel.setBiometricEnabled(isChecked));

        binding.btnRestore.setOnClickListener(v ->
                showMessage("Funzionalità Restore in arrivo"));

        // --- OBSERVERS (Reazione) ---

        // 1. Gestione Loading (Blocco UI durante Re-Wrap)
        viewModel.isLoading.observe(getViewLifecycleOwner(), isLoading -> {
            // Esempio: Disabilitiamo i click se sta caricando
            binding.btnChangePin.setEnabled(!isLoading);
            binding.btnBackup.setEnabled(!isLoading);
            // Se avessi una progressBar nel layout: binding.progressBar.setVisibility(...)
        });

        // 2. Messaggi "one-shot"(Snackbar)
        viewModel.statusMessage.observe(getViewLifecycleOwner(), event -> {
            String msg = event.getContentIfNotHandled();
            if (msg != null) showMessage(msg);
        });

        // 3. Richiesta Biometria (Step 1: Richiesta)
        viewModel.authRequest.observe(getViewLifecycleOwner(), event -> {
            SettingsViewModel.PendingAction action = event.getContentIfNotHandled();
            if (action != null) {
                launchBiometricPrompt(action);
            }
        });

        // 4. Esecuzione Azione (Step 2: Semaforo Verde)  <-- NUOVO!
        // Questo risponde alla tua domanda: Chi chiama dispatchAction?
        // Risposta: Lo chiama questo Observer quando il ViewModel dà l'OK.
        viewModel.actionToExecute.observe(getViewLifecycleOwner(), event -> {
            SettingsViewModel.PendingAction action = event.getContentIfNotHandled();
            if (action != null) {
                dispatchAction(action);
            }
        });
    }

    /**
     * Il "Semaforo Verde".
     * Viene chiamato SOLO quando l'utente è autorizzato (Biometria OK o assente).
     */
    private void dispatchAction(SettingsViewModel.PendingAction action) {
        switch (action) {
            case CHANGE_PIN:
                showOldPinDialog();
                break;
            case BACKUP:
                viewModel.triggerBackup();
                break;
        }
    }

    // --- BIOMETRIA ---
    private void launchBiometricPrompt(SettingsViewModel.PendingAction action) {
        java.util.concurrent.Executor executor = ContextCompat.getMainExecutor(requireContext());

        androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt(this, executor,
                new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        // Diciamo al VM: "Successo! Procedi con l'azione in sospeso"
                        viewModel.onBiometricSuccess(action);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            viewModel.onBiometricError(errString.toString());
                        }
                    }
                });

        androidx.biometric.BiometricPrompt.PromptInfo info = new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Conferma Identità")
                .setSubtitle("Autenticati per procedere")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Annulla")
                .build();

        prompt.authenticate(info);
    }

    // --- DIALOG ---

    private void showOldPinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Cambia PIN");
        builder.setMessage("Inserisci il VECCHIO PIN per decifrare il database:");

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);
        //(d,w) sono i parametri del metodo onClick() che implementiamo dell'interfaccia OnClickListener
        //"d" è la dialog stessa, "w" è l'ID del tasto premuto (potrebbe servire se avessimo più pulsanti)
        builder.setPositiveButton("Avanti", (d, w) -> {
            String oldPin = input.getText().toString();
            if (!oldPin.isEmpty()) showNewPinDialog(oldPin);
        });
        builder.setNegativeButton("Annulla", null);
        builder.show();
    }

    private void showNewPinDialog(String oldPin) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Nuovo PIN");
        builder.setMessage("Inserisci il NUOVO PIN (min 6 cifre):");

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Salva", (d, w) ->
                viewModel.changePin(oldPin, input.getText().toString()));
        builder.setNegativeButton("Annulla", null);
        builder.show();
    }

    /**
     * IMPLEMENTAZIONE RICHIESTA DEL TIMEOUT DIALOG
     */
    private void showTimeoutDialog() {
        // 1. Le opzioni visibili all'utente
        final String[] options = {"1 minuto", "3 minuti", "5 minuti", "10 minuti"};

        // 2. I valori reali in millisecondi corrispondenti
        final long[] values = {60_000L, 180_000L, 300_000L, 600_000L};

        // 3. Trova l'indice attuale per pre-selezionarlo nel dialog
        long current = viewModel.getCurrentTimeout();
        int selectedIndex = 1; // Default 3 min
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                selectedIndex = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Timeout Sessione");

        // setSingleChoiceItems crea una lista con i "radio button"
        builder.setSingleChoiceItems(options, selectedIndex, (dialog, which) -> {
            // "which" è l'indice cliccato (0, 1, 2, 3)
            long selectedValue = values[which];

            // Aggiorniamo il ViewModel
            viewModel.setSessionTimeout(selectedValue);

            // Aggiorniamo il testo nella UI
            updateTimeoutText(selectedValue);

            // Chiudiamo il dialog
            dialog.dismiss();
        });

        builder.setNegativeButton("Annulla", null);
        builder.show();
    }

    private void updateTimeoutText(long ms) {
        binding.tvTimeoutValue.setText((ms / 60000) + " min");
    }

    private void showMessage(String msg) {
        Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Evita Memory Leak del View Binding
    }
}
