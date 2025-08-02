package com.example.securenotes.feature_auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;
import com.example.securenotes.feature_auth.databinding.FragmentOnboardingHostBinding;


public class OnBoardingHostFragment extends Fragment {
    private FragmentOnboardingHostBinding binding;
    private OnboardingAdapter onboardingAdapter;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable
    ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentOnboardingHostBinding.inflate(inflater, container, false);
        return binding.getRoot();//ritorna la root view del fragment (il ConstraintLayout)
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupViewPager();
    }
    private void setupViewPager() {
        /*'OnboardingAdapter è un adattatore che funge da "ponte" tra una lista di dati
          (in questo caso, i Fragment di onboarding) e il componente grafico ViewPager2 che li deve visualizzare.
          Il suo compito è dire al ViewPager2 quale Fragment mostrare per ogni specifica pagina.*/
        onboardingAdapter = new OnboardingAdapter(this);
        //onboardingViewPager è l'oggetto "public final" del binder, associato all'id del view pager (guarda xml)
        binding.onboardingViewPager.setAdapter(onboardingAdapter);
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        /* 5. CRITICO: Rilasciare il riferimento al binding.
              Il ciclo di vita della view di un Fragment è separato da quello del Fragment stesso.
              La vista può essere distrutta (es. quando l'app va in background), ma l'oggetto Fragment
              può rimanere in memoria. Se non si imposta 'binding' a null, si crea un memory leak,
              perché il binding terrebbe un riferimento a una gerarchia di viste non più esistente.
*/
        binding = null;
    }
}
