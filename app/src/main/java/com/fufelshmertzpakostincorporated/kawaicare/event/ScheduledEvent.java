package com.fufelshmertzpakostincorporated.kawaicare.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified data model representing a scheduled event (Alarm or Reminder).
 * 
 * This class replaces the existing Alarm model with a more comprehensive event system
 * that supports both alarms and reminders with recurrence, custom payloads, and
 * configurable termination gestures.
 * 
 * Features:
 * - Trigger Time: UTC milliseconds for precise scheduling
 * - Event Type: ALARM or REMINDER (affects notification behavior)
 * - Recurrence: None, Daily, or Weekly with day-of-week bitmask
 * - Payload: Animation and sound effect to play when triggered
 * - Termination: Gesture/signal required to dismiss the event
 * - State: Enabled/disabled toggle
 */
public class ScheduledEvent {

    /**
     * Event type enumeration.
     */
    public enum EventType {
        /** Full alarm with wake screen, vibration, and gesture dismissal */
        ALARM,
        /** Gentle reminder with notification, optional vibration */
        REMINDER
    }

    // Unique identifier
    private final String id;
    
    // Event type (Alarm vs Reminder)
    private final EventType eventType;
    
    // Scheduling
    private final long triggerTimeMillis;
    private final Recurrence recurrence;
    
    // Payload - what to display/play when triggered
    private final EventPayload payload;
    
    // Termination - how to dismiss the event
    private final String terminationSignal;
    
    // State
    private volatile boolean enabled;
    
    // Metadata
    private final String label;
    private final long createdAt;
    private volatile long lastTriggeredAt;

    /**
     * Create a new event with auto-generated ID.
     */
    public ScheduledEvent(
            EventType eventType,
            long triggerTimeMillis,
            @Nullable String label,
            @Nullable Recurrence recurrence,
            @Nullable EventPayload payload,
            @Nullable String terminationSignal) {
        this(
                UUID.randomUUID().toString(),
                eventType,
                triggerTimeMillis,
                label,
                recurrence,
                payload,
                terminationSignal,
                true,
                System.currentTimeMillis(),
                0
        );
    }

    /**
     * Full constructor for restoring from storage.
     */
    public ScheduledEvent(
            String id,
            EventType eventType,
            long triggerTimeMillis,
            @Nullable String label,
            @Nullable Recurrence recurrence,
            @Nullable EventPayload payload,
            @Nullable String terminationSignal,
            boolean enabled,
            long createdAt,
            long lastTriggeredAt) {
        this.id = id;
        this.eventType = eventType != null ? eventType : EventType.ALARM;
        this.triggerTimeMillis = triggerTimeMillis;
        this.label = label != null ? label : "";
        this.recurrence = recurrence != null ? recurrence : Recurrence.none();
        this.payload = payload != null ? payload : EventPayload.defaultPayload();
        this.terminationSignal = terminationSignal != null ? terminationSignal : SignalRegistry.SIGNAL_SHAKE;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.lastTriggeredAt = lastTriggeredAt;
    }

    // --- Getters ---

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public EventType getEventType() {
        return eventType;
    }

    public long getTriggerTimeMillis() {
        return triggerTimeMillis;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    @NonNull
    public Recurrence getRecurrence() {
        return recurrence;
    }

    @NonNull
    public EventPayload getPayload() {
        return payload;
    }

    @NonNull
    public String getTerminationSignal() {
        return terminationSignal;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    // --- State Modifiers ---

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setLastTriggeredAt(long lastTriggeredAt) {
        this.lastTriggeredAt = lastTriggeredAt;
    }

    // --- Utility Methods ---

    /**
     * Check if this is an alarm (vs reminder).
     */
    public boolean isAlarm() {
        return eventType == EventType.ALARM;
    }

    /**
     * Check if this is a reminder (vs alarm).
     */
    public boolean isReminder() {
        return eventType == EventType.REMINDER;
    }

    /**
     * Check if this event has any recurrence.
     */
    public boolean isRecurring() {
        return recurrence != null && !recurrence.isOneTime();
    }

    /**
     * Check if this event's trigger time is in the future.
     */
    public boolean isFuture() {
        return triggerTimeMillis > System.currentTimeMillis();
    }

    /**
     * Check if this event should trigger now (within 1 minute tolerance).
     */
    public boolean shouldTriggerNow() {
        if (!enabled) return false;
        
        long now = System.currentTimeMillis();
        
        // For non-recurring events, check if within trigger window
        if (!isRecurring()) {
            return Math.abs(triggerTimeMillis - now) < 60000;
        }
        
        // For recurring events, check if the current day matches
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        
        if (!recurrence.occursOnDay(dayOfWeek)) {
            return false;
        }
        
        // Extract time-of-day from trigger time and compare
        Calendar triggerCal = Calendar.getInstance();
        triggerCal.setTimeInMillis(triggerTimeMillis);
        
        int triggerHour = triggerCal.get(Calendar.HOUR_OF_DAY);
        int triggerMinute = triggerCal.get(Calendar.MINUTE);
        
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        int currentMinute = calendar.get(Calendar.MINUTE);
        
        return triggerHour == currentHour && Math.abs(triggerMinute - currentMinute) <= 1;
    }

    /**
     * Calculate the next trigger time for recurring events.
     * Returns the original trigger time for non-recurring events.
     * 
     * @return Next trigger time in milliseconds, or -1 if event won't recur
     */
    public long getNextTriggerTime() {
        if (!isRecurring()) {
            return isFuture() ? triggerTimeMillis : -1;
        }

        long now = System.currentTimeMillis();
        Calendar calendar = Calendar.getInstance();
        
        // Extract time-of-day from the original trigger time
        Calendar triggerCal = Calendar.getInstance();
        triggerCal.setTimeInMillis(triggerTimeMillis);
        int triggerHour = triggerCal.get(Calendar.HOUR_OF_DAY);
        int triggerMinute = triggerCal.get(Calendar.MINUTE);
        int triggerSecond = triggerCal.get(Calendar.SECOND);

        // Start from today
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, triggerHour);
        calendar.set(Calendar.MINUTE, triggerMinute);
        calendar.set(Calendar.SECOND, triggerSecond);
        calendar.set(Calendar.MILLISECOND, 0);

        // If today's trigger time has passed, start from tomorrow
        if (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Find the next valid day for WEEKLY recurrence
        if (recurrence.getType() == Recurrence.Type.WEEKLY) {
            for (int i = 0; i < 7; i++) {
                int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
                if (recurrence.occursOnDay(dayOfWeek)) {
                    return calendar.getTimeInMillis();
                }
                calendar.add(Calendar.DAY_OF_YEAR, 1);
            }
            return -1; // No valid day found (shouldn't happen with valid mask)
        }

        // DAILY: return calculated time
        return calendar.getTimeInMillis();
    }

    /**
     * Get a unique request code for PendingIntent.
     */
    public int getRequestCode() {
        return Math.abs(id.hashCode());
    }

    // --- JSON Serialization ---

    /**
     * Serialize event to JSON for storage or transmission.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("event_type", eventType.name());
        json.put("trigger_time_millis", triggerTimeMillis);
        json.put("label", label);
        json.put("recurrence", recurrence.toJson());
        json.put("payload", payload.toJson());
        json.put("termination_signal", terminationSignal);
        json.put("enabled", enabled);
        json.put("created_at", createdAt);
        json.put("last_triggered_at", lastTriggeredAt);
        return json;
    }

    /**
     * Deserialize event from JSON.
     */
    public static ScheduledEvent fromJson(JSONObject json) throws JSONException {
        String id = json.getString("id");
        
        EventType eventType;
        try {
            eventType = EventType.valueOf(json.optString("event_type", "ALARM").toUpperCase());
        } catch (IllegalArgumentException e) {
            eventType = EventType.ALARM;
        }

        long triggerTimeMillis = json.getLong("trigger_time_millis");
        String label = json.optString("label", "");
        
        Recurrence recurrence = Recurrence.none();
        if (json.has("recurrence")) {
            recurrence = Recurrence.fromJson(json.getJSONObject("recurrence"));
        }
        
        EventPayload payload = EventPayload.defaultPayload();
        if (json.has("payload")) {
            payload = EventPayload.fromJson(json.getJSONObject("payload"));
        }
        
        String terminationSignal = json.optString("termination_signal", SignalRegistry.SIGNAL_SHAKE);
        boolean enabled = json.optBoolean("enabled", true);
        long createdAt = json.optLong("created_at", System.currentTimeMillis());
        long lastTriggeredAt = json.optLong("last_triggered_at", 0);

        return new ScheduledEvent(
                id,
                eventType,
                triggerTimeMillis,
                label,
                recurrence,
                payload,
                terminationSignal,
                enabled,
                createdAt,
                lastTriggeredAt
        );
    }

    // --- Builder Pattern for Immutable Updates ---

    /**
     * Create a copy with updated enabled state.
     */
    public ScheduledEvent withEnabled(boolean newEnabled) {
        return new ScheduledEvent(
                id, eventType, triggerTimeMillis, label, recurrence, payload,
                terminationSignal, newEnabled, createdAt, lastTriggeredAt
        );
    }

    /**
     * Create a copy with updated trigger time.
     */
    public ScheduledEvent withTriggerTime(long newTriggerTimeMillis) {
        return new ScheduledEvent(
                id, eventType, newTriggerTimeMillis, label, recurrence, payload,
                terminationSignal, enabled, createdAt, lastTriggeredAt
        );
    }

    /**
     * Create a copy with updated recurrence.
     */
    public ScheduledEvent withRecurrence(Recurrence newRecurrence) {
        return new ScheduledEvent(
                id, eventType, triggerTimeMillis, label, newRecurrence, payload,
                terminationSignal, enabled, createdAt, lastTriggeredAt
        );
    }

    /**
     * Create a copy with updated payload.
     */
    public ScheduledEvent withPayload(EventPayload newPayload) {
        return new ScheduledEvent(
                id, eventType, triggerTimeMillis, label, recurrence, newPayload,
                terminationSignal, enabled, createdAt, lastTriggeredAt
        );
    }

    /**
     * Create a copy with updated termination signal.
     */
    public ScheduledEvent withTerminationSignal(String newSignal) {
        return new ScheduledEvent(
                id, eventType, triggerTimeMillis, label, recurrence, payload,
                newSignal, enabled, createdAt, lastTriggeredAt
        );
    }

    /**
     * Create a copy with updated label.
     */
    public ScheduledEvent withLabel(String newLabel) {
        return new ScheduledEvent(
                id, eventType, triggerTimeMillis, newLabel, recurrence, payload,
                terminationSignal, enabled, createdAt, lastTriggeredAt
        );
    }

    // --- Object Methods ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScheduledEvent event = (ScheduledEvent) o;
        return id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @NonNull
    @Override
    public String toString() {
        return "ScheduledEvent{" +
                "id='" + id + '\'' +
                ", eventType=" + eventType +
                ", triggerTimeMillis=" + triggerTimeMillis +
                ", label='" + label + '\'' +
                ", recurrence=" + recurrence +
                ", terminationSignal='" + terminationSignal + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
