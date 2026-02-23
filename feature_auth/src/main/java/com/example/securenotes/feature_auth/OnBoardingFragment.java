package com.example.securenotes.feature_auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.viewpager2.widget.ViewPager2;

import com.example.securenotes.feature_auth.databinding.FragmentOnboardingBinding;

/**
 * Fragment “contenitore” dell’on-boarding:
 * ospita un ViewPager2 a schermo intero con le slide e
 * mostra i pulsanti Skip / Avanti-Inizia.
 *
 * — Usa ViewBinding (FragmentOnboardingBinding).
 * — Salta automaticamente l’onboarding se il PIN è già stato creato.
 */
public class OnBoardingFragment extends Fragment {

    //ViewBinding
    private FragmentOnboardingBinding binding;

    //MVVM
    private AuthViewModel authViewModel;

    //Adapter per il ViewPager2
    private OnboardingPagerAdapter pagerAdapter;


    //  Ciclo di vita

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        binding = FragmentOnboardingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View root,
                              @Nullable Bundle savedInstanceState) {

        super.onViewCreated(root, savedInstanceState);

        /*ViewModel condiviso con l’Activity (Auth flow) */
        authViewModel = new ViewModelProvider(requireActivity())
                .get(AuthViewModel.class);

        /*Inizializza il ViewPager2 e l’adapter*/
        pagerAdapter = new OnboardingPagerAdapter(this);
        binding.viewPagerOnboarding.setAdapter(pagerAdapter);

        /*Setup pulsanti Skip / Next*/
        NavController navController = NavHostFragment.findNavController(this);
    // se l'utente fa "Skip", si va alla creazione del pin
        binding.btnSkip.setOnClickListener(v ->
                navController.navigate(R.id.action_onBoardingFragment_to_createPinFragment));
    //se l'utente preme "Next", si passa alla slide successiva, o, se è l'ultima slide, si va alla creazione del pin
        binding.btnNext.setOnClickListener(v -> {
            int cur = binding.viewPagerOnboarding.getCurrentItem();
            if (cur < pagerAdapter.getItemCount() - 1) {
                binding.viewPagerOnboarding.setCurrentItem(cur + 1);
            } else {
                navController.navigate(R.id.action_onBoardingFragment_to_createPinFragment);
            }
        });

        //Cambia testo “Avanti” → “Inizia” all’ultima slide
        //se la pagina/slide visualizzata è l'ultima allora cambia il testo di btnNext "Avanti" con "Inizia", e fa sparire il btnSkip
        //il controllo viene fatto tramite indice/position della slide mostrata
        binding.viewPagerOnboarding.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        super.onPageSelected(position);
                        boolean last = position == pagerAdapter.getItemCount() - 1;
                        binding.btnNext.setText(last ? R.string.onboarding_start : R.string.onboarding_next);
                        binding.btnSkip.setVisibility(last ? View.GONE : View.VISIBLE);
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; //per evitare i memory-leaks
    }


    // Adapter per le pagine di onboarding
    private static class OnboardingPagerAdapter extends androidx.viewpager2.adapter.FragmentStateAdapter {
        // Contenuto statico delle slide (titoli e descrizioni)
        private final String[] titles = {
                "Benvenuto su SecureNotes",
                "Sicurezza ai massimi livelli",
                "Tutto sotto controllo"
        };
        private final String[] descriptions = {
                "Prendi appunti in modo sicuro con crittografia end-to-end.",
                "Proteggi l'accesso con PIN e impronta digitale.",
                "Le tue note e i files più importanti sono al sicuro. Possibilità di Backup cifrati."
        };
        // (Nota: potremmo anche avere riferimenti a immagini da mostrare per ogni slide)

        public OnboardingPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }
        @NonNull
        @Override
        public Fragment createFragment(int position) {
            // Crea un fragment per la pagina (slide) di onboarding specifica
            OnboardingPageFragment page = new OnboardingPageFragment();
            Bundle args = new Bundle();
            args.putInt("position", position);
            args.putString("title", titles[position]);
            args.putString("desc", descriptions[position]);
            page.setArguments(args);
            return page;
        }
        @Override
        public int getItemCount() {
            return titles.length;
        }
    }

    // Fragment per una singola pagina di onboarding
    public static class OnboardingPageFragment extends Fragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            return inflater.inflate(R.layout.onboarding_page, container, false);
        }
        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            Bundle args = getArguments();
            if (args != null) {
                ((android.widget.TextView) view.findViewById(R.id.tv_onboarding_title))
                        .setText(args.getString("title", ""));
                ((android.widget.TextView) view.findViewById(R.id.tv_onboarding_desc))
                        .setText(args.getString("desc", ""));

            }
        }
    }
}
