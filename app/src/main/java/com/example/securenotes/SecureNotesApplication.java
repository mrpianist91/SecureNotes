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


    @Override
    public void onCreate() {
        super.onCreate();

        // Attiva i Dynamic Colors se disponibili
        DynamicColors.applyToActivitiesIfAvailable(this);

        }


}
