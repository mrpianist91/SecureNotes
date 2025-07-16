package com.example.securenotes.core;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "notes")
public class Note {
    @PrimaryKey
    @NonNull
    public String id;
    public String title;
    public String body;
    public String tag;
    public long createdAt;
    public long expiresAt; // Per le note temporanee
}