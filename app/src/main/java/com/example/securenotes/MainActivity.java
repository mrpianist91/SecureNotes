package com.example.securenotes;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.securenotes.backup_worker.BackupWorker;
import com.example.securenotes.core.PreferenceManager;
import com.example.securenotes.core.SystemInteractionListener;
import com.example.securenotes.databinding.ActivityMainBinding;

import androidx.lifecycle.ViewModelProvider;

import com.example.securenotes.feature_auth.AuthListener;
import com.example.securenotes.feature_auth.AuthViewModel;
import com.example.securenotes.feature_auth.AuthViewModel.LoginResult;
import com.example.securenotes.feature_vault.VaultInteractionListener;
import com.google.android.material.appbar.MaterialToolbar;

import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.util.HashSet;
import java.util.Set;

/**
 * Activity principale che funge da host per la navigazione.
 * Gestisce:
 * - Setup della Toolbar e BottomNavigationView con Jetpack Navigation.
 * - Visibilità condizionale della UI (BottomNav visibile solo nei fragment principali).
 * - Avvio del SessionObserver per il timeout di sicurezza.
 */
/**
 * Implementa "AuthListener" per gestire l'avvio della sessione
 * quando il LoginFragment segnala il successo (Principio di Inversione della Dipendenza) tramite Callback/Listener
 *
 * Implementa "SystemInteractionListener" per evitare che durante le operazioni SAF (Storage Access Framework) l'utente venga forzato al logout: 1) al rientro dal FilePicker nelle Impostazioni (durante il Backup),
 * e 2) dopo la conferma sul file da importare nel Vault, il main vada in Stop bloccando la sessione (forzando di conseguenza una nuova autenticazione).
 * Infatti la MainActivity va in Pausa o Stop durante le operazioni del File Picker, e dal momento che il SessionObserver segue una politica di "zero trust", butterebbe fuori l'utente.
 * NB l'interfaccia SystemInteractionListener, è definita nel modulo condiviso (incluso) sia dal modulo :app che :feature_vault, cioè il modulo :core, in modo che fosse visibile a tutti
 */

public class MainActivity extends AppCompatActivity implements AuthListener, SystemInteractionListener {

    private ActivityMainBinding binding;     // ViewBinding
    private AppBarConfiguration appBarConfiguration;

    //Implementazione AuthListener per SessionObserver
    @Override
    public void onAuthSuccess() {
        // Recupera il timeout dalle preferenze
        long timeoutMs = new PreferenceManager(this).getSessionTimeoutMs();
        // Avvia il SessionObserver
        SessionObserver.startSession(timeoutMs);
        Log.d("MainActivity", "Sessione avviata con timeout: " + timeoutMs);
    }

    // Implementazione VaultInteractionListener per SessionObserver
    /*@Override
    public void onVaultExternalAction() {
        // Segnala all'Observer di ignorare il prossimo "onStop" (causato dall'Intent esterno generato quando si aggiungono file al vault)
        SessionObserver.setIgnoreNextPause();
    }*/

    // --- Implementazione SystemInteractionListener ---
    @Override
    public void onSystemInteraction() {
        // Segnala all'Observer di ignorare il prossimo "onStop"
        // Questo metodo viene chiamato sia dal Vault (apertura file/import)
        // sia dai Settings (export backup)
        SessionObserver.setIgnoreNextPause();
    }



    // Life-cycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("A", "Main.onCreate START " + this + " t=" + SystemClock.uptimeMillis());
        super.onCreate(savedInstanceState);
        Log.d("A", "Main.onCreate END   " + this + " t=" + SystemClock.uptimeMillis());

