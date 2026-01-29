package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.ui.MainActivity;

/**
 * BroadcastReceiver that triggers when a scheduled alarm fires.
 * 
 * Responsibilities:
 * - Wake the device if sleeping
 * - Set the active stop signal for dismissal
 * - Start the alarm sound/vibration via AlarmManagerUtils
 * - Launch MainActivity to show alarm UI
 * - Update alarm repositories
 */
public class AlarmTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmTriggerReceiver";

    /** Broadcast action when alarm triggers and is starting */
    public static final String ACTION_ALARM_TRIGGERED = 
            "com.fufelshmertzpakostincorporated.kawaicare.ALARM_TRIGGERED";

    /** Extra key for alarm ID in broadcasts */
    public static final String EXTRA_ALARM_ID = WearAlarmScheduler.EXTRA_ALARM_ID;

    /** Extra key for alarm label in broadcasts */
    public static final String EXTRA_ALARM_LABEL = WearAlarmScheduler.EXTRA_ALARM_LABEL;

    /** Extra key for stop signal in broadcasts */
    public static final String EXTRA_STOP_SIGNAL = WearAlarmScheduler.EXTRA_STOP_SIGNAL;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !WearAlarmScheduler.ACTION_ALARM_TRIGGER.equals(intent.getAction())) {
            return;
        }

        String alarmId = intent.getStringExtra(EXTRA_ALARM_ID);
        String label = intent.getStringExtra(EXTRA_ALARM_LABEL);
        String stopSignal = intent.getStringExtra(EXTRA_STOP_SIGNAL);

        Log.i(TAG, "Alarm triggered: " + alarmId + " - " + label);

        // Acquire wake lock to ensure alarm processing completes
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;

        try {
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "KawaiiCare:AlarmTriggerWakeLock"
                );
                wakeLock.acquire(10000); // 10 second timeout
            }

            // Set up the alarm state
            setupAlarmState(context, alarmId, label, stopSignal);

            // Launch MainActivity to handle the alarm
            launchMainActivity(context, alarmId, label, stopSignal);

            // Broadcast alarm triggered for any other listeners
            broadcastAlarmTriggered(context, alarmId, label, stopSignal);

            // Disable this alarm (one-shot) or update for recurring
            handleAlarmCompletion(context, alarmId);

        } catch (Exception e) {
            Log.e(TAG, "Error processing alarm trigger", e);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    /**
     * Set up the alarm state in repositories.
     */
    private void setupAlarmState(Context context, String alarmId, String label, String stopSignal) {
        // Set alarm status ON
        AlarmStatusRepository.getInstance().setAlarmStatus(true);

        // Set the active stop signal for this alarm
        if (stopSignal != null && !stopSignal.isEmpty()) {
            String customGesturePath = null;
            if (SignalRegistry.SIGNAL_CUSTOM.equals(stopSignal)) {
                // Get custom gesture path from SignalRegistry
                SignalRegistry registry = new SignalRegistry(context);
                customGesturePath = registry.getCustomGestureFilePath();
            }
            AlarmStatusRepository.getInstance().setActiveStopSignal(stopSignal, customGesturePath);
        }

        Log.d(TAG, "Alarm state set: ON, signal=" + stopSignal);
    }

    /**
     * Launch MainActivity to show the alarm UI.
     */
    private void launchMainActivity(Context context, String alarmId, String label, String stopSignal) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setAction(ACTION_ALARM_TRIGGERED);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchIntent.putExtra(EXTRA_ALARM_ID, alarmId);
        launchIntent.putExtra(EXTRA_ALARM_LABEL, label);
        launchIntent.putExtra(EXTRA_STOP_SIGNAL, stopSignal);

        context.startActivity(launchIntent);
        Log.d(TAG, "Launched MainActivity for alarm");
    }

    /**
     * Broadcast that an alarm has triggered.
     */
    private void broadcastAlarmTriggered(Context context, String alarmId, String label, String stopSignal) {
        Intent broadcastIntent = new Intent(ACTION_ALARM_TRIGGERED);
        broadcastIntent.setPackage(context.getPackageName());
        broadcastIntent.putExtra(EXTRA_ALARM_ID, alarmId);
        broadcastIntent.putExtra(EXTRA_ALARM_LABEL, label);
        broadcastIntent.putExtra(EXTRA_STOP_SIGNAL, stopSignal);
        context.sendBroadcast(broadcastIntent);
    }

    /**
     * Handle alarm completion (disable one-shot alarms).
     */
    private void handleAlarmCompletion(Context context, String alarmId) {
        if (alarmId == null) {
            return;
        }

        try {
            WearAlarmRepository repository = WearAlarmRepository.getInstance(context);
            Alarm alarm = repository.getAlarm(alarmId);

            if (alarm != null) {
                // Disable the alarm after it fires (one-shot behavior)
                // For recurring alarms, you would reschedule here instead
                repository.setAlarmEnabled(alarmId, false);
                Log.d(TAG, "Disabled fired alarm: " + alarmId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling alarm completion", e);
        }
    }
}
