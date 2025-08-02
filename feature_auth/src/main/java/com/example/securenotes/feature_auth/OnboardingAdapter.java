package com.example.securenotes.feature_auth;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.example.securenotes.feature_auth.CreatePinFragment;
import com.example.securenotes.feature_auth.WelcomeFragment;

public class OnboardingAdapter extends FragmentStateAdapter {

    private static final int NUM_PAGES = 2;

    public OnboardingAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }
    @NonNull
    @Override
    public Fragment createFragment(int position) {
// Restituisce il fragment corretto in base alla posizione
        switch (position) {
            case 0:
                return new WelcomeFragment();
            case 1:
                return new CreatePinFragment();
            default:
// Dovrebbe essere irraggiungibile
                throw new IllegalStateException("Invalid position: " +
                        position);
        }
    }
    @Override
    public int getItemCount() {
// Il numero totale di pagine nell'onboarding
        return NUM_PAGES;
    }
}