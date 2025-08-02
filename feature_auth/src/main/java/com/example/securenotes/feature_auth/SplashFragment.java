/*La SplashFragment è il punto di ingresso dell'app. Il suo unico compito è decidere dove
  reindirizzare l'utente: al flusso di onboarding (se è il primo avvio) o alla schermata di login
  (se l'app è già stata configurata)*/

package com.example.securenotes.feature_auth;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.securenotes.feature_auth.AuthViewModel;
import com.example.securenotes.feature_auth.R;
import com.example.securenotes.feature_auth.databinding.FragmentSplashBinding;

public class SplashFragment extends Fragment {
    private FragmentSplashBinding binding;
    private AuthViewModel authViewModel;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSplashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
// Aggiungiamo un piccolo ritardo per non avere un flash immediato
        new Handler(Looper.getMainLooper()).postDelayed(this::checkAuthStatus,500);
    }
    private void checkAuthStatus() {
        if (authViewModel.isPinSet()) {
// L'utente ha già un PIN, vai al login
            NavHostFragment.findNavController(this)
                    .navigate(R.id.action_splashFragment_to_loginFragment);
        } else {
// Primo avvio, vai all'onboarding
            NavHostFragment.findNavController(this)
                    .navigate(R.id.action_splashFragment_to_onboardingHostFragment);
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}