package com.example.securenotes;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.color.DynamicColors;
import com.example.securenotes.core.PreferenceManager;

/**
 * Application class che gestisce:
 *  • Dynamic Color (Material You)
 *  • Registrazione globale di SessionObserver
 */
public class SecureNotesApplication extends Application {

    //private static final long DEFAULT_TIMEOUT_MS = 3 * 60 * 1000L;  // 3 minuti
    //private SessionObserver sessionObserver;
    //private boolean observerRegistered = false;                     // evita doppie registrazioni

    @Override
    public void onCreate() {
        super.onCreate();

        // Attiva i Dynamic Colors se disponibili
        DynamicColors.applyToActivitiesIfAvailable(this);

        // un metodo di Application che ti permette di iscriverti a tutti gli eventi (ON_CREATE, ON_START, ON_RESUMED, ON_PAUSE, ON_STOP, ON_DESTROY) di lifecycle di ogni Activity dell’app.
        // Ci serve sapere quando viene creata la MainActivity
        //registerActivityLifecycleCallbacks(this);
    }

    /* --------------------------------------------------------------------- */
    /* ActivityLifecycleCallbacks – ci interessa solo onActivityCreated      */
    /* --------------------------------------------------------------------- */

   /* @Override
    public void onActivityCreated(@NonNull Activity activity,
                                  @Nullable Bundle savedInstanceState) {

        Log.d("A", "App.onActivityCreated for " + activity + " t=" + SystemClock.uptimeMillis());

        if (observerRegistered) return;                    // già fatto

        if (activity instanceof MainActivity) {            // la tua (main)activity con il NavHost
            AppCompatActivity main = (AppCompatActivity) activity;

            NavHostFragment navHost =
                    (NavHostFragment) main.getSupportFragmentManager()
                            .findFragmentById(R.id.fragmentContainerView);

            if (navHost != null) {
                NavController navController = navHost.getNavController();

                // Timeout salvato dall’utente (oppure, se non presente fallback al default 3′)
                PreferenceManager prefs = new PreferenceManager(main);
                long timeoutMs = prefs.getSessionTimeoutMs(DEFAULT_TIMEOUT_MS);
//La seguente istruzione permette di
// 1. osservare globalmente quando l’utente manda l’app in background o la riporta in foreground, senza dover replicare lo stesso codice in ogni Activity.
// 2. Avviare / fermare logiche “di sessione”:
//  far partire il timer di timeout di sessione quando l’app finisce in background (`ON_STOP`);
//  annullarlo quando l’utente torna (`ON_START`);
//  eventuali cleanup (chiudere DB, rimuovere listener, ecc.).
//Implementare analytics, logging, root-detection periodica, schedulazione backup, ecc., una sola volta per tutto il processo.
                sessionObserver = new SessionObserver(navController, timeoutMs);
                ProcessLifecycleOwner.get()
                        .getLifecycle()
                        .addObserver(sessionObserver);

                observerRegistered = true;                // una volta basta
            }
        }
    }*/

    /* ----- Metodi obbligatori vuoti -------------------------------------- */

   /* @Override public void onActivityStarted(@NonNull Activity activity) {}
    @Override public void onActivityResumed(@NonNull Activity activity) {}
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivityStopped(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity activity) {}*/
}
