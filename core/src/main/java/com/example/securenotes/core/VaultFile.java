package com.example.securenotes.core;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;
//il db serve per cifrare i metadati dei files, non i files stessi
@Entity(tableName = "vault_files")
public class VaultFile {
    @PrimaryKey
    @NonNull
    public String id;
    public String name;
    public String path; // Percorso del file cifrato nella memoria interna
    public long addedAt;
}
