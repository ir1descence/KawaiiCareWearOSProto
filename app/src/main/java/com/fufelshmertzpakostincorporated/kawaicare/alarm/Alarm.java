package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.UUID;

/**
 * Data model representing a scheduled alarm.
 * Immutable except for enabled state.
 */
public class Alarm {

    private final String id;
    private final long timeMillis;
    private final String label;
    private final String stopSignal;
    private volatile boolean enabled;
    private final long createdAt;

    /**
     * Create a new alarm with auto-generated ID.
     *
     * @param timeMillis The trigger time in epoch milliseconds
     * @param label      Human-readable label for the alarm
     * @param stopSignal The signal required to dismiss this alarm
     */
    public Alarm(long timeMillis, String label, String stopSignal) {
        this(UUID.randomUUID().toString(), timeMillis, label, stopSignal, true, System.currentTimeMillis());
    }

    /**
     * Full constructor for restoring from storage.
     */
    public Alarm(String id, long timeMillis, String label, String stopSignal, boolean enabled, long createdAt) {
        this.id = id;
        this.timeMillis = timeMillis;
        this.label = label != null ? label : "";
        this.stopSignal = stopSignal != null ? stopSignal : SignalRegistry.SIGNAL_SHAKE;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    // --- Getters ---

    @NonNull
    public String getId() {
        return id;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    @NonNull
    public String getStopSignal() {
        return stopSignal;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    // --- Mutable State ---

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // --- Utility Methods ---

    /**
     * Check if this alarm is in the future.
     */
    public boolean isFuture() {
        return timeMillis > System.currentTimeMillis();
    }

    /**
     * Check if this alarm should trigger now (within 1 minute tolerance).
     */
    public boolean shouldTriggerNow() {
        long now = System.currentTimeMillis();
        return enabled && Math.abs(timeMillis - now) < 60000;
    }

    /**
     * Get a unique request code for PendingIntent.
     * Uses hashCode of ID truncated to positive int.
     */
    public int getRequestCode() {
        return Math.abs(id.hashCode());
    }

    // --- JSON Serialization ---

    /**
     * Serialize alarm to JSON for storage or transmission.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("time_millis", timeMillis);
        json.put("label", label);
        json.put("stop_signal", stopSignal);
        json.put("enabled", enabled);
        json.put("created_at", createdAt);
        return json;
    }

    /**
     * Deserialize alarm from JSON.
     */
    public static Alarm fromJson(JSONObject json) throws JSONException {
        return new Alarm(
                json.getString("id"),
                json.getLong("time_millis"),
                json.optString("label", ""),
                json.optString("stop_signal", SignalRegistry.SIGNAL_SHAKE),
                json.optBoolean("enabled", true),
                json.optLong("created_at", System.currentTimeMillis())
        );
    }

    /**
     * Create a copy with updated enabled state.
     */
    public Alarm withEnabled(boolean newEnabled) {
        Alarm copy = new Alarm(id, timeMillis, label, stopSignal, newEnabled, createdAt);
        return copy;
    }

    /**
     * Create a copy with updated time.
     */
    public Alarm withTime(long newTimeMillis) {
        return new Alarm(id, newTimeMillis, label, stopSignal, enabled, createdAt);
    }

    // --- Object Methods ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Alarm alarm = (Alarm) o;
        return id.equals(alarm.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @NonNull
    @Override
    public String toString() {
        return "Alarm{" +
                "id='" + id + '\'' +
                ", timeMillis=" + timeMillis +
                ", label='" + label + '\'' +
                ", stopSignal='" + stopSignal + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
