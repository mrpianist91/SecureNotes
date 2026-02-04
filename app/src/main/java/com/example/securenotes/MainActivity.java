package com.example.securenotes;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.securenotes.backup_worker.BackupWorker;
import com.example.securenotes.core.PreferenceManager;
import com.example.securenotes.databinding.ActivityMainBinding;

import androidx.lifecycle.ViewModelProvider;
import com.example.securenotes.feature_auth.AuthViewModel;
import com.example.securenotes.feature_auth.AuthViewModel.LoginResult;
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
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;     // ViewBinding
    private AppBarConfiguration appBarConfiguration;
    // --------------------------------------------------------------------- //
    // Life-cycle
    // --------------------------------------------------------------------- //
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("A", "Main.onCreate START " + this + " t=" + SystemClock.uptimeMillis());
        super.onCreate(savedInstanceState);
        Log.d("A", "Main.onCreate END   " + this + " t=" + SystemClock.uptimeMillis());

        // ----- ViewBinding -------------------------------------------------
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Collega la Toolbar come Action Bar (host dei menu dei Fragment)
        MaterialToolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);

        // ----- Navigation Setup--------------------------------------------------
        NavHostFragment navHost =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.fragmentContainerView);
        // Controllo difensivo se il fragment non è ancora istanziato (raro ma possibile)
        if (navHost == null) return;
        NavController navController = navHost.getNavController();

        // Collega Navigation al titolo/up button
        /*appBarConfiguration = new AppBarConfiguration.Builder(R.id.notesListFragment).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);*/

        // 4. CONFIGURAZIONE "TOP LEVEL DESTINATIONS"
        // Definiamo quali schermate sono "radici" della navigazione (Note, Vault, Settings).
        // In queste schermate NON verrà mostrata la freccia "Indietro" (Up Button) nella Toolbar.
        Set<Integer> topLevelDestinations = new HashSet<>();
        topLevelDestinations.add(R.id.notesListFragment);
        topLevelDestinations.add(R.id.vaultFragment);
        topLevelDestinations.add(R.id.settingsFragment);

        appBarConfiguration = new AppBarConfiguration.Builder(topLevelDestinations).build();

        // Collega la Toolbar al NavController usando la configurazione definita
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // 5. SETUP BOTTOM NAVIGATION VIEW
        // Questo metodo collega automaticamente i click sulla BottomBar alla navigazione.
        // Funziona perché gli ID nel menu_bottom_nav.xml coincidono con gli ID nel nav_graph.xml.
        NavigationUI.setupWithNavController(binding.bottomNav, navController);

        // 6. GATEKEEPER VISIVO (Gestione Visibilità BottomBar)
        // La barra deve apparire SOLO nelle destinazioni principali.
        // Deve sparire in: Splash, Login, Onboarding, Modifica Nota, etc.
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            int id = destination.getId();

            // Verifichiamo se la destinazione attuale è una di quelle Top Level
            if (topLevelDestinations.contains(id)) {
                binding.bottomNav.setVisibility(View.VISIBLE);

                // Opzionale: Reinneschiamo il timer di sessione quando si atterra su una schermata principale
                // per garantire che l'attività venga registrata.
                refreshSession();
            } else {
                binding.bottomNav.setVisibility(View.GONE);
            }
        });

        // ----- Collega qui il SessionObserver (ora il NavHost esiste) -----
        setupSessionObserver(navController);
        /*long timeoutMs = new PreferenceManager(this)
                .getSessionTimeoutMs(3 * 60 * 1000L); // default 3 minuti in ms
        ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(new SessionObserver(navController, timeoutMs));*/


        // Avvia/riavvia la sessione quando si "approda" nella schermata delle note,
        // indipendentemente dal percorso (PIN o biometria).
        /*navController.addOnDestinationChangedListener((controller, destination, args) -> {
            if (destination.getId() == R.id.notesListFragment) {
                //long timeoutMs = new PreferenceManager(this)
                //        .getSessionTimeoutMs(3 * 60 * 1000L); // default 3'
                //SessionObserver.startSession(timeoutMs);
                long toMs = new PreferenceManager(this).getSessionTimeoutMs(3 * 60 * 1000L);
                SessionObserver.startSession(toMs);
            }
        });*/

        // ----- Backup di test (solo build DEBUG) --------------------------
        if (BuildConfig.DEBUG) {
            scheduleDebugBackup();
        }
    }

    private void setupSessionObserver(NavController navController) {
        long timeoutMs = new PreferenceManager(this)
                .getSessionTimeoutMs(3 * 60 * 1000L); // default 3 minuti

        ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(new SessionObserver(navController, timeoutMs));
    }

    private void refreshSession() {
        long toMs = new PreferenceManager(this).getSessionTimeoutMs(3 * 60 * 1000L);
        SessionObserver.startSession(toMs);
    }

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

    // --------------------------------------------------------------------- //
    // Helper
    // --------------------------------------------------------------------- //
    /** Avvia un BackupWorker «expedited» solo per test in build DEBUG. */
    private void scheduleDebugBackup() {
        WorkRequest req = new OneTimeWorkRequest.Builder(BackupWorker.class)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(this).enqueue(req);
    }
}
