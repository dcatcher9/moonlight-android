package com.limelight;

import android.app.Application;

import com.limelight.preferences.LegacyProfileMigration;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        LegacyProfileMigration.migrateActiveProfile(this);
        LegacyProfileMigration.applyDebugBuildDefaults(this);
    }
}
