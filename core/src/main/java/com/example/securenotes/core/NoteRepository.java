package com.example.securenotes.core;
import androidx.lifecycle.LiveData;
import java.io.Closeable;
import java.util.List;
public interface NoteRepository extends Closeable {
    LiveData<List<Note>> getAllNotes();
    void addNote(Note note);
}