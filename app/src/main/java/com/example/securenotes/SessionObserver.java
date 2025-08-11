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

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.navigation.NavController;
import com.example.securenotes.feature_auth.R;

import androidx.navigation.NavOptions;

public class SessionObserver implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    /*private static final long BACKGROUND_INTERVAL_MS = 10000;  // 10 secondi
    private static int startedCount = 0;
    private static long lastBackgroundTime = 0;
    private static boolean sessionInvalidated = false;
    private static NavController navController;      // riferimento al NavController principale
    private static Handler timeoutHandler;    //  il gestore del timer interno al SessionObserver, crea un MessageQueue che verrà associato al thread principale grazie al Looper
    private static Runnable timeoutRunnable;  // COSA eseguire quando scade il timer
    private static long sessionTimeoutMs;   */         // durata timeout per "inattività" in millisecondi
    /* ───────────────────────── configurabili ───────────────────────── */
    private static long     sessionTimeoutMs;          // es. 180 000 (3 min)
    private static boolean      inForeground;      // # Activity in onStart()
    private static Handler  timeoutHandler;                   // handler sul main-thread
    private static Runnable timeoutRunnable;              // naviga all’auth_graph

    /* ───────────────────────── stato globale ───────────────────────── */
    private static boolean  sessionRunning    = false; // true dopo startSession
    private static boolean  sessionInvalidated    = false; // scatta dopo timeout
    private static NavController navController;        // fornito dal costruttore

    public SessionObserver(NavController mainNavController, long timeoutMinutes) {
        navController = mainNavController;
        sessionTimeoutMs = timeoutMinutes * 60 * 1000;
        timeoutHandler = new Handler(Looper.getMainLooper()); // Un Looper è un oggetto che permette al thread (Runnable) di diventare event-driven tramite MessageQueue. In pratica il Looper estrae i messaggi dalla queue e li esegue. Esiste già un Looper associato al main thread dal framework. Con getMainLooper() lo otteniamo e associamo all'Handler.
        timeoutRunnable = () -> {
            // Invalida la sessione per timeout e va all'autenticazione
            sessionRunning = false;
            sessionInvalidated = true; // se app è in background navigeremo al ritorno
            /*if (navController != null) {
                navController.navigate(R.id.auth_nav, null,
                        new androidx.navigation.NavOptions.Builder().setPopUpTo(com.example.securenotes.R.id.nav_graph, true).build());
            }*/
            navigateToAuthIfPossible();
        };
        inForeground = false;
    }

    /* ─────────────────────────  helper  ───────────────────────── */

    private static void navigateToAuthIfPossible() {
        if (navController != null && sessionInvalidated) {
            sessionInvalidated = false;  // evitiamo sdoppiamenti di nav
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(com.example.securenotes.R.id.nav_graph, true)
                    .build();
            navController.navigate(R.id.auth_nav, null, opts);
        }
    }

    /** Avvia – o riavvia dopo login – il timer di SESSIONE. */
    public static void startSession(long timeoutMs) {
        sessionTimeoutMs = timeoutMs;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        timeoutHandler.postDelayed(timeoutRunnable, sessionTimeoutMs);
        sessionRunning = true;
        sessionInvalidated = false;
    }

    /** Logout manuale / cambio-PIN: interrompe subito la sessione. */
    public static void invalidateSession() {
        timeoutHandler.removeCallbacks(timeoutRunnable);
        sessionRunning = false;
        sessionInvalidated = true;
        navigateToAuthIfPossible();
    }

    /** Rimposta (“azzera”) il conto alla rovescia che blocca l’app quando l’utente non interagisce più o quando l’app va in background.
     * Da chiamare su ogni interazione utente (es. in MainActivity.onUserInteraction()) */
    public static void resetSessionTimer() {
        if (timeoutHandler == null) return;
        timeoutHandler.removeCallbacks(timeoutRunnable); //cancella il vecchio timer
        timeoutHandler.postDelayed(timeoutRunnable, sessionTimeoutMs); //avvia il nuovo timer
    }

    /** Invalida forzatamente la sessione (es. logout manuale o cambio PIN) */
    /*public static void invalidateSession() {
        sessionInvalidated = true;
        if (navController != null) {
            navController.navigate(R.id.auth_graph, null,
                    new androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build());
        }
    }*/

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        /*if (startedCount == 0 && lastBackgroundTime > 0) {
            // L'app sta tornando in foreground
            long backgroundDuration = System.currentTimeMillis() - lastBackgroundTime;
            if (backgroundDuration > BACKGROUND_INTERVAL_MS) {
                sessionInvalidated = true;
            }
            lastBackgroundTime = 0;
        }
        startedCount++;
        // Se l'attività inizia e la sessione è marcata invalida, naviga al login
        if (sessionInvalidated && navController != null) {
            sessionInvalidated = false;
            navController.navigate(R.id.auth_graph, null,
                    new androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build());
        }
        // Avvia/Reset del timer di inattività quando l'attività diventa visibile
        resetSessionTimer();*/
        if (!inForeground) {
            inForeground = true; //l'app torna visibile
            // Se la sessione è invalida, naviga al login
            if (sessionInvalidated) navigateToAuthIfPossible();
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        /*startedCount--;
        if (startedCount == 0) {
            // L'app è andata in background
            lastBackgroundTime = System.currentTimeMillis();
            // Ferma il timer di inattività mentre in background
            if (timeoutHandler != null) {
                timeoutHandler.removeCallbacks(timeoutRunnable);
            }
        }*/
        inForeground=false;
        if (sessionRunning) {
            /* App in background → scadenza immediata */
            timeoutHandler.removeCallbacks(timeoutRunnable);
            sessionRunning = false;
            sessionInvalidated = true;
        }

    }

    // Altri metodi dell'interfaccia (non utilizzati ma devono essere presenti)
    @Override public void onActivityResumed(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, Bundle outState) { }
}
