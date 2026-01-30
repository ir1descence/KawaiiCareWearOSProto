package com.fufelshmertzpakostincorporated.kawaicare.event;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Recurrence configuration for scheduled events.
 * Supports None, Daily, and Weekly (with day-of-week bitmask) recurrence patterns.
 */
public class Recurrence {

    /**
     * Recurrence type enum.
     */
    public enum Type {
        /** One-time event, no recurrence */
        NONE,
        /** Repeats every day at the same time */
        DAILY,
        /** Repeats weekly on selected days */
        WEEKLY
    }

    // Day-of-week bitmask constants (Sunday = 1, Saturday = 64)
    public static final int SUNDAY    = 1 << 0;  // 1
    public static final int MONDAY    = 1 << 1;  // 2
    public static final int TUESDAY   = 1 << 2;  // 4
    public static final int WEDNESDAY = 1 << 3;  // 8
    public static final int THURSDAY  = 1 << 4;  // 16
    public static final int FRIDAY    = 1 << 5;  // 32
    public static final int SATURDAY  = 1 << 6;  // 64

    /** All weekdays (Monday-Friday) */
    public static final int WEEKDAYS = MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY;
    
    /** Weekend days (Saturday-Sunday) */
    public static final int WEEKENDS = SATURDAY | SUNDAY;
    
    /** Every day of the week */
    public static final int EVERY_DAY = SUNDAY | MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY | SATURDAY;

    private final Type type;
    private final int dayOfWeekMask;

    /**
     * Create a recurrence with no day mask (for NONE or DAILY types).
     */
    public Recurrence(Type type) {
        this(type, type == Type.WEEKLY ? EVERY_DAY : 0);
    }

    /**
     * Create a recurrence with a specific day-of-week mask.
     * 
     * @param type The recurrence type
     * @param dayOfWeekMask Bitmask of days (only used for WEEKLY type)
     */
    public Recurrence(Type type, int dayOfWeekMask) {
        this.type = type != null ? type : Type.NONE;
        this.dayOfWeekMask = (type == Type.WEEKLY) ? (dayOfWeekMask & EVERY_DAY) : 0;
    }

    // --- Static Factory Methods ---

    /**
     * Create a non-recurring (one-time) recurrence.
     */
    public static Recurrence none() {
        return new Recurrence(Type.NONE);
    }

    /**
     * Create a daily recurrence.
     */
    public static Recurrence daily() {
        return new Recurrence(Type.DAILY);
    }

    /**
     * Create a weekly recurrence on specific days.
     * 
     * @param dayMask Bitmask of days (use SUNDAY, MONDAY, etc. constants)
     */
    public static Recurrence weekly(int dayMask) {
        return new Recurrence(Type.WEEKLY, dayMask);
    }

    /**
     * Create a weekly recurrence for weekdays only.
     */
    public static Recurrence weekdays() {
        return new Recurrence(Type.WEEKLY, WEEKDAYS);
    }

    /**
     * Create a weekly recurrence for weekends only.
     */
    public static Recurrence weekends() {
        return new Recurrence(Type.WEEKLY, WEEKENDS);
    }

    // --- Getters ---

    public Type getType() {
        return type;
    }

    public int getDayOfWeekMask() {
        return dayOfWeekMask;
    }

    /**
     * Check if this is a non-recurring (one-time) event.
     */
    public boolean isOneTime() {
        return type == Type.NONE;
    }

    /**
     * Check if the event should occur on a specific day of week.
     * 
     * @param dayOfWeek Calendar day constant (Calendar.SUNDAY = 1, Calendar.SATURDAY = 7)
     * @return true if the event should occur on that day
     */
    public boolean occursOnDay(int dayOfWeek) {
        if (type == Type.NONE) {
            return true; // One-time events occur on their scheduled day
        }
        if (type == Type.DAILY) {
            return true;
        }
        // WEEKLY: Check the bitmask
        // Convert Calendar day (1-7) to bitmask position (0-6)
        int dayBit = 1 << (dayOfWeek - 1);
        return (dayOfWeekMask & dayBit) != 0;
    }

    /**
     * Get a human-readable description of this recurrence.
     */
    public String getDescription() {
        switch (type) {
            case NONE:
                return "Once";
            case DAILY:
                return "Daily";
            case WEEKLY:
                if (dayOfWeekMask == EVERY_DAY) {
                    return "Every day";
                } else if (dayOfWeekMask == WEEKDAYS) {
                    return "Weekdays";
                } else if (dayOfWeekMask == WEEKENDS) {
                    return "Weekends";
                } else {
                    return "Weekly (" + describeDays() + ")";
                }
            default:
                return "Unknown";
        }
    }

    private String describeDays() {
        StringBuilder sb = new StringBuilder();
        String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        
        for (int i = 0; i < 7; i++) {
            if ((dayOfWeekMask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(dayNames[i]);
            }
        }
        return sb.toString();
    }

    // --- JSON Serialization ---

    /**
     * Serialize to JSON object.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", type.name());
        if (type == Type.WEEKLY) {
            json.put("day_of_week_mask", dayOfWeekMask);
        }
        return json;
    }

    /**
     * Deserialize from JSON object.
     */
    public static Recurrence fromJson(JSONObject json) throws JSONException {
        String typeStr = json.optString("type", "NONE");
        Type type;
        try {
            type = Type.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = Type.NONE;
        }

        int dayMask = json.optInt("day_of_week_mask", EVERY_DAY);
        return new Recurrence(type, dayMask);
    }

    // --- Object Methods ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Recurrence that = (Recurrence) o;
        return type == that.type && dayOfWeekMask == that.dayOfWeekMask;
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + dayOfWeekMask;
    }

    @NonNull
    @Override
    public String toString() {
        return "Recurrence{" +
                "type=" + type +
                ", dayOfWeekMask=" + Integer.toBinaryString(dayOfWeekMask) +
                '}';
    }
}
