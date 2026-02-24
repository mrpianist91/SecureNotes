package com.example.securenotes.core;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;
@Dao
public interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    LiveData<List<Note>> getAllNotes();
    @Query("DELETE FROM notes WHERE expiresAt != 0 AND expiresAt < :now")
    void deleteExpired(long now);
    @Insert
    void insertNote(Note note);
    @Update
    void updateNote(Note note);
    @Query("DELETE FROM notes WHERE id = :id")
    void deleteNoteById(String id);
    @Query("SELECT * FROM notes WHERE id = :id")
    Note getNoteById(String id);
    @Query("SELECT * FROM notes")
    List<Note> getAllNotesSync();


    // Query filtrata per tag e testo di ricerca (ricerca anche nell'HTML del body)
    @Query("SELECT * FROM notes " +
            "WHERE (:tag = :allTag OR tag = :tag) " +
            "AND (:search = '' OR title LIKE '%' || :search || '%' OR body LIKE '%' || :search || '%') " +
            "ORDER BY createdAt DESC")
    LiveData<List<Note>> filterNotes(String tag, String allTag, String
            search);
}