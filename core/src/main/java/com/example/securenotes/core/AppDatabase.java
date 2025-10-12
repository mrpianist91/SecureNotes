package com.example.securenotes.core;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;

@Database(entities = { Note.class }, version = 1, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    public abstract NoteDao noteDao();

    private static volatile AppDatabase INSTANCE;

    public static synchronized void openWithPassphrase(@NonNull Context context, @NonNull byte[] passphrase) {
        if (passphrase.length == 0) {
            throw new IllegalArgumentException("Passphrase vuota: impossibile aprire il database cifrato.");
        }
        SQLiteDatabase.loadLibs(context);
        final SupportFactory factory = new SupportFactory(passphrase);
        if (INSTANCE != null) {
            INSTANCE.close();
            INSTANCE = null;
        }
        INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "SecureNotes.db")
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build();
        zeroize(passphrase);
    }

    @NonNull
    public static AppDatabase getInstance() {
        AppDatabase local = INSTANCE;
        if (local == null) {
            throw new IllegalStateException("AppDatabase non inizializzato. Chiama openWithPassphrase(context, passphrase) dopo il login.");
        }
        return local;
    }

    public static synchronized void closeInstance() {
        if (INSTANCE != null) {
            try { INSTANCE.close(); } catch (Exception ignored) {}
            finally { INSTANCE = null; }
        }
    }

    private static void zeroize(@NonNull byte[] buf) {
        for (int i = 0; i < buf.length; i++) buf[i] = 0;
    }
}
