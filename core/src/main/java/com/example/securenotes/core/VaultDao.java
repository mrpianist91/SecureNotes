package com.example.securenotes.core;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface VaultDao {
    @Query("SELECT * FROM vault_files ORDER BY addedAt DESC")
    LiveData<List<VaultFile>> getAllFiles();

    @Insert
    void insertVaultFile(VaultFile file);

    @Delete
    void deleteVaultFile(VaultFile file);

    @Query("DELETE FROM vault_files WHERE id = :id")
    void deleteById(String id);

    @Query("SELECT * FROM vault_files WHERE id = :id LIMIT 1")
    VaultFile getFileById(String id);
}