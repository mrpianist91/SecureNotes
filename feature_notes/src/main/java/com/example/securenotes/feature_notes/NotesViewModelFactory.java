package com.example.securenotes.feature_notes;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.example.securenotes.core.NoteRepository;
public class NotesViewModelFactory implements ViewModelProvider.Factory {
    //NoteRepository è la dipendenza di cui fare injection nei ViewModel creati da questa factory.
    private final NoteRepository repository;

    public NotesViewModelFactory(NoteRepository repository) {
        this.repository = repository;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        //isAssignableFrom() controlla se la classe richiesta (modelClass) è NotesViewModel o una sua sottoclasse.
        if (modelClass.isAssignableFrom(NotesViewModel.class)) {
            //cast al tipo "T" di NotesViewModel, passando il repository come parametro.
            return (T) new NotesViewModel(repository);
        }
        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}