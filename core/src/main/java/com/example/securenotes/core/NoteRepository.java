package com.example.securenotes.core;
import androidx.lifecycle.LiveData;
import java.io.Closeable;
import java.util.List;
/**
* Interfaccia Repository per gestire le note (astrazione del data source).
 */
 public interface NoteRepository extends Closeable {
    LiveData<List<Note>> getAllNotes();
    LiveData<List<Note>> filterNotes(String tag, String searchQuery);
    void addNote(Note note);
    void updateNote(Note note);
    void deleteNote(Note note);
}