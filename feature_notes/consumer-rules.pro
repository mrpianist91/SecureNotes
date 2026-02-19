# Fragments – Navigation Component instantiates by class name via reflection
-keep class com.example.securenotes.feature_notes.notesListFragment { *; }
-keep class com.example.securenotes.feature_notes.NoteEditFragment { *; }

# ViewModel and its Factory
-keep class com.example.securenotes.feature_notes.NotesViewModel { *; }
-keep class com.example.securenotes.feature_notes.NotesViewModelFactory { *; }

# Repository implementation
-keep class com.example.securenotes.feature_notes.NoteRepositoryImpl { *; }

# RecyclerView Adapter and inner types (ViewHolder, listener interfaces)
-keep class com.example.securenotes.feature_notes.NoteAdapter { *; }
-keep class com.example.securenotes.feature_notes.NoteAdapter$* { *; }
