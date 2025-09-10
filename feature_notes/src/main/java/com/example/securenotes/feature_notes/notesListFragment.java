package com.example.securenotes.feature_notes;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.example.securenotes.core.AppDatabase;
import com.example.securenotes.core.NoteRepository;
import com.example.securenotes.core.SecurityUtils;

public class notesListFragment extends Fragment {
    private NotesViewModel viewModel;

    public notesListFragment() {
// Costruttore pubblico vuoto richiesto da Fragment
    }
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return new View(getContext()); // Placeholder view
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // --- Inizializzazione delle dipendenze (temporanea) ---
        // In un'app reale, questo verrebbe gestito da un framework di Dependency Injection come Hilt.
        byte [] passphrase = SecurityUtils.generateRandom(32);
        AppDatabase database = AppDatabase.getDatabase(requireContext(), passphrase);
        NoteRepository repository = new NoteRepositoryImpl(database.noteDao());
        NotesViewModelFactory factory = new NotesViewModelFactory(repository);
// --- Fine inizializzazione dipendenze ---
        viewModel = new ViewModelProvider(this, factory).get(NotesViewModel.class);
// Ora possiamo osservare i dati dal ViewModel
        viewModel.notes.observe(getViewLifecycleOwner(), notes -> {
// Qui si aggiornerà la UI (es. l'adapter del RecyclerView)
// quando la lista di note cambia.
        });
    }
}
