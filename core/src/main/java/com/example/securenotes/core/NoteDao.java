package com.example.securenotes.core;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;
@Dao
public interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    LiveData<List<Note>> getAllNotes(); // Espone un LiveData che si aggiorna automaticamente

    @Insert
    void insertNote(Note note);

// Altri metodi (update, delete, etc.) verranno aggiunti qui
}