package com.example.securenotes.core;
import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SupportFactory;


@Database(entities = {Note.class, VaultFile.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract NoteDao noteDao();
    //public abstract VaultFileDao vaultFileDao();
    private static volatile AppDatabase INSTANCE;
    // Caricamento della libreria nativa, come da documentazione

    public static AppDatabase getDatabase(final Context context, final byte[] passphrase) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
// Carica le librerie native di SQLCipher
                    SQLiteDatabase.loadLibs(context);
// Crea la factory per SQLCipher
                    final SupportFactory factory = new SupportFactory(passphrase);
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "SecureNotes.db").openHelperFactory(factory).build();
                }
            }
        }
        return INSTANCE;
    }
}

/*
@Database(entities = { Note.class, VaultFile.class }, version = 1, exportSchema = false)

public abstract class AppDatabase extends RoomDatabase {

    public abstract NoteDao noteDao();
    public abstract VaultFileDao vaultFileDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(Context context, char[] passphraseChars) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    byte[] passphrase = SQLiteDatabase.getBytes(passphraseChars);
                    Arrays.fill(passphraseChars, '\0');

                    SupportFactory factory = new SupportFactory(passphrase);
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "secure_notes.db")
                            .openHelperFactory(factory)
                            .setJournalMode(JournalMode.TRUNCATE)
                            .build();

                    Arrays.fill(passphrase, (byte) 0);
                }
            }
        }
        return INSTANCE;
    }
}*/
