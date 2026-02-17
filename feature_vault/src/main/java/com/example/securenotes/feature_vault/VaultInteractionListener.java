package com.example.securenotes.feature_vault;

/**
 * Interfaccia per gestire le interazioni tra il Vault e il contenitore (App/Activity).
 * Permette di coordinare azioni che impattano il ciclo di vita o la sessione.
 */
public interface VaultInteractionListener {
    /**
     * Da chiamare PRIMA di lanciare un Intent di sistema (es. SAF, Camera)
     * che porterebbe l'app in background temporaneamente.
     */
    void onVaultExternalAction();
}