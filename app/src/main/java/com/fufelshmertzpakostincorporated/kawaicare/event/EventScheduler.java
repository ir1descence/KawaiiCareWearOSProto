package com.fufelshmertzpakostincorporated.kawaicare.event;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fufelshmertzpakostincorporated.kawaicare.alarm.AlarmTriggerReceiver;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;

import java.util.List;

/**
 * Scheduler for managing ScheduledEvent timing with Android's AlarmManager.
 * 
 * Handles both ALARM and REMINDER event types with precise scheduling.
 * Uses setExactAndAllowWhileIdle for Doze-mode compatibility.
 * 
 * Features:
 * - Scheduling events with exact timing
 * - Support for recurring events (daily, weekly)
 * - Automatic rescheduling after boot
 * - Signal validation via SignalRegistry
 */
public class EventScheduler implements EventRepository.EventChangeListener {

    private static final String TAG = "EventScheduler";

    /** Intent action when event triggers */
    public static final String ACTION_EVENT_TRIGGER = 
            "com.fufelshmertzpakostincorporated.kawaicare.EVENT_TRIGGER";

    /** Intent extra for event ID */
    public static final String EXTRA_EVENT_ID = "event_id";

    /** Intent extra for event type */
    public static final String EXTRA_EVENT_TYPE = "event_type";

    /** Intent extra for event label */
    public static final String EXTRA_EVENT_LABEL = "event_label";

    /** Intent extra for termination signal */
    public static final String EXTRA_TERMINATION_SIGNAL = "termination_signal";

    /** Intent extra for animation */
    public static final String EXTRA_ANIMATION = "animation";

    /** Intent extra for vibrate */
    public static final String EXTRA_VIBRATE = "vibrate";

    private static volatile EventScheduler instance;

    private final Context context;
    private final AlarmManager alarmManager;
    private final EventRepository repository;
    private final SignalRegistry signalRegistry;

    private EventScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.repository = EventRepository.getInstance(context);
        this.signalRegistry = new SignalRegistry(context);

        // Listen for event changes
        repository.addListener(this);
    }

    /**
     * Get singleton instance.
     */
    public static EventScheduler getInstance(Context context) {
        if (instance == null) {
            synchronized (EventScheduler.class) {
                if (instance == null) {
                    instance = new EventScheduler(context);
                }
            }
        }
        return instance;
    }

    /**
     * Initialize the scheduler and repository.
     */
    public void initialize() {
        repository.initialize();
        Log.i(TAG, "EventScheduler initialized with " + repository.getEventCount() + " events");
    }

    // =========================================
    // Event Scheduling
    // =========================================

    /**
     * Schedule a new event.
     * Validates the termination signal and schedules with AlarmManager.
     *
     * @param event The event to schedule
     * @return true if scheduled successfully
     */
    public boolean scheduleEvent(@NonNull ScheduledEvent event) {
        // Validate the termination signal
        SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(event.getTerminationSignal());
        if (!validation.isValid()) {
            Log.e(TAG, "Invalid termination signal for event " + event.getId() + ": " + validation.getErrorMessage());
            return false;
        }

        // Add to repository
        if (!repository.addEvent(event)) {
            Log.e(TAG, "Failed to add event to repository: " + event.getId());
            return false;
        }

        // Schedule with AlarmManager
        scheduleWithAlarmManager(event);

        Log.i(TAG, "Scheduled event: " + event.getId() + " for " + event.getTriggerTimeMillis());
        return true;
    }

    /**
     * Schedule an event with AlarmManager.
     */
    public void scheduleWithAlarmManager(@NonNull ScheduledEvent event) {
        if (!event.isEnabled()) {
            Log.d(TAG, "Skipping disabled event: " + event.getId());
            return;
        }

        // Get the next trigger time (handles recurring events)
        long triggerTime = event.getNextTriggerTime();
        if (triggerTime <= 0) {
            Log.w(TAG, "Event has no valid trigger time: " + event.getId());
            return;
        }

        PendingIntent pendingIntent = createEventPendingIntent(event);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: Check for exact alarm permission
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                    );
                } else {
                    Log.w(TAG, "Exact alarm permission not granted, using inexact");
                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                    );
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0+: Use setExactAndAllowWhileIdle for Doze
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            } else {
                // Older Android versions
                alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                );
            }

            Log.d(TAG, "Event scheduled with AlarmManager: " + event.getId() + 
                    " at " + triggerTime + " (in " + (triggerTime - System.currentTimeMillis()) + "ms)");

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException scheduling event", e);
        }
    }

    /**
     * Cancel a scheduled event.
     *
     * @param eventId The event ID to cancel
     * @param removeFromRepository If true, also removes from repository
     * @return true if operation succeeded
     */
    public boolean cancelEvent(@NonNull String eventId, boolean removeFromRepository) {
        ScheduledEvent event = repository.getEvent(eventId);
        if (event == null) {
            Log.w(TAG, "Event not found for cancellation: " + eventId);
            return false;
        }

        // Cancel with AlarmManager
        cancelWithAlarmManager(event);

        if (removeFromRepository) {
            repository.removeEvent(eventId);
        }

        Log.i(TAG, "Cancelled event: " + eventId);
        return true;
    }

    /**
     * Cancel event with AlarmManager.
     */
    private void cancelWithAlarmManager(@NonNull ScheduledEvent event) {
        PendingIntent pendingIntent = createEventPendingIntent(event);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
        Log.d(TAG, "Cancelled AlarmManager event: " + event.getId());
    }

    /**
     * Enable or disable an event.
     *
     * @param eventId The event ID
     * @param enabled true to enable, false to disable
     * @return The updated event, or null if not found
     */
    @Nullable
    public ScheduledEvent setEventEnabled(@NonNull String eventId, boolean enabled) {
        ScheduledEvent event = repository.setEventEnabled(eventId, enabled);
        if (event == null) {
            return null;
        }

        if (enabled) {
            scheduleWithAlarmManager(event);
        } else {
            cancelWithAlarmManager(event);
        }

        Log.i(TAG, "Event " + eventId + " enabled: " + enabled);
        return event;
    }

    /**
     * Reschedule all enabled events.
     * Called after boot or when events need to be refreshed.
     */
    public void rescheduleAllEvents() {
        List<ScheduledEvent> events = repository.getFutureEnabledEvents();
        Log.i(TAG, "Rescheduling " + events.size() + " events");

        for (ScheduledEvent event : events) {
            scheduleWithAlarmManager(event);
        }
    }

    /**
     * Cancel all scheduled events.
     */
    public void cancelAllEvents() {
        List<ScheduledEvent> events = repository.getAllEvents();
        for (ScheduledEvent event : events) {
            cancelWithAlarmManager(event);
        }
        Log.i(TAG, "Cancelled all " + events.size() + " events");
    }

    /**
     * Handle event being triggered.
     * Updates the last triggered time and reschedules for recurring events.
     *
     * @param eventId The ID of the triggered event
     * @return The event that was triggered, or null if not found
     */
    @Nullable
    public ScheduledEvent onEventTriggered(@NonNull String eventId) {
        ScheduledEvent event = repository.getEvent(eventId);
        if (event == null) {
            Log.w(TAG, "Triggered event not found: " + eventId);
            return null;
        }

        // Update last triggered time
        event.setLastTriggeredAt(System.currentTimeMillis());
        repository.updateEvent(event);

        // If recurring, schedule next occurrence
        if (event.isRecurring() && event.isEnabled()) {
            long nextTrigger = event.getNextTriggerTime();
            if (nextTrigger > 0) {
                Log.d(TAG, "Rescheduling recurring event " + eventId + " for " + nextTrigger);
                scheduleWithAlarmManager(event);
            }
        } else if (!event.isRecurring()) {
            // One-time event: disable it after triggering
            event.setEnabled(false);
            repository.updateEvent(event);
            Log.d(TAG, "One-time event " + eventId + " disabled after triggering");
        }

        return event;
    }

    // =========================================
    // Query Methods
    // =========================================

    /**
     * Get the next event to trigger.
     */
    @Nullable
    public ScheduledEvent getNextEvent() {
        return repository.getNextEvent();
    }

    /**
     * Get all events.
     */
    public List<ScheduledEvent> getAllEvents() {
        return repository.getAllEvents();
    }

    /**
     * Get an event by ID.
     */
    @Nullable
    public ScheduledEvent getEvent(@NonNull String eventId) {
        return repository.getEvent(eventId);
    }

    /**
     * Get events by type.
     */
    public List<ScheduledEvent> getEventsByType(ScheduledEvent.EventType type) {
        return repository.getEventsByType(type);
    }

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
     * Validate a termination signal.
     */
    public SignalRegistry.ValidationResult validateSignal(String signal) {
        return signalRegistry.validateSignal(signal);
    }

    // =========================================
    // PendingIntent Creation
    // =========================================

    /**
     * Create a PendingIntent for an event.
     */
    private PendingIntent createEventPendingIntent(@NonNull ScheduledEvent event) {
        Intent intent = new Intent(context, AlarmTriggerReceiver.class);
        intent.setAction(ACTION_EVENT_TRIGGER);
        intent.putExtra(EXTRA_EVENT_ID, event.getId());
        intent.putExtra(EXTRA_EVENT_TYPE, event.getEventType().name());
        intent.putExtra(EXTRA_EVENT_LABEL, event.getLabel());
        intent.putExtra(EXTRA_TERMINATION_SIGNAL, event.getTerminationSignal());
        intent.putExtra(EXTRA_ANIMATION, event.getPayload().getAnimation());
        intent.putExtra(EXTRA_VIBRATE, event.getPayload().shouldVibrate());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(
                context,
                event.getRequestCode(),
                intent,
                flags
        );
    }

    // =========================================
    // Repository Listener Callbacks
    // =========================================

    @Override
    public void onEventAdded(ScheduledEvent event) {
        // Schedule when added
        if (event.isEnabled()) {
            scheduleWithAlarmManager(event);
        }
    }

    @Override
    public void onEventUpdated(ScheduledEvent event) {
        if (event.isEnabled()) {
            scheduleWithAlarmManager(event);
        } else {
            cancelWithAlarmManager(event);
        }
    }

    @Override
    public void onEventRemoved(String eventId) {
        // Create a dummy intent to cancel
        Intent intent = new Intent(context, AlarmTriggerReceiver.class);
        intent.setAction(ACTION_EVENT_TRIGGER);

        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                Math.abs(eventId.hashCode()),
                intent,
                flags
        );

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    @Override
    public void onEventsSynced(List<ScheduledEvent> events) {
        // Cancel all existing and reschedule new ones
        cancelAllEvents();
        for (ScheduledEvent event : events) {
            if (event.isEnabled()) {
                scheduleWithAlarmManager(event);
            }
        }
        Log.i(TAG, "Events synced and rescheduled: " + events.size());
    }

    @Override
    public void onEventsCleared() {
        cancelAllEvents();
    }
}