        // 1. SICUREZZA GLOBALE: Impedisce screenshot e anteprima nelle app recenti
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        //ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Collega (setta) la Toolbar come Action Bar (host dei menu dei Fragment)
        MaterialToolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);

        //Navigation Setup
        NavHostFragment navHost =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.fragmentContainerView);
        // Ottieni il NavController
        if (navHost == null) return;
        NavController navController = navHost.getNavController();

        // Collega Navigation al titolo/up button
        /*appBarConfiguration = new AppBarConfiguration.Builder(R.id.notesListFragment).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);*/

        // CONFIGURAZIONE "DESTINAZIONI PRINCIPALI"
        // Definiamo quali sono le schermate principali della navigazione (Note, Vault, Settings).
        // In queste schermate NON verrà mostrata la freccia "Indietro" (Up Button) nella Toolbar.
        Set<Integer> topLevelDestinations = new HashSet<>();
        topLevelDestinations.add(R.id.notesListFragment);
        topLevelDestinations.add(R.id.vaultFragment);
        topLevelDestinations.add(R.id.settingsFragment);

        appBarConfiguration = new AppBarConfiguration.Builder(topLevelDestinations).build();

        // Collega la Toolbar al NavController usando la configurazione definita
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // SETUP BOTTOM NAVIGATION VIEW
        // Questo metodo collega automaticamente i click sulla BottomBar alla navigazione.
        // Funziona perché gli ID nel menu_bottom_nav.xml coincidono con gli ID nel nav_graph.xml.
        NavigationUI.setupWithNavController(binding.bottomNav, navController);

        // (Gestione Visibilità BottomBar)
        // La barra deve apparire SOLO nelle destinazioni principali (Top Level).
        // Deve sparire in: Splash, Login, Onboarding, Modifica Nota, etc.
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            int id = destination.getId();

            // Verifichiamo se la destinazione attuale è una di quelle Top Level
            if (topLevelDestinations.contains(id)) {
                binding.bottomNav.setVisibility(View.VISIBLE);

                // Reinneschiamo il timer di sessione quando si approda su una schermata principale
                // per garantire che l'attività venga registrata.
                refreshSession();
            } else {
                binding.bottomNav.setVisibility(View.GONE);
            }
        });

        // Collega qui il SessionObserver (ora il NavHost esiste)
        setupSessionObserver(navController);

        //Backup di test (solo build DEBUG) DA ELIMINARE
        if (BuildConfig.DEBUG) {
            scheduleDebugBackup();
        }
    }

    private void setupSessionObserver(NavController navController) {
        long timeoutMs = new PreferenceManager(this)
                .getSessionTimeoutMs(3 * 60 * 1000L); // default 3 minuti

        // Registra l'observer che ascolta il ciclo di vita dell'INTERO processo (Background/Foreground) dell'app. ProcessLifecycleOwner chiama onStart/onStop del SessionObserver (che implementa il DefaultLifecycleOwner)
        ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(new SessionObserver(navController, timeoutMs));//il navController serve al SessionObserver per navigare verso il LoginFragment
    }

    private void refreshSession() {
       /* long toMs = new PreferenceManager(this).getSessionTimeoutMs(3 * 60 * 1000L);
        SessionObserver.startSession(toMs);*/
        // Aggiorna il timestamp dell'ultima interazione
        SessionObserver.resetSessionTimer();
    }

    //Gestisce il comportamento del pulsante "freccia su" (Up Button) nella Toolbar quando l'utente ci clicca
    //per tornare indietro nella navigazione.
    @Override
    public boolean onSupportNavigateUp() {
        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.fragmentContainerView);
       /* return NavigationUI.navigateUp(navHost.getNavController(), appBarConfiguration)
                || super.onSupportNavigateUp();*/
        if (navHost == null) return super.onSupportNavigateUp();

        return NavigationUI.navigateUp(navHost.getNavController(), appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    // Intercetta ogni interazione utente per resettare il timer di sessione
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        SessionObserver.resetSessionTimer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // Helper
    // Avvia un BackupWorker «expedited» solo per test in build DEBUG
    private void scheduleDebugBackup() {
        WorkRequest req = new OneTimeWorkRequest.Builder(BackupWorker.class)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(this).enqueue(req);
    }
}
