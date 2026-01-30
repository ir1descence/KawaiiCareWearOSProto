package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.fufelshmertzpakostincorporated.kawaicare.event.EventScheduler;
import com.fufelshmertzpakostincorporated.kawaicare.network.TcpWearService;

/**
 * BroadcastReceiver that listens for ACTION_BOOT_COMPLETED to reschedule
 * all enabled events after device reboot and restart the TCP service.
 * 
 * Required because AlarmManager alarms are cleared on reboot.
 */
public class AlarmBootReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.i(TAG, "Boot completed, rescheduling events and starting TCP service");
            rescheduleEvents(context);
            startTcpWearService(context);
        }
    }

    /**
     * Reschedule all enabled events after boot.
     */
    private void rescheduleEvents(Context context) {
        try {
            // Initialize and reschedule using EventScheduler
            EventScheduler scheduler = EventScheduler.getInstance(context);
            scheduler.initialize();
            scheduler.rescheduleAllEvents();

            Log.i(TAG, "Events rescheduled successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error rescheduling events", e);
        }
    }

    /**
     * Start the TCP Wear Service after boot.
     * This ensures the watch is discoverable on the network immediately after reboot.
     */
    private void startTcpWearService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, TcpWearService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "TcpWearService started after boot");
        } catch (Exception e) {
            Log.e(TAG, "Error starting TcpWearService", e);
        }
    }
}
