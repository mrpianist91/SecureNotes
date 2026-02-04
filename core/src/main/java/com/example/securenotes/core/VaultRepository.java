package com.example.securenotes.core;

import androidx.lifecycle.LiveData;
import android.content.Context;
import android.net.Uri;
import java.util.List;

public interface VaultRepository {
    LiveData<List<VaultFile>> getAllFiles();

    // FIX: Aggiunta callback per gestire fine caricamento
    void addFileFromUri(Context context, Uri sourceUri, CompletionListener listener);

    void deleteFile(VaultFile file);
    void decryptFileForViewing(Context context, VaultFile file, OnFileDecryptedListener listener);

    interface OnFileDecryptedListener {
        void onDecrypted(Uri fileUri);
        void onError(Exception e);
    }

    // Nuova interfaccia per operazioni void asincrone
    interface CompletionListener {
        void onComplete();
        void onError(Exception e);
    }

    //Verrà chiamato nella onDestroy() di VaultFragment
    static void clearTempCache(Context context) {
        try {
            java.io.File cacheDir = new java.io.File(context.getCacheDir(), "vault_temp");
            if (cacheDir.exists()) {
                java.io.File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) f.delete();
                }
            }
        } catch (Exception ignored) {}
    }
}