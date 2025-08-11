package com.example.securenotes;

import android.os.Bundle;

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

import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

/**
 * Activity di lancio dell’app:
 * – ospita il NavHostFragment dichiarato in activity_main.xml
 * – avvia (solo in debug) un BackupWorker di prova
 * – registra il SessionObserver per il timeout di sessione
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;     // ViewBinding
    //private SessionObserver sessionObserver;

    // --------------------------------------------------------------------- //
    // Life-cycle
    // --------------------------------------------------------------------- //
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ----- ViewBinding -------------------------------------------------
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // ----- Navigation --------------------------------------------------
        NavHostFragment navHost =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.fragmentContainerView);
        NavController navController = navHost.getNavController();



        // ----- Timeout di sessione ----------------------------------------
        //Il tempo di sessione va salvato nelle EncryptedSharedPreference per permettere all'utente di stabilire il valore di timeout che preferisce.
        /*PreferenceManager prefs = new PreferenceManager(this);
        // (3 min default, range 3-10 min definito nelle impostazioni)
        long timeoutMs = prefs.getSessionTimeoutMs();
        if (timeoutMs < 180_000L || timeoutMs > 600_000L) {
            timeoutMs = 180_000L;
        }
        sessionObserver = new SessionObserver(navController, timeoutMs);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(sessionObserver);
         */

        // -------- avvio timer di sessione al primo LOGIN --------
        AuthViewModel authVm = new ViewModelProvider(this).get(AuthViewModel.class);

        authVm.getLoginResult().observe(this, result -> {
            if (result == LoginResult.SUCCESS) {
                navController.navigate(R.id.notesListFragment); //vado alla dashboard in seguito al login (SUCCESS)
                long minutesMs = new PreferenceManager(this).getSessionTimeoutMs(3); // 3 di default
                SessionObserver.startSession(minutesMs);
                authVm.getLoginResult().removeObservers(this);   // osserva una sola volta
            }
        });

        // ----- Backup di test (solo build DEBUG) --------------------------
        if (BuildConfig.DEBUG) {
            scheduleDebugBackup();
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        /* azzera il timer del SessionObserver ad ogni interazione dell’utente
        SessionObserver.resetSessionTimer();*/
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        /*if (sessionObserver != null) {
            ProcessLifecycleOwner.get().getLifecycle().removeObserver(sessionObserver);
        }*/
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
