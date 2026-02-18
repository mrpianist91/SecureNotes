package com.example.securenotes;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.work.Data;

import com.example.securenotes.core.SystemInteractionListener;
import com.example.securenotes.backup_worker.BackupWorker;
// Importa AuthViewModel per passare il vecchio PIN
import com.example.securenotes.feature_auth.AuthViewModel;
import com.example.securenotes.core.AuthManager;
import com.example.securenotes.core.Event;
import com.example.securenotes.databinding.FragmentSettingsBinding; // Generato da Gradle
import com.google.android.material.snackbar.Snackbar;

public class SettingsFragment extends Fragment {

    private SettingsViewModel viewModel;

    // VIEW BINDING: Sostituisce tutti i findViewById
    private FragmentSettingsBinding binding;
    private AuthViewModel authViewModel; //Per condividere stato col modulo Auth
    //Launcher per il SAF (File picker per "Creazione File Backup")
    private ActivityResultLauncher<Intent> exportLauncher;

    // Listener per segnalare l'azione esterna
    private SystemInteractionListener interactionListener;

    //GESTIONE LISTENER (DEPENDENCY INVERSION) per evitare che al ritorno dal file picker, l'app venga interrotta
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof SystemInteractionListener) {
            interactionListener = (SystemInteractionListener) context;
        } else {
            // Nota: Non crashiamo qui se non è strettamente obbligatorio per tutto,
            // ma PER IL BACKUP E' NECESSARIO.
            // throw new RuntimeException(context.toString() + " deve implementare SystemInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        interactionListener = null;
    }
    // Launcher per la richiesta del permesso notifiche
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // Indipendentemente dall'esito, procediamo con il SAF.
                // Se l'utente nega, il backup si farà lo stesso ma senza notifica: non blocchiamo una feature critica (backup) per un permesso accessorio (notifica).
                openSafFilePicker();
            });

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

        // Otteniamo AuthViewModel con scope Activity per condividere i dati col CreatePinFragment
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        //Setup del Launcher
        exportLauncher = registerForActivityResult(//primo argomento indica “cosa si vuole con l’Intent” (risposta, “avviare un’activity che torna un risultato”); il secondo argomento invece indica la gestione del risultato della richiesta
                new ActivityResultContracts.StartActivityForResult(),
                result -> {//logica di gestione del risultato tornato dal sistema alla richiesta di creazione (salvataggio) di un file (il nostro backup)
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();//estrae l’URI del file su cui scriveremo il backup
                        if (uri != null) {
                            // Chiedi password backup (Dialog) -> poi avvia worker
                            showBackupPasswordDialog(uri);
                        }
                    }
                    // Al rientro, SessionObserver vedrà che era un'azione autorizzata e non invaliderà la sessione.
                }
        );


        // INIZIALIZZAZIONE UI
        updateTimeoutText(viewModel.getCurrentTimeout());//inserisce il testo del timeout impostato in @+id/tv_timeout_value
        binding.switchBiometric.setChecked(viewModel.isBiometricEnabled());

        //LISTENERS (ClickListenr)
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

        //OBSERVERS (Reazioni)

        // 1. Gestione Loading (Blocco UI durante Re-Wrap) e blocca bottone Backup se Worker in corso
        viewModel.isLoading.observe(getViewLifecycleOwner(), isLoading -> {
            // Esempio: Disabilitiamo i click se sta caricando
            binding.btnChangePin.setEnabled(!isLoading);
            binding.btnBackup.setEnabled(!isLoading);
            // Se avessi una progressBar nel layout: binding.progressBar.setVisibility(...)
        });

        // 2. Messaggi "one-shot" (Snackbar)
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

        // 4. Esecuzione Azione (Step 2: Auth superata -> Esegui logica)
        // Chi chiama dispatchAction? Lo chiama questo Observer quando il ViewModel dà l'OK.
        viewModel.actionToExecute.observe(getViewLifecycleOwner(), event -> {
            SettingsViewModel.PendingAction action = event.getContentIfNotHandled();
            if (action != null) {
                dispatchAction(action);
            }
        });
        //Auth Fallback - Observer del risultato verifica PIN
        viewModel.authPinResult.observe(getViewLifecycleOwner(), event -> {
            AuthManager.AuthResult result = event.getContentIfNotHandled();
            if (result == null) return;

            if (result == AuthManager.AuthResult.SUCCESS) {
                // Autenticazione riuscita! Procediamo COME SE la biometria fosse passata.
                // Usiamo il metodo del VM per rientrare nel flusso standard
                viewModel.onBiometricSuccess(SettingsViewModel.PendingAction.BACKUP);
            } else if (result == AuthManager.AuthResult.LOCKED) {
                showMessage("Troppi tentativi. Riprova più tardi.");
            } else {
                showMessage("PIN Errato.");
                // Opzionale: Riaprire la dialog? Per ora chiudiamo e mostriamo errore.
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
                checkNotificationPermissionAndBackup();
                // Step 2: Auth OK -> Apri File Picker (SAF)
                //openSafFilePicker();
                //viewModel.triggerBackup();
                break;
        }
    }

    private void checkNotificationPermissionAndBackup() {
        // Il permesso serve solo da Android 13 (API 33) in poi
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                // Permesso già concesso, procedi
                // Step 2: Auth OK -> Apri File Picker (SAF)
                openSafFilePicker();
            } else {
                // Chiedi il permesso. La callback del launcher chiamerà openSafFilePicker() alla fine.
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // Android < 13: Permesso concesso all'installazione
            // Step 2: Auth OK -> Apri File Picker (SAF)
            openSafFilePicker();
        }
    }

    private void openSafFilePicker() {
        // Apri SAF (file picker) per permettere all’utente dove salvare il file di backup
        // 1. AVVISIAMO IL SESSION OBSERVER (tramite Activity)
        if (interactionListener != null) {
            interactionListener.onSystemInteraction();
        }
        // 2. Creazione dell'Intent (Voglio creare un documento) e salvarlo
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        //il file deve essere apribile
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        //il file sarà un .zip
        intent.setType("application/zip"); // o "application/octet-stream"
        //suggeriamo il nome del backup
        intent.putExtra(Intent.EXTRA_TITLE, "securenotes_backup.snbackup");
        //eseguiamo l’Intent tramite il launcher definito sopra
        exportLauncher.launch(intent);

    }

    // Metodo Dialog Password
    private void showBackupPasswordDialog(Uri destUri) {
//Creiamo il campo di input per inserire la password del backup
        EditText input = new EditText(requireContext());
//il testo inserito sarà generico (alfanumerico)
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);//Il testo verrà “nascosto” dai pallini
        //Costruiamo la dialog
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Imposta Password Backup")
                .setMessage("Questa password servirà per ripristinare i dati. Non dimenticarla!")
                .setView(input)//injection dell’input nella view
                .setPositiveButton("Avvia", (d, w) -> {//(d,w) sono i parametri del metodo onClick() che implementiamo dell'interfaccia OnClickListener
//"d" è la dialog stessa, "w" è l'ID del tasto premuto (potrebbe servire se avessimo più pulsanti)

                    String pwd = input.getText().toString();//prendiamo la password digitata dall’utente dall’EditText
                    if (pwd.length() < 8) {
                        Toast.makeText(requireContext(), "Password troppo corta (min 8 char)", Toast.LENGTH_SHORT).show();
                    } else {
                        // Step 4 Finale: Costruisci Dati e chiama ViewModel
                        Data inputData = new Data.Builder()
                                .putString(BackupWorker.KEY_URI, destUri.toString())
                                .putString(BackupWorker.KEY_PASSWORD, pwd)
                                .build();

                        // Il metodo triggerBackup lancia il Worker -> ENQUEUED -> isLoading=true
                        viewModel.triggerBackup(inputData);
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }


    //BIOMETRIA
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
                        /*if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            viewModel.onBiometricError(errString.toString());
                        }*/
                        // Auth Fallback - Gestione bottone negativo ("Usa PIN")
                        if (errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            // Se l'azione era il Backup, mostriamo il dialog di autenticazione PIN.
                            // Se era Change PIN, non serve (ha già il suo flusso old pin).
                            if (action == SettingsViewModel.PendingAction.BACKUP) {
                                showPinAuthDialog();
                            }
                        } else if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED) {
                            viewModel.onBiometricError(errString.toString());
                        }
                    }
                });

        androidx.biometric.BiometricPrompt.PromptInfo.Builder info = new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Conferma Identità")
                .setSubtitle("Autenticati per procedere")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG);
                //.setNegativeButtonText("Annulla")
                //.build();
        // Se l'azione è BACKUP, mostriamo il bottone "Usa PIN" (Negative Button)
        // Se è CHANGE_PIN, usiamo "Annulla" perché il Change PIN ha già la sua logica di richiesta vecchio pin separata.
        if (action == SettingsViewModel.PendingAction.BACKUP) {
            info.setNegativeButtonText("Usa PIN");
        } else {
            info.setNegativeButtonText("Annulla");
        }
        prompt.authenticate(info.build());
    }

    //DIALOG

    //Auth Fallback - Dialog per inserimento PIN di autenticazione (CASO "Esporta Backup")
    private void showPinAuthDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Autenticazione");
        builder.setMessage("Inserisci il PIN dell'app per procedere con il backup:");

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Conferma", (d, w) -> {
            String pin = input.getText().toString();
            if (!pin.isEmpty()) {
                // Passiamo il PIN al ViewModel per la verifica sicura
                viewModel.verifyAuthPin(pin);
            }
        });
        builder.setNegativeButton("Annulla", null);
        builder.show();
    }

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
            if (!oldPin.isEmpty()) //showNewPinDialog(oldPin);
            {
                // Approccio pragmatico "Minimal Changes":
                // Riutilizziamo la verifica sincrona (veloce per hash) o deleghiamo al AuthViewModel activity-scoped.
                // Poiché il flusso cambia pagina, usiamo AuthManager direttamente qui per semplicità di flusso immediato.

                AuthManager.AuthResult result = AuthManager.getInstance(requireContext()).verifyPin(oldPin);
                if (result == AuthManager.AuthResult.SUCCESS) {
                    // 1. Salva vecchio PIN nel ViewModel condiviso (Activity Scope)
                    authViewModel.setTempOldPinForChange(oldPin);

                    // 2. Naviga verso CreatePinFragment in modalità Change
                    // Nota: Usa la classe generata SettingsFragmentDirections se usi SafeArgs,
                    // altrimenti Bundle manuale. Qui uso Bundle manuale per coerenza Java base.
                    Bundle args = new Bundle();
                    args.putBoolean("isChangeMode", true);
// 2. Naviga verso CreatePinFragment usando Safe Args
                    // La classe 'SettingsFragmentDirections' viene generata automaticamente alla build
                    /*SettingsFragmentDirections.ActionSettingsFragmentToCreatePinFragment action =
                            SettingsFragmentDirections.actionSettingsFragmentToCreatePinFragment();

                    action.setIsChangeMode(true);*/

                    // Eseguiamo la navigazione passando l'oggetto 'action' invece dell'ID e del Bundle
                    //NavHostFragment.findNavController(this).navigate(action);
                    NavHostFragment.findNavController(this)
                            .navigate(R.id.action_settingsFragment_to_createPinFragment, args);

                } else {
                    showMessage("PIN errato");
                }

            }
        });
        builder.setNegativeButton("Annulla", null);
        builder.show();
    }

    /*private void showNewPinDialog(String oldPin) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Nuovo PIN");
        builder.setMessage("Inserisci il NUOVO PIN (min 8 cifre):");

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Salva", (d, w) ->
                viewModel.changePin(oldPin, input.getText().toString()));
        builder.setNegativeButton("Annulla", null);
        builder.show();
    }*/
    /**
     * IMPLEMENTAZIONE DEL TIMEOUT DIALOG
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
