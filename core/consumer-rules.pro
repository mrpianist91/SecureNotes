# Room: entities (field names map to DB columns), DAOs, and generated database impl
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Core security/auth singletons – called from multiple modules
-keep class com.example.securenotes.core.AuthManager { *; }
-keep class com.example.securenotes.core.PreferenceManager { *; }
-keep class com.example.securenotes.core.SecurityUtils { *; }
-keep class com.example.securenotes.core.SecurityUtils$* { *; }
-keep class com.example.securenotes.core.Event { *; }

# Repository interfaces (implemented in feature modules)
-keep interface com.example.securenotes.core.NoteRepository { *; }
-keep interface com.example.securenotes.core.VaultRepository { *; }
-keep interface com.example.securenotes.core.VaultRepository$* { *; }

# SystemInteractionListener – implemented by MainActivity
-keep interface com.example.securenotes.core.SystemInteractionListener { *; }
