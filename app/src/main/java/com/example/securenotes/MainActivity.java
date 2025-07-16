package com.example.securenotes;

import android.os.Bundle;
import com.example.securenotes.BuildConfig;
import androidx.appcompat.app.AppCompatActivity;
import com.example.securenotes.backup_worker.BackupWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (BuildConfig.DEBUG) {
            // avvia il test solo in debug (build type)
            scheduleBackup();
        }
           // Esempio di avvio del worker (da spostare in Impostazioni in futuro)
           // scheduleBackup();
    }
    private void scheduleBackup() {
        OneTimeWorkRequest req = new OneTimeWorkRequest
                .Builder(BackupWorker.class)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(this).enqueue(req);
    }

    /*private void scheduleBackup() {
OneTimeWorkRequest backupRequest = new
OneTimeWorkRequest.Builder(BackupWorker.class).build();
WorkManager.getInstance(this).enqueue(backupRequest);
}*/
}



/*import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

    }
}*/