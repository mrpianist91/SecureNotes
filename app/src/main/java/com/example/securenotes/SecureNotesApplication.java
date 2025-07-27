package com.example.securenotes;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

public class SecureNotesApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
