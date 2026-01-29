package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that listens for ACTION_BOOT_COMPLETED to reschedule
 * all enabled alarms after device reboot.
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

            Log.i(TAG, "Boot completed, rescheduling alarms");
            rescheduleAlarms(context);
        }
    }

    /**
     * Reschedule all enabled alarms after boot.
     */
    private void rescheduleAlarms(Context context) {
        try {
            // Initialize and reschedule
            WearAlarmScheduler scheduler = WearAlarmScheduler.getInstance(context);
            scheduler.initialize();
            scheduler.rescheduleAllAlarms();

            Log.i(TAG, "Alarms rescheduled successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error rescheduling alarms", e);
        }
    }
}
