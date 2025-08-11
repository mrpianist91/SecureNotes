package com.example.securenotes.feature_auth;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import com.example.securenotes.feature_auth.databinding.FragmentCreatePinBinding;
import com.google.android.material.progressindicator.LinearProgressIndicator;

public class CreatePinFragment extends Fragment {
    private FragmentCreatePinBinding binding;
    private AuthViewModel authViewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCreatePinBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);

        // Aggiorna barra di robustezza ad ogni digitazione del PIN
        binding.etPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String pin = s.toString();
                int strength = authViewModel.calculatePinStrength(pin);
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
            }
        });

        // Verifica in tempo reale la corrispondenza tra PIN e Conferma PIN
        binding.etConfirmPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String pin = binding.etPin.getText().toString();
                String confirm = s.toString();
                if (!confirm.equals(pin)) {
                    binding.etConfirmPin.setError("I PIN non coincidono");
                } else {
                    binding.etConfirmPin.setError(null);
                }
            }
        });

        // Pulsante per creare/aggiornare il PIN
        binding.btnCreatePin.setOnClickListener(v -> {
            String pin = binding.etPin.getText().toString().trim();
            String confirm = binding.etConfirmPin.getText().toString().trim();
            if (pin.length() < 4) {
                Toast.makeText(requireContext(), "PIN troppo corto (minimo 4 cifre)", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!pin.equals(confirm)) {
                Toast.makeText(requireContext(), "I PIN inseriti non coincidono", Toast.LENGTH_SHORT).show();
                return;
            }
            // Salva il PIN in modo sicuro tramite ViewModel
            authViewModel.createPin(pin);
        });

        // Osserva l'esito della creazione PIN
        authViewModel.getPinCreated().observe(getViewLifecycleOwner(), success -> {
            if (success == null) return;
            if (success) {
                NavController nav = NavHostFragment.findNavController(CreatePinFragment.this);
                if (authViewModel.wasPinExisting()) {
                    // PIN modificato durante sessione -> invalida sessione e torna al login
                    Toast.makeText(requireContext(), "PIN modificato con successo. Esegui di nuovo l'accesso.", Toast.LENGTH_LONG).show();
                    nav.navigate(R.id.action_createPinFragment_to_loginFragment, null,
                            new NavOptions.Builder().setPopUpTo(R.id.auth_graph, true).build());
                } else {
                    // Primo PIN creato (onboarding) -> naviga alla schermata principale
                    Toast.makeText(requireContext(), "PIN creato! Benvenuto/a su SecureNotes.", Toast.LENGTH_SHORT).show();
                    nav.navigate(R.id.action_createPinFragment_to_notesListFragment, null,
                            new NavOptions.Builder().setPopUpTo(R.id.auth_graph, true).build());
                }
            } else {
                Toast.makeText(requireContext(), "Errore durante la creazione del PIN", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
