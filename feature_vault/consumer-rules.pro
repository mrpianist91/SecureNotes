# Fragment – Navigation Component instantiates by class name via reflection
-keep class com.example.securenotes.feature_vault.VaultFragment { *; }

# ViewModel and inner types
-keep class com.example.securenotes.feature_vault.VaultViewModel { *; }
-keep class com.example.securenotes.feature_vault.VaultViewModel$* { *; }

# Repository implementation
-keep class com.example.securenotes.feature_vault.VaultRepositoryImpl { *; }

# RecyclerView Adapter and inner types (ViewHolder, DiffCallback, listener interfaces)
-keep class com.example.securenotes.feature_vault.VaultAdapter { *; }
-keep class com.example.securenotes.feature_vault.VaultAdapter$* { *; }

# VaultInteractionListener – implemented by VaultFragment
-keep interface com.example.securenotes.feature_vault.VaultInteractionListener { *; }
