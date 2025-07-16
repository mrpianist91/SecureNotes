package com.example.securenotes.feature_notes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.NoteRepository;
import java.io.IOException;
import java.util.List;
public class NotesViewModel extends ViewModel {
    private final NoteRepository repository;
    public final LiveData<List<Note>> notes;
    public NotesViewModel(NoteRepository repository) {
        this.repository = repository;
        this.notes = repository.getAllNotes();
    }

    public void addNote(Note note) {
    repository.addNote(note);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        try {
            repository.close();
        } catch (IOException e) {
        // Log o gestisci l'exception
            e.printStackTrace();
        }
    }
}