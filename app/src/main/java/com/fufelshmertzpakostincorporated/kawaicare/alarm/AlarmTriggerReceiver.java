package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.event.EventRepository;
import com.fufelshmertzpakostincorporated.kawaicare.event.EventScheduler;
import com.fufelshmertzpakostincorporated.kawaicare.event.Recurrence;
import com.fufelshmertzpakostincorporated.kawaicare.event.ScheduledEvent;
import com.fufelshmertzpakostincorporated.kawaicare.ui.MainActivity;

/**
 * BroadcastReceiver that triggers when a scheduled event fires.
 * 
 * Responsibilities:
 * - Wake the device if sleeping
 * - Set the active stop signal for dismissal
 * - Launch MainActivity to show event UI
 * - Update event repositories
 */
public class AlarmTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmTriggerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        
        // Handle event-based trigger
        if (EventScheduler.ACTION_EVENT_TRIGGER.equals(action)) {
            handleEventTrigger(context, intent);
            return;
        }
        
        Log.w(TAG, "Unknown action received: " + action);
    }

    /**
     * Handle event-based trigger from EventScheduler.
     */
    private void handleEventTrigger(Context context, Intent intent) {
        String eventId = intent.getStringExtra(EventScheduler.EXTRA_EVENT_ID);
        String eventType = intent.getStringExtra(EventScheduler.EXTRA_EVENT_TYPE);
        String label = intent.getStringExtra(EventScheduler.EXTRA_EVENT_LABEL);
        String terminationSignal = intent.getStringExtra(EventScheduler.EXTRA_TERMINATION_SIGNAL);
        String animation = intent.getStringExtra(EventScheduler.EXTRA_ANIMATION);
        boolean vibrate = intent.getBooleanExtra(EventScheduler.EXTRA_VIBRATE, true);

        Log.i(TAG, "Event triggered: " + eventId + " [" + eventType + "] - " + label);

        // Acquire wake lock to ensure event processing completes
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;

        try {
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "KawaiiCare:EventTriggerWakeLock"
                );
                wakeLock.acquire(10000); // 10 second timeout
            }

            // Set up the event state
            setupEventState(context, terminationSignal);

            // Launch MainActivity to handle the event
            launchMainActivity(context, eventId, eventType, label, terminationSignal, animation, vibrate);

            // Broadcast event triggered for any other listeners
            broadcastEventTriggered(context, eventId, eventType, label, terminationSignal);

            // Handle event completion (disable one-shot or reschedule recurring)
            handleEventCompletion(context, eventId);

        } catch (Exception e) {
            Log.e(TAG, "Error processing event trigger", e);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    /**
     * Set up the event state in repositories.
     */
    private void setupEventState(Context context, String terminationSignal) {
        // Set alarm status ON (events use the same alarm status mechanism)
        AlarmStatusRepository.getInstance().setAlarmStatus(true);

        // Set the active stop signal for this event
        if (terminationSignal != null && !terminationSignal.isEmpty()) {
            String customGesturePath = null;
            if (SignalRegistry.SIGNAL_CUSTOM.equals(terminationSignal)) {
                SignalRegistry registry = new SignalRegistry(context);
                customGesturePath = registry.getCustomGestureFilePath();
            }
            AlarmStatusRepository.getInstance().setActiveStopSignal(terminationSignal, customGesturePath);
        }

        Log.d(TAG, "Event state set: ON, signal=" + terminationSignal);
    }

    /**
     * Launch MainActivity to show the event UI.
     */
    private void launchMainActivity(Context context, String eventId, String eventType, 
            String label, String terminationSignal, String animation, boolean vibrate) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setAction(MainActivity.ACTION_EVENT_TRIGGERED);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchIntent.putExtra("event_id", eventId);
        launchIntent.putExtra("event_type", eventType);
        launchIntent.putExtra("event_label", label);
        launchIntent.putExtra("termination_signal", terminationSignal);
        launchIntent.putExtra("animation", animation);
        launchIntent.putExtra("vibrate", vibrate);

        context.startActivity(launchIntent);
        Log.d(TAG, "Launched MainActivity for event: " + eventId);
    }

    /**
     * Broadcast that an event has triggered.
     */
    private void broadcastEventTriggered(Context context, String eventId, String eventType, 
            String label, String terminationSignal) {
        Intent broadcastIntent = new Intent(MainActivity.ACTION_EVENT_TRIGGERED);
        broadcastIntent.setPackage(context.getPackageName());
        broadcastIntent.putExtra("event_id", eventId);
        broadcastIntent.putExtra("event_type", eventType);
        broadcastIntent.putExtra("event_label", label);
        broadcastIntent.putExtra("termination_signal", terminationSignal);
        context.sendBroadcast(broadcastIntent);
    }

    /**
     * Handle event completion (disable one-shot or reschedule recurring events).
     */
    private void handleEventCompletion(Context context, String eventId) {
        if (eventId == null) {
            return;
        }

        try {
            EventRepository repository = EventRepository.getInstance(context);
            ScheduledEvent event = repository.getEvent(eventId);

            if (event != null) {
                // Update last triggered timestamp
                event.setLastTriggeredAt(System.currentTimeMillis());
                
                // Check if recurring
                if (event.getRecurrence().getType() != Recurrence.Type.NONE) {
                    // Recurring event - update and let EventScheduler reschedule
                    repository.updateEvent(event);
                    Log.d(TAG, "Updated recurring event for rescheduling: " + eventId);
                } else {
                    // One-shot event - disable it
                    event.setEnabled(false);
                    repository.updateEvent(event);
                    Log.d(TAG, "Disabled one-shot event: " + eventId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling event completion", e);
        }
    }
}
