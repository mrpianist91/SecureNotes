package com.example.securenotes.feature_auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.transition.MaterialSharedAxis;
import com.example.securenotes.feature_auth.AuthViewModel;
import com.example.securenotes.feature_auth.R;
import com.example.securenotes.feature_auth.databinding.FragmentCreatePinBinding;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.core.content.ContextCompat;


public class CreatePinFragment extends Fragment {

    private FragmentCreatePinBinding binding;
    private AuthViewModel authViewModel;
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
// Imposta la transizione per quando si arriva dalla pagina precedente (benvenuto)
        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
// Imposta la transizione per quando si torna alla pagina precedente
        setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCreatePinBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        setupListeners();
        observeViewModel();
        setupPinStrengthChecker();//guarda sotto
    }

    private void setupPinStrengthChecker() {
        //TextWatcher Un TextWatcher è un "ascoltatore" che ti permette di reagire in tempo reale
        // a qualsiasi modifica del testo all'interno di un campo di input, come un EditText.
        binding.pinEditText.addTextChangedListener(new TextWatcher() {
            //beforeTextChanged() viene chiamato un istante prima che il testo venga modificato.
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            //onTextChanged() viene chiamato quando il testo viene modificato.
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

                if (s!= null) {
                    authViewModel.calculatePinStrength(s.toString());
                }
            }
           // afterTextChanged() viene chiamato dopo che il testo viene modificato.
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void observeViewModel() {

        //... osservatore per pinCreationState (cioè lo stato della creazione del PIN da parte dell'utente)

        authViewModel.getPinCreationState().observe(getViewLifecycleOwner(), state -> {
                    switch (state.getStatus()) {
                        case LOADING:
// Si potrebbe mostrare un indicatore di caricamento se necessario...setEnabled() indica se il pulsante è cliccabile o meno
                            binding.createPinButton.setEnabled(false);
                            break;
                        case SUCCESS:
// PIN creato con successo, chiedi all'utente di autenticarsi con la biometria
                            binding.createPinButton.setEnabled(true);
                            promptForBiometrics();
                            break;
                        case ERROR:
// Mostra errore di validazione
                            binding.createPinButton.setEnabled(true);
                            if (state.getError()!= null) {
                                binding.pinInputLayout.setError(state.getError());
                            }
                            break;
                        default:
                            binding.createPinButton.setEnabled(true);
                            binding.pinInputLayout.setError(null);
                    }
                });

// osservatore per la robustezza del PIN
        authViewModel.getPinStrengthState().observe(getViewLifecycleOwner(), strengthState -> {
                    binding.pinStrengthIndicator.setProgress(strengthState.getProgress());
                    binding.pinStrengthIndicator.setIndicatorColor(ContextCompat.getColor(requireContext(), strengthState.getColorRes()));
                    binding.pinStrengthLabel.setText(getString(R.string.pin_strength_label, strengthState.getLabel(requireContext())));
                });
    }

    private void promptForBiometrics() {
// Controlla se la biometria è disponibile
        if (authViewModel.isBiometricAuthAvailable(requireContext())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.biometric_prompt_title)
                    .setMessage(R.string.biometric_prompt_message)
                    .setPositiveButton(R.string.biometric_prompt_enable, (dialog, which) -> {
                        authViewModel.setBiometricEnabled(true);
                        navigateToMainApp();
                    })
                    .setNegativeButton(R.string.biometric_prompt_skip, (dialog, which) -> {
                        authViewModel.setBiometricEnabled(false);
                        navigateToMainApp();
                    })
                    .setCancelable(false)
                    .show();
        }
        else {
// Se la biometria non è disponibile, vai direttamente all'app
            navigateToMainApp();
        }
    }
    private void navigateToMainApp() {
// Naviga verso la dashboard principale, pulendo lo stack di autenticazione
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_onboardingHostFragment_to_main_app);
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

