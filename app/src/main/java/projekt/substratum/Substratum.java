/*
 * Copyright (c) 2016-2017 Projekt Substratum
 * This file is part of Substratum.
 *
 * Substratum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Substratum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Substratum.  If not, see <http://www.gnu.org/licenses/>.
 */

package projekt.substratum;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crash.FirebaseCrash;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import projekt.substratum.activities.crash.SubstratumCrash;
import projekt.substratum.common.Broadcasts;
import projekt.substratum.common.Packages;
import projekt.substratum.common.References;
import projekt.substratum.common.Systems;
import projekt.substratum.services.binder.AndromedaBinderService;
import projekt.substratum.services.binder.InterfacerBinderService;
import projekt.substratum.services.system.SamsungPackageService;

import static projekt.substratum.BuildConfig.DEBUG;
import static projekt.substratum.common.Systems.isAndromedaDevice;
import static projekt.substratum.common.Systems.isBinderInterfacer;

public class Substratum extends Application {

    private static final String BINDER_TAG = "BinderService";
    private static final FinishReceiver finishReceiver = new FinishReceiver();
    private static Substratum substratum;
    private static boolean isWaiting = false;

    public static Substratum getInstance() {
        return substratum;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        substratum = this;

        // Firebase debug checks
        try {
            FirebaseApp.initializeApp(getApplicationContext());
            FirebaseCrash.setCrashCollectionEnabled(!DEBUG);
        } catch (IllegalStateException ise) {
            // Suppress warning
        }

        // Dynamically check which theme engine is running at the moment
        if (isAndromedaDevice(getApplicationContext())) {
            Log.d(BINDER_TAG, "Successful to start the Andromeda binder service: " +
                    (startBinderService(AndromedaBinderService.class) ? "Success!" : "Failed"));
        } else if (isBinderInterfacer(getApplicationContext())) {
            Log.d(BINDER_TAG, "Successful to start the Interfacer binder service: " +
                    (startBinderService(InterfacerBinderService.class) ? "Success!" : "Failed"));
        }

        // Implicit broadcasts must be declared
        Broadcasts.registerBroadcastReceivers(this);

        // If the device is Android Oreo, create a persistent notification
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            createNotificationChannel();
        }

        // Custom Activity on Crash initialization
        CaocConfig.Builder.create()
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SHOW_CUSTOM)
                .enabled(true)
                .showErrorDetails(true)
                .showRestartButton(true)
                .trackActivities(true)
                .minTimeBetweenCrashesMs(1)
                .errorActivity(SubstratumCrash.class)
                .apply();

        // Samsung refresher
        if (Systems.isSamsungDevice(getApplicationContext())) {
            startService(new Intent(getBaseContext(), SamsungPackageService.class));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void createNotificationChannel() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel mainChannel = new NotificationChannel(
                References.DEFAULT_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_default),
                NotificationManager.IMPORTANCE_DEFAULT);
        mainChannel.setDescription(
                getString(R.string.notification_channel_default_description));
        mainChannel.setSound(null, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build());
        assert notificationManager != null;
        notificationManager.createNotificationChannel(mainChannel);

        NotificationChannel compileChannel = new NotificationChannel(
                References.ONGOING_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_ongoing),
                NotificationManager.IMPORTANCE_LOW);
        mainChannel.setDescription(
                getString(R.string.notification_channel_ongoing_description));
        notificationManager.createNotificationChannel(compileChannel);

        NotificationChannel andromedaChannel = new NotificationChannel(
                References.ANDROMEDA_NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_andromeda),
                NotificationManager.IMPORTANCE_NONE);
        andromedaChannel.setDescription(
                getString(R.string.notification_channel_andromeda_description));
        notificationManager.createNotificationChannel(andromedaChannel);
    }

    public boolean startBinderService(Class className) {
        try {
            if (className.equals(AndromedaBinderService.class)) {
                if (checkServiceActivation(AndromedaBinderService.class)) {
                    Log.d(BINDER_TAG,
                            "This session will utilize the connected Andromeda Binder service!");
                } else {
                    Log.d(BINDER_TAG,
                            "Substratum is now connecting to the Andromeda Binder service...");
                    startService(new Intent(getApplicationContext(), AndromedaBinderService.class));
                }
            } else if (className.equals(InterfacerBinderService.class)) {
                if (checkServiceActivation(InterfacerBinderService.class)) {
                    Log.d(BINDER_TAG, "This session will utilize the connected Binder service!");
                } else {
                    Log.d(BINDER_TAG, "Substratum is now connecting to the Binder service...");
                    Intent i = new Intent(getApplicationContext(), InterfacerBinderService.class);
                    startService(i);
                }
            }
            return true;
        } catch (Exception e) {
            // Suppress warnings
        }
        return false;
    }

    private boolean checkServiceActivation(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        assert manager != null;
        for (ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public void startWaitingInstall() {
        isWaiting = true;
    }

    public boolean isWaitingInstall() {
        return isWaiting;
    }

    public void registerFinishReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        registerReceiver(finishReceiver, filter);
    }

    public void unregisterFinishReceiver() {
        try {
            unregisterReceiver(finishReceiver);
        } catch (IllegalArgumentException e) {
            // Already unregistered
        }
    }

    private static class FinishReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() != null) {
                String packageName = intent.getData().getEncodedSchemeSpecificPart();
                // Check whether the installed package is made by substratum
                String check = Packages.getOverlayParent(context, packageName);
                if (check != null) {
                    isWaiting = false;
                    Log.d("Substratum", "PACKAGE_ADDED: " + packageName);
                }
            }
        }
    }
}