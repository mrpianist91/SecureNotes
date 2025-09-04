package com.example.securenotes;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

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

        // ----- Navigation --------------------------------------------------
        NavHostFragment navHost =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.fragmentContainerView);
        NavController navController = navHost.getNavController();

        // ----- Collega qui il SessionObserver (ora il NavHost esiste) -----
        long timeoutMs = new PreferenceManager(this)
                .getSessionTimeoutMs(3 * 60 * 1000L); // default 3 minuti in ms
        ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(new SessionObserver(navController, timeoutMs));

        // Avvia/riavvia la sessione quando si "atterra" nella schermata delle note,
        // indipendentemente dal percorso (PIN o biometria).
        navController.addOnDestinationChangedListener((controller, destination, args) -> {
            if (destination.getId() == R.id.notesListFragment) {
                //long timeoutMs = new PreferenceManager(this)
                //        .getSessionTimeoutMs(3 * 60 * 1000L); // default 3'
                //SessionObserver.startSession(timeoutMs);
                long toMs = new PreferenceManager(this).getSessionTimeoutMs(3 * 60 * 1000L);
                SessionObserver.startSession(toMs);
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
