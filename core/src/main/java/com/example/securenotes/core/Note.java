package com.example.securenotes.core;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;
/**
 * Entità Note del database.
 * Contiene campi per titolo, corpo (formattato HTML), tag, timestamp
 creazione e scadenza (expiresAt).
 */
@Entity(tableName = "notes")
public class Note {
    @PrimaryKey @NonNull
    public String id; // Identificativo univoco (es. UUID string)
    public String title;// Titolo (plain text, prima riga o inserito manualmente)
    public String body;// Corpo (testo formattato in HTML)
    public String tag;// Categoria della nota (es. "Faccende","Lavoro", ecc.)
    public long createdAt;// Timestamp creazione (epoch millis)
    public long expiresAt; // Timestamp autodistruzione (0 se non temporanea)


// Costanti dei tag disponibili (per coerenza con UI)...assegneremo una di tali costanti alla variabile “String tag”
    public static final String TAG_ALL = "Tutti";
    public static final String TAG_FACCENDE = "Faccende";
    public static final String TAG_LAVORO = "Lavoro";
    public static final String TAG_FAMIGLIA = "Famiglia";
}