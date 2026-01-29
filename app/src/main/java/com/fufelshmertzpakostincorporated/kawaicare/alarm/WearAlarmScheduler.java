package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Helper class for scheduling alarms using Android's AlarmManager.
 * Uses setExactAndAllowWhileIdle for precision on Doze-mode devices.
 * 
 * This class handles:
 * - Scheduling alarms with exact timing
 * - Canceling scheduled alarms
 * - Rescheduling all alarms (e.g., after boot)
 * - Managing alarm state with WearAlarmRepository
 */
public class WearAlarmScheduler implements WearAlarmRepository.AlarmChangeListener {

    private static final String TAG = "WearAlarmScheduler";

    /** Intent action when alarm triggers */
    public static final String ACTION_ALARM_TRIGGER = 
            "com.fufelshmertzpakostincorporated.kawaicare.ALARM_TRIGGER";

    /** Intent extra for alarm ID */
    public static final String EXTRA_ALARM_ID = "alarm_id";

    /** Intent extra for alarm label */
    public static final String EXTRA_ALARM_LABEL = "alarm_label";

    /** Intent extra for stop signal */
    public static final String EXTRA_STOP_SIGNAL = "stop_signal";

    private static volatile WearAlarmScheduler instance;

    private final Context context;
    private final AlarmManager alarmManager;
    private final WearAlarmRepository repository;
    private final SignalRegistry signalRegistry;

    private WearAlarmScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.repository = WearAlarmRepository.getInstance(context);
        this.signalRegistry = new SignalRegistry(context);

        // Listen for alarm changes
        repository.addListener(this);
    }

    /**
     * Get singleton instance.
     */
    public static WearAlarmScheduler getInstance(Context context) {
        if (instance == null) {
            synchronized (WearAlarmScheduler.class) {
                if (instance == null) {
                    instance = new WearAlarmScheduler(context);
                }
            }
        }
        return instance;
    }

    /**
     * Schedule a new alarm with the system AlarmManager.
     * Also persists the alarm to the repository.
     *
     * @param timeMillis  Trigger time in epoch milliseconds
     * @param label       Human-readable label
     * @param stopSignal  Signal required to dismiss (validated against SignalRegistry)
     * @return The created Alarm object, or null if validation failed
     */
    public Alarm scheduleAlarm(long timeMillis, String label, String stopSignal) {
        // Validate the stop signal
        if (stopSignal == null || stopSignal.isEmpty()) {
            stopSignal = SignalRegistry.SIGNAL_SHAKE; // Default
        }

        SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(stopSignal);
        if (!validation.isValid()) {
            Log.e(TAG, "Invalid stop signal: " + stopSignal + " - " + validation.getErrorMessage());
            return null;
        }

        // Create the alarm
        Alarm alarm = new Alarm(timeMillis, label, stopSignal);

        // Persist to repository
        if (!repository.addAlarm(alarm)) {
            Log.e(TAG, "Failed to add alarm to repository");
            return null;
        }

        // Schedule with AlarmManager
        scheduleWithAlarmManager(alarm);

        Log.i(TAG, "Scheduled alarm: " + alarm.getId() + " for " + timeMillis);
        return alarm;
    }

    /**
     * Schedule an existing alarm with AlarmManager.
     * Called when alarm is added or enabled.
     */
    public void scheduleWithAlarmManager(@NonNull Alarm alarm) {
        if (!alarm.isEnabled()) {
            Log.d(TAG, "Skipping disabled alarm: " + alarm.getId());
            return;
        }

        if (!alarm.isFuture()) {
            Log.w(TAG, "Alarm time is in the past, skipping: " + alarm.getId());
            return;
        }

        PendingIntent pendingIntent = createAlarmPendingIntent(alarm);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: Check for exact alarm permission
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            alarm.getTimeMillis(),
                            pendingIntent
                    );
                } else {
                    // Fall back to inexact alarm
                    Log.w(TAG, "Exact alarm permission not granted, using inexact");
                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            alarm.getTimeMillis(),
                            pendingIntent
                    );
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0+: Use setExactAndAllowWhileIdle for Doze
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        alarm.getTimeMillis(),
                        pendingIntent
                );
            } else {
                // Older Android versions
                alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        alarm.getTimeMillis(),
                        pendingIntent
                );
            }

            Log.d(TAG, "Alarm scheduled with AlarmManager: " + alarm.getId());

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException scheduling alarm", e);
        }
    }

    /**
     * Cancel a scheduled alarm.
     *
     * @param alarmId The alarm ID to cancel
     * @param removeFromRepository If true, also removes from repository
     * @return true if operation succeeded
     */
    public boolean cancelAlarm(@NonNull String alarmId, boolean removeFromRepository) {
        Alarm alarm = repository.getAlarm(alarmId);
        if (alarm == null) {
            Log.w(TAG, "Alarm not found for cancellation: " + alarmId);
            return false;
        }

        // Cancel with AlarmManager
        cancelWithAlarmManager(alarm);

        if (removeFromRepository) {
            repository.removeAlarm(alarmId);
        }

        Log.i(TAG, "Cancelled alarm: " + alarmId);
        return true;
    }

    /**
     * Cancel alarm with AlarmManager.
     */
    private void cancelWithAlarmManager(@NonNull Alarm alarm) {
        PendingIntent pendingIntent = createAlarmPendingIntent(alarm);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
        Log.d(TAG, "Cancelled AlarmManager alarm: " + alarm.getId());
    }

    /**
     * Enable or disable an alarm.
     *
     * @param alarmId The alarm ID
     * @param enabled true to enable, false to disable
     * @return The updated alarm, or null if not found
     */
    public Alarm setAlarmEnabled(@NonNull String alarmId, boolean enabled) {
        Alarm alarm = repository.setAlarmEnabled(alarmId, enabled);
        if (alarm == null) {
            return null;
        }

        if (enabled && alarm.isFuture()) {
            scheduleWithAlarmManager(alarm);
        } else {
            cancelWithAlarmManager(alarm);
        }

        Log.i(TAG, "Alarm " + alarmId + " enabled: " + enabled);
        return alarm;
    }

    /**
     * Reschedule all enabled, future alarms.
     * Called after boot or when alarms need to be refreshed.
     */
    public void rescheduleAllAlarms() {
        List<Alarm> alarms = repository.getFutureEnabledAlarms();
        Log.i(TAG, "Rescheduling " + alarms.size() + " alarms");

        for (Alarm alarm : alarms) {
            scheduleWithAlarmManager(alarm);
        }
    }

    /**
     * Cancel all scheduled alarms.
     */
    public void cancelAllAlarms() {
        List<Alarm> alarms = repository.getAllAlarms();
        for (Alarm alarm : alarms) {
            cancelWithAlarmManager(alarm);
        }
        Log.i(TAG, "Cancelled all " + alarms.size() + " alarms");
    }

    /**
     * Get the next scheduled alarm.
     *
     * @return The next alarm to trigger, or null if none
     */
    public Alarm getNextAlarm() {
        List<Alarm> alarms = repository.getFutureEnabledAlarms();
        if (alarms.isEmpty()) {
            return null;
        }

        Alarm next = null;
        for (Alarm alarm : alarms) {
            if (next == null || alarm.getTimeMillis() < next.getTimeMillis()) {
                next = alarm;
            }
        }
        return next;
    }

    /**
     * Create a PendingIntent for an alarm.
     */
    private PendingIntent createAlarmPendingIntent(@NonNull Alarm alarm) {
        Intent intent = new Intent(context, AlarmTriggerReceiver.class);
        intent.setAction(ACTION_ALARM_TRIGGER);
        intent.putExtra(EXTRA_ALARM_ID, alarm.getId());
        intent.putExtra(EXTRA_ALARM_LABEL, alarm.getLabel());
        intent.putExtra(EXTRA_STOP_SIGNAL, alarm.getStopSignal());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(
                context,
                alarm.getRequestCode(),
                intent,
                flags
        );
    }

    // --- Repository Listener Callbacks ---

    @Override
    public void onAlarmAdded(Alarm alarm) {
        // Already handled in scheduleAlarm()
    }

    @Override
    public void onAlarmUpdated(Alarm alarm) {
        if (alarm.isEnabled() && alarm.isFuture()) {
            scheduleWithAlarmManager(alarm);
        } else {
            cancelWithAlarmManager(alarm);
        }
    }

    @Override
    public void onAlarmRemoved(String alarmId) {
        // Create a dummy alarm to cancel
        // The actual alarm was already removed, so we need the request code
        Intent intent = new Intent(context, AlarmTriggerReceiver.class);
        intent.setAction(ACTION_ALARM_TRIGGER);

        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                Math.abs(alarmId.hashCode()),
                intent,
                flags
        );

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    @Override
    public void onAlarmsCleared() {
        cancelAllAlarms();
    }

    // --- Utility Methods ---

    /**
     * Check if exact alarms are allowed (Android 12+).
     */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager.canScheduleExactAlarms();
        }
        return true;
    }

    /**
     * Get all alarms from repository.
     */
    public List<Alarm> getAllAlarms() {
        return repository.getAllAlarms();
    }

    /**
     * Get an alarm by ID.
     */
    public Alarm getAlarm(@NonNull String alarmId) {
        return repository.getAlarm(alarmId);
    }

    /**
     * Initialize the scheduler and repository.
     */
    public void initialize() {
        repository.initialize();
    }
}
