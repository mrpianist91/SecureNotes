package com.example.securenotes.feature_auth;

/**
 * Interfaccia per comunicare eventi di autenticazione al container (Activity).
 * Permette di disaccoppiare il Fragment dalla logica di sessione globale dell'App.
 */
public interface AuthListener {
    void onAuthSuccess();
}
