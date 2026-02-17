package com.example.securenotes.core;

/**
 * Interfaccia generica per i componenti che devono interagire con il sistema operativo
 * o altre app (es. SAF, Camera, Permission Request) causando la pausa dell'Activity ospitante.
 *
 * I Fragment usano questo listener per segnalare al Container (MainActivity)
 * che l'uscita dall'app è intenzionale e non deve invalidare la sessione di sicurezza.
 */
public interface SystemInteractionListener {
    /**
     * Da invocare IMMEDIATAMENTE prima di lanciare un Intent esterno (startActivity/launch).
     * Istruisce il SessionObserver di ignorare il prossimo evento onStop.
     */
    void onSystemInteraction();
}