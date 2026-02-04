package com.example.securenotes.core;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * Rappresenta i metadati di un file cifrato archiviato nel Vault.
 * Il file fisico risiede nella directory interna dell'app, cifrato via Jetpack Security.
 */
@Entity(tableName = "vault_files")
public class VaultFile {
    @PrimaryKey
    @NonNull
    public String id;           // UUID del file

    public String name;         // Nome originale del file (es. "documento.pdf")
    public String path;         // Path assoluto del file cifrato nello storage interno
    public String mimeType;     // Tipo MIME per l'apertura (es. "application/pdf")
    public long addedAt;        // Timestamp di importazione
}