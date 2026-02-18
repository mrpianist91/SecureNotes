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
 *  • Dynamic Color
 */
public class SecureNotesApplication extends Application {


    @Override
    public void onCreate() {
        super.onCreate();

        // Da Android 12 in poi adattiamo automaticamente il tema al sfondo del dispositivo (Dynamic Color "Material You").
        // Attiviamo quindi i Dynamic Colors se disponibili. Ciò farà sì che il tema usi la palette dinamica derivata dallo sfondo del
        //dispositivo, garantendo un’esperienza personalizzata e rispettando le linee guida moderne
        DynamicColors.applyToActivitiesIfAvailable(this);

        }


}
