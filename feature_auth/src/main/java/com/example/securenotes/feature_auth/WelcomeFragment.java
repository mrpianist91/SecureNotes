/*
La classe Java per questo Fragment è molto semplice, si
occupa solo di inflare il layout e impostare le transizioni come da linee guida Motion.*/

package com.example.securenotes.feature_auth;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.transition.MaterialSharedAxis;
import com.example.securenotes.feature_auth.databinding.FragmentWelcomeBinding;

public class WelcomeFragment extends Fragment {

    private FragmentWelcomeBinding binding;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

// Le seguenti istruzioni fanno parte del sistema di transizioni "material Motion" di Google, legato a Materia 3
// Imposta la transizione per quando si passa alla pagina successiva (creazione PIN), che deve avvenire sull'asse delle ascisse (x)
// "MaterialSharedAxis" è il nome della transizione, e il secondo parametro (true) indica
// una navigazione "in avanti". L'effetto visivo sarà che il WelcomeFragment scivola e si dissolve verso sinistra, mentre il nuovo CreatePinFragment entra dalla destra.    

        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));

// Imposta la transizione per quando si torna a questa pagina dalla successiva. Il "false"
// definisce una navigazione "all'indietro" ⬅️. L'effetto visivo è l'opposto del precedente:
// il WelcomeFragment riapparirà scivolando e apparendo da sinistra, come se si stesse "riavvolgendo" la navigazione.

        setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        binding = FragmentWelcomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}