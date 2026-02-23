package com.example.securenotes.feature_vault;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.securenotes.core.AuthManager;
import com.example.securenotes.core.SystemInteractionListener;
import com.example.securenotes.core.VaultFile;
import com.example.securenotes.core.VaultRepository;
import com.example.securenotes.feature_vault.databinding.FragmentVaultBinding;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.Executor;

public class VaultFragment extends Fragment {

    private FragmentVaultBinding binding;
    private VaultViewModel viewModel;
    private VaultAdapter adapter;

    // Listener per comunicare con l'Activity (Sessione)
    //private VaultInteractionListener interactionListener;

    // Listener per comunicare con l'Activity (Sessione)
    private SystemInteractionListener interactionListener;
    // Gestione Attach/Detach del Listener
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof SystemInteractionListener) {
            interactionListener = (SystemInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString() + " deve implementare VaultInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        interactionListener = null;
    }

    // Launcher importazione file
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    viewModel.importFile(result.getData().getData());
                }
                //Al ritorno, onStart() del SessionObserver troverà la sessione ancora valida (perché non invalidata in onStop) e riprenderà il timer.
            }
    );

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentVaultBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(VaultViewModel.class);

        setupAdapter();
        setupObservers();

        // Setup UI interattiva (PIN, Tastiera, Bottoni)
        setupAuthUiInteractions();

        // Inizializza il blocco di sicurezza (Gatekeeper)
        setupGatekeeper();
    }

    //GATEKEEPER & AUTH UI LOGIC

    private void setupGatekeeper() {
        if (!viewModel.isUnlocked()) {//!isUnlocked
            lockUiState(); // Stato iniziale: Tutto nascosto tranne Biometria/Bottone PIN
            // Avvio automatico del prompt biometrico per UX fluida
            launchBiometricAuth();
        } else {//questo ramo sussiste nel caso l'utente, dopo aver già sbloccato il vault passasse ad un altro tab (note/settings) e poi tornasse al vault.

            unlockUiState();
        }
    }

    //Configura i listener per la nuova UI unificata (simile a LoginFragment)
    private void setupAuthUiInteractions() {
        // 1. Observer risultato PIN (dal ViewModel che usa AuthManager)
        viewModel.pinResult.observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;

            if (result == AuthManager.AuthResult.SUCCESS) {
                //isUnlocked = true;
                // SALVIAMO LO STATO NEL VIEWMODEL
                viewModel.setUnlocked(true);
                hideKeyboard();
                unlockUiState();
                binding.etPin.setText(""); // Pulisci per sicurezza
            } else if (result == AuthManager.AuthResult.LOCKED) {
                binding.tvStatus.setText("Troppi tentativi. Riprova più tardi.");
                binding.etPin.setError("Bloccato");
            } else {
                binding.etPin.setError("PIN Errato");
                binding.etPin.requestFocus();
            }
            viewModel.resetPinResult();
        });

        // 2. Bottone "Usa PIN" (Fallback manuale)
        binding.btnUsePin.setOnClickListener(v -> showPinInputState());

        // 3. Bottone "Conferma" PIN
        binding.btnConfirmPin.setOnClickListener(v -> {
            String pin = binding.etPin.getText().toString();
            if (!pin.isEmpty()) {
                viewModel.verifyPin(pin);
            } else {
                binding.tilPin.setError("Inserisci il PIN");
            }
        });

        // 4. Gestione tastiera "Fatto/Invio"
        binding.etPin.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnConfirmPin.performClick();
                return true;
            }
            return false;
        });

        // 5. Bottone "Annulla" (Esce dal Vault)
        binding.btnCancelAuth.setOnClickListener(v -> {
            hideKeyboard();
            NavHostFragment.findNavController(this).popBackStack();
        });
    }

    private void launchBiometricAuth() {
        Executor executor = ContextCompat.getMainExecutor(requireContext());
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                //isUnlocked = true;
                // SALVIAMO LO STATO NEL VIEWMODEL
                viewModel.setUnlocked(true);
                unlockUiState();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                // Se l'utente annulla volontariamente o preme "Usa PIN"
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                    showPinInputState(); // Passa alla UI del PIN
                } else {
                    // Altri errori (es. troppi tentativi o lockout): mostra errore ma resta sull'opzione PIN
                    Toast.makeText(requireContext(), "Auth Errore: " + errString, Toast.LENGTH_SHORT).show();
                    showPinInputState();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // Fallimento biometrico (es. dito sbagliato): resta in ascolto, non cambiare UI
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Accesso al Vault")
                .setSubtitle("Autenticazione richiesta")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Usa PIN") // Coerente con Login
                .build();

        // Evita crash se biometria non disponibile
        try {
            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            showPinInputState();
        }
    }

    //STATO UI

    //Stato 1: Vault Bloccato, Biometria in corso o opzione "Usa PIN" visibile
    private void lockUiState() {
        binding.contentLayer.setVisibility(View.GONE);
        binding.authLayer.setVisibility(View.VISIBLE);

        // Reset UI Auth
        binding.tvStatus.setText("Vault Protetto");
        binding.btnUsePin.setVisibility(View.VISIBLE);
        binding.pinGroup.setVisibility(View.GONE);
        binding.etPin.setText("");
        binding.etPin.setError(null);
    }

    //Stato 2: Utente ha scelto "Usa PIN" o Bio non disponibile -> Mostra InputText e tastiera
    private void showPinInputState() {
        binding.contentLayer.setVisibility(View.GONE);
        binding.authLayer.setVisibility(View.VISIBLE);

        binding.tvStatus.setText("Inserisci PIN App");
        binding.btnUsePin.setVisibility(View.GONE); // Nascondi il trigger
        binding.pinGroup.setVisibility(View.VISIBLE); // Mostra il form

        // Focus e Tastiera
        binding.etPin.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(binding.etPin, InputMethodManager.SHOW_IMPLICIT);
    }

    //Stato 3: Sbloccato -> Mostra File
    private void unlockUiState() {
        binding.authLayer.setVisibility(View.GONE);
        binding.contentLayer.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        View view = getView();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }


    private void setupAdapter() {
        // Impostiamo l'adapter per gestire l'interazione dell'utente coi file nel RecyclerView
        // La lambda che passiamo al costruttore dell'adapter implementa il metodo onItemClick() dell'interfaccia OnItemClickListener definita nel costruttore di VaultAdapter.java
        adapter = new VaultAdapter(file -> viewModel.requestOpenFile(file));
        binding.rvVaultFiles.setAdapter(adapter);

        // Swipe per Delete
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) { return false; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                VaultFile file = adapter.getFileAt(viewHolder.getAdapterPosition());
                viewModel.deleteFile(file);
                Snackbar.make(binding.getRoot(), "File eliminato", Snackbar.LENGTH_SHORT).show();
            }
        }).attachToRecyclerView(binding.rvVaultFiles);

        // FAB Import
        binding.fabAddFile.setOnClickListener(v -> {
            // AVVISA IL SESSION OBSERVER DI NON BLOCCARE L'APP DURANTE LA SCHERMATA DI IMPORTAZIONE
            if (interactionListener != null) {
                interactionListener.onSystemInteraction();
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });
    }

    private void setupObservers() {
        viewModel.files.observe(getViewLifecycleOwner(), files -> adapter.submitList(files));

        viewModel.isLoading.observe(getViewLifecycleOwner(), loading ->
                binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));

        viewModel.viewFileEvent.observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) {
                // ANCHE QUI serve avvisare il Session observer, perché ACTION_VIEW apre un'app esterna
                if (interactionListener != null) {
                    interactionListener.onSystemInteraction();
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, requireContext().getContentResolver().getType(uri));
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Nessuna app per aprire questo file", Toast.LENGTH_LONG).show();
                }
                viewModel.onFileViewed();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        VaultRepository.clearTempCache(requireContext());
        binding = null;
    }
}