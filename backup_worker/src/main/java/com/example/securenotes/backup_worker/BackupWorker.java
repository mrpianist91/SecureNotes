package com.example.securenotes.backup_worker;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
public class BackupWorker extends Worker {
    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters
            workerParams) {
        super(context, workerParams);
    }
    @NonNull
    @Override
    public Result doWork() {
        // Questo è un segnaposto. La logica di backup reale andrà qui.
        Log.d("BackupWorker", "Backup job started (stub).");
        // Simulare il lavoro
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.d("BackupWorker", "Backup job finished (stub).");
        return Result.success();

    }
}
