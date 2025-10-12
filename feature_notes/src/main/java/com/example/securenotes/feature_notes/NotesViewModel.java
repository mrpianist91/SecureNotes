package com.example.securenotes.feature_notes;
import android.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.NoteRepository;
import java.io.IOException;
import java.util.List;
/**
 * ViewModel per la lista di note.
 * Mantiene l’elenco LiveData delle note filtrate in base a tag selezionato e
 stringa di ricerca.
 */
public class NotesViewModel extends ViewModel {
    private final NoteRepository repository;
    // LiveData non filtrato (tutte le note) – non esposto pubblicamente
    private final LiveData<List<Note>> allNotes;
    // Filtri correnti: tag selezionato e query di ricerca
    private final MutableLiveData<String> selectedTag = new MutableLiveData<>(Note.TAG_ALL);
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");

    // LiveData combinato di (tag, query) che attiva la query filtrata
    /* LiveData<T>: stream osservabile, che conosce il ciclo di vita; espone solo observe(...). È read-only per chi lo riceve (UI).
     * MutableLiveData<T>: sottotipo che consente anche setValue/postValue, quindi scrivibile dall'owner (es. ViewModel).
     * MediatorLiveData<T>: un LiveData che può ascoltare più LiveData sorgenti (Pair<String, String>) e riemettere un valore proprio, quando una qualsiasi delle sorgenti cambia. In pratica, è un “combine” per LiveData.
     * Perché MediatorLiveData qui?
     * Perché i parametri di filtro sono due e indipendenti (tag e query). Usando MediatorLiveData, il ViewModel accoppia i valori correnti e li emette come singolo stato coerente, su cui poi si fa lo switchMap verso la query del repository.
     */
    private final MediatorLiveData<Pair<String, String>> filterParams = new MediatorLiveData<>();
    // LiveData note filtrate risultante
    public final LiveData<List<Note>> notes;
    // (Opzionale) nota attualmente selezionata per editing (usata se si condivide VM tra fragment)
    private Note currentNote;
    public NotesViewModel(NoteRepository repository) {
        this.repository = repository;
// Ottiene LiveData di tutte le note (triggera anche la cancellazione note scadute)
        this.allNotes = repository.getAllNotes();
// Imposta inizialmente i parametri di filtro
        filterParams.setValue(new Pair<>(Note.TAG_ALL, "")); // tag default "Tutti", ricerca vuota
// Aggiorna i parametri di filtro quando cambia il tag selezionato o la query
        filterParams.addSource(selectedTag, tag -> {
            String query = searchQuery.getValue() != null ? searchQuery.getValue() : "";
            filterParams.setValue(new Pair<>(tag, query));
        });
        filterParams.addSource(searchQuery, query -> {
            String tag = selectedTag.getValue() != null ? selectedTag.getValue() : Note.TAG_ALL;
            filterParams.setValue(new Pair<>(tag, query));
        });
// Usa switchMap sui parametri combinati per ottenere LiveData filtrato dal repository
                notes = Transformations.switchMap(filterParams, params ->
                repository.filterNotes(params.first, params.second)
        );
    }
    /** Imposta un nuovo tag filtro (trigger filtraggio LiveData). */
    public void setSelectedTag(String tag) {
        selectedTag.setValue(tag);
    }

    /** Imposta una nuova stringa di ricerca (trigger filtraggio LiveData).*/
     public void setSearchQuery(String query) {
     searchQuery.setValue(query);
     }

     /** Ritorna il tag attualmente selezionato. */
    public String getSelectedTag() {

        return selectedTag.getValue() != null ? selectedTag.getValue() : Note.TAG_ALL;

    }
/** Aggiunge una nuova nota (persistenza su DB). */
    public void addNote(Note note) {
        repository.addNote(note);
    }
/** Richiede aggiornamento di una nota esistente (persistenza). */
    public void updateNote(Note note) {
                repository.updateNote(note);
    }
/** Cancella la nota specificata (persistenza). */
    public void deleteNote(Note note) {
        repository.deleteNote(note);
    }
/** Salva in ViewModel la nota attualmente selezionata per l'editor. */
    public void setCurrentNote(Note note) {
                        this.currentNote = note;
    }

    /** Restituisce la nota selezionata attualmente in memoria (se presente).
     */
    public Note getCurrentNote() {
        return currentNote;
    }

    /**
     * Ricerca nelle note (già caricate) una nota per ID.
     * fa il returno della Nota trovata con l'ID specificato, o null se non trovata.
     */
    public Note findNoteById(String noteId) {
        List<Note> list = notes.getValue();
        if (list != null) {
            for (Note n : list) {
                if (n.id.equals(noteId)) {
                    return n;
                }
            }
        }
        return null;
    }
    @Override
    protected void onCleared() {
        super.onCleared();
        try {
            repository.close(); // Chiude executor thread
        } catch (IOException e) {
// Log dell'exception durante la chiusura (best effort)
            e.printStackTrace();
        }
    }
}