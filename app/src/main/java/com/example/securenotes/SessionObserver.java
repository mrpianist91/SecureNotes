/*+--------------------+
        |  Thread (UI)       |
        |  while(true) {     |
        (handler)   |     msg = queue.next(); <-- Looper.loop()
        │         |     msg.target.dispatchMessage(msg)
        │ post()  |  }                 |
        ▼         +--------------------+
        MessageQueue  (timestamp-sorted)*/

/** SessionObserver:
 * – invalida la sessione non appena TUTTE le Activity sono in background;
 * – obbliga l'utente a ri-autenticarsi al primo rientro in foreground.
 *
 * Flusso:
 *   login OK            → startSession()
 *   app in background   → onActivityStopped() ⇒ sessionExpired = true
 *   rientro foreground  → onActivityStarted()  (vede sessionExpired) ⇒ nav login
 *   logout / cambio PIN → invalidateSession()
 */


package com.example.securenotes;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.navigation.NavController;
import com.example.securenotes.feature_auth.R;
import com.example.securenotes.feature_vault.VaultFragment;

import androidx.navigation.NavOptions;

/**
 * SessionObserver: Gestore del ciclo di vita della sessione di sicurezza.
 * Implementa DefaultLifecycleObserver (onStart, onStop, onPause ecc)
 * Questo permette a ProcessLifecycleOwner di invocare correttamente onStart (Foreground)
 * e onStop (Background).
 */
public class SessionObserver implements DefaultLifecycleObserver {

    private static final String TAG = "SessionObserver";

    //Configurazione
    private static long sessionTimeoutMs;          // tempo timeout inattività (default 3 min)
    private static final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // Runnable eseguito se l'utente non tocca lo schermo per X minuti
    private static final Runnable timeoutRunnable = () -> {
        Log.d(TAG, "Timeout inattività scaduto: sessione invalidata.");
        invalidateSession();
    };

    //Stato Globale

    // Indica se l'utente è attualmente autenticato e la sessione è valida
    private static boolean isSessionValid = false;

    //fornito dal costruttore, serve a navigare verso il LoginFragment
    private static NavController navController;

    // Se true, ignora il prossimo onStop() senza invalidare la sessione.
    private static boolean ignoreNextPause = false;

    public SessionObserver(NavController mainNavController, long timeoutMs) {
        navController = mainNavController;
        sessionTimeoutMs = timeoutMs;
    }


    /**
     * Da chiamare SOLO dopo un Login (PIN o Biometrico) avvenuto con successo.
     * Abilita il monitoraggio della sessione.
     */
    public static void startSession(long timeoutMs) {
        sessionTimeoutMs = timeoutMs;
        isSessionValid = true;
        ignoreNextPause = false; // Reset di sicurezza all'avvio
        resetSessionTimer();
        Log.d(TAG, "Sessione avviata manualmente. Timeout: " + timeoutMs + "ms");
    }

    /**
     * Resetta il timer di inattività.
     * Deve essere chiamato da MainActivity.onUserInteraction().
     */
    public static void resetSessionTimer() {
        if (!isSessionValid) return; // Non resettare se non siamo loggati

        // Rimuove il callback pendente e ne pianifica uno nuovo
        timeoutHandler.removeCallbacks(timeoutRunnable);
        timeoutHandler.postDelayed(timeoutRunnable, sessionTimeoutMs);
    }

    public static void updateTimeout(long newTimeoutMs) {
        sessionTimeoutMs = newTimeoutMs;
        if (isSessionValid) resetSessionTimer();
    }

    /**
     * Forza il logout immediato (es. cambio PIN, timeout, o chiusura app).
     */
    public static void invalidateSession() {
        isSessionValid = false;
        timeoutHandler.removeCallbacks(timeoutRunnable); // Ferma il timer
        navigateToAuth();
    }

    /**
     * Chiama questo metodo PRIMA di lanciare un intent esterno (File Picker da "Esporta Backup cifrato" o "Aggiungi file al Vault").
     * Impedisce che la sessione venga invalidata quando l'app va in background.
     */
    public static void setIgnoreNextPause() {
        Log.d(TAG, "Il prossimo onStop sarà ignorato.");
        ignoreNextPause = true;
    }

    /*Gestione Lifecycle */

    /**
     * onStart: Scatta quando l'app entra in FOREGROUND (l'utente apre l'app).
     */
    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        Log.d(TAG, "App in Foreground (onStart). Stato sessione: " + isSessionValid);

        // se la sessione non è valida (es. invalidata in onStop o mai avviata)
        // allora forza il ritorno alla schermata di Login.
        if (!isSessionValid) {
            navigateToAuth();
        } else {
            // Se la sessione è ancora valida, riattiva il timer di inattività
            resetSessionTimer();
        }
    }

    /**
     * onStop: Scatta quando l'app va in BACKGROUND (Home button, blocco schermo, cambio app).
     * Qui applichiamo la "Zero Trust": uscita dall'app = sessione chiusa.
     */
    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        Log.d(TAG, "App in Background (onStop). Invalidazione sessione.");

        // 1. Ferma il timer di inattività per non consumare risorse in background
        timeoutHandler.removeCallbacks(timeoutRunnable);

        // CONTROLLO DEL FLAG per evitare di forzare l'utente al Login quando si torna dal File Picker di sistema (durante l'"Esegui Backup cifrato" e l'aggiunta di un file nel Vault).
        if (ignoreNextPause) {
            Log.d(TAG, "Sessione mantenuta attiva per azione esterna autorizzata.");
            // CONSUMIAMO IL FLAG: La prossima volta deve bloccare.
            ignoreNextPause = false;
            // NON invalidiamo la sessione (return)
            return;
        }

        // 2. Invalida la sessione immediatamente.
        // Al prossimo rientro (onStart), isSessionValid sarà false e verrà chiesto il PIN.
        isSessionValid = false;
    }

    // Nota: onDestroy, onResume, onPause non sono strettamente necessari per questa logica,
    // DefaultLifecycleObserver li gestisce di default come vuoti.

    /*Navigazione*/

    private static void navigateToAuth() {
        if (navController != null) {
            try {
                // Controllo per evitare loop se siamo già nel grafo di Auth
                int currentDestId = (navController.getCurrentDestination() != null)
                        ? navController.getCurrentDestination().getId()
                        : -1;

                // Elenco ID delle schermate di Auth dove NON dobbiamo navigare (siamo già lì)
                // Nota: R.id.loginFragment è in feature_auth, ma gli ID sono unificati nel grafo globale
                boolean isAuthScreen = (currentDestId == com.example.securenotes.feature_auth.R.id.loginFragment
                        || currentDestId == com.example.securenotes.feature_auth.R.id.SplashFragment
                        || currentDestId == com.example.securenotes.feature_auth.R.id.onBoardingFragment
                        || currentDestId == com.example.securenotes.feature_auth.R.id.createPinFragment);

                if (!isAuthScreen) {//Se non siamo nelle schermate di :feature_auth…
                    Log.d(TAG, "Eseguo navigazione verso Login.");

                    // Pulisce il backstack (rimuove note/vault) e va al grafo di Auth
                    NavOptions opts = new NavOptions.Builder()
                            .setPopUpTo(com.example.securenotes.R.id.nav_graph, true)
                            .setLaunchSingleTop(true)//controlla se l’auth_nav è già in cima allo stack (in caso ricicla direttamente tale referenza per la navigazione)
                            .build();

                    navController.navigate(R.id.auth_nav, null, opts);
                }
            } catch (Exception e) {
                // Può capitare se il NavController non è ancora attaccato o l'app sta morendo
                Log.e(TAG, "Navigazione fallita: " + e.getMessage());
            }
        }
    }
}
