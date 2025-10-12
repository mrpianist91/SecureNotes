package com.example.securenotes.feature_notes;
import androidx.lifecycle.LiveData;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.NoteDao;
import com.example.securenotes.core.NoteRepository;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * Implementazione concreta di NoteRepository.
 * Utilizza un single-thread Executor per operazioni di scrittura sul DB
 (inserimento, update, delete).
 */
public class NoteRepositoryImpl implements NoteRepository {

    private final NoteDao noteDao;
    private final ExecutorService executorService;
    public NoteRepositoryImpl(NoteDao noteDao) {
        this.noteDao = noteDao;
        this.executorService = Executors.newSingleThreadExecutor();
    }
    @Override
    public LiveData<List<Note>> getAllNotes() {
// Esegue pulizia note scadute prima di ottenere l'elenco completo
        executorService.execute(() -> {
            try {
                noteDao.deleteExpired(System.currentTimeMillis());
            } catch (Exception e) {
// Log dell'errore di pulizia (ad es. nessun DB aperto)
                e.printStackTrace();
            }
        });
        return noteDao.getAllNotes();
    }

    //Torna la lista delle note filtrate (con il tag e la stringa di “ricerca”)
    @Override
    public LiveData<List<Note>> filterNotes(String tag, String searchQuery) {
// Usa la query Room parametrica per ottenere LiveData filtrato.
// Passa il nome speciale per tag "Tutti" dalla classe Note.TAG_ALL.
        return noteDao.filterNotes(tag, Note.TAG_ALL, searchQuery != null ?
                searchQuery : "");
    }
    @Override
    public void addNote(final Note note) {
        executorService.execute(() -> {
            try {
                noteDao.insertNote(note);
            } catch (Exception e) {
// Possibile errore (es. violazione PK) -> loggare o gestire
                e.printStackTrace();
            }
        });
    }
    @Override
    public void updateNote(final Note note) {
        executorService.execute(() -> {
            try {
                noteDao.updateNote(note);
            } catch (Exception e) {
// Log/gestione errore (es. DB non disponibile)
                e.printStackTrace();
            }
        });
    }
    @Override
    public void deleteNote(final Note note) {
        executorService.execute(() -> {
            try {
                noteDao.deleteNoteById(note.id);
            } catch (Exception e) {
// Log/gestione errore in cancellazione
                e.printStackTrace();
            }
        });
    }
    @Override
    public void close() throws IOException {
        executorService.shutdown();
    }
}