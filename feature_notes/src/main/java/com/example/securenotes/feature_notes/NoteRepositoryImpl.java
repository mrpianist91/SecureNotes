package com.example.securenotes.feature_notes;
import androidx.lifecycle.LiveData;
import com.example.securenotes.core.Note;
import com.example.securenotes.core.NoteDao;
import com.example.securenotes.core.NoteRepository;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class NoteRepositoryImpl implements NoteRepository {
    private final NoteDao noteDao;
    private final ExecutorService executorService;
    public NoteRepositoryImpl(NoteDao noteDao) {
        this.noteDao = noteDao;
        this.executorService = Executors.newSingleThreadExecutor();
    }
    @Override
    public LiveData<List<Note>> getAllNotes() {
        return noteDao.getAllNotes();
    }
    @Override
    public void addNote(Note note) {
        executorService.execute(() -> noteDao.insertNote(note));
    }
    @Override
    public void close() throws IOException {
        executorService.shutdown();
    }
}