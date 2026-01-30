package com.fufelshmertzpakostincorporated.kawaicare.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Payload configuration for a scheduled event.
 * Defines what animation and sound effect to play when the event triggers.
 */
public class EventPayload {

    /** Default animation state for alarms */
    public static final String DEFAULT_ALARM_ANIMATION = "ALARM";
    
    /** Default animation state for reminders */
    public static final String DEFAULT_REMINDER_ANIMATION = "GESTURE_ACTION";
    
    /** Default sound effect (null means use system default) */
    public static final String DEFAULT_SOUND = null;

    // Animation to play when event triggers
    private final String animation;
    
    // Sound effect to play (null for system default)
    private final String soundEffect;
    
    // Whether to vibrate
    private final boolean vibrate;
    
    // Vibration pattern (array of on/off durations in ms)
    private final long[] vibrationPattern;

    /**
     * Create a payload with just an animation.
     */
    public EventPayload(@Nullable String animation) {
        this(animation, null, true, null);
    }

    /**
     * Full constructor.
     */
    public EventPayload(
            @Nullable String animation,
            @Nullable String soundEffect,
            boolean vibrate,
            @Nullable long[] vibrationPattern) {
        this.animation = animation != null ? animation : DEFAULT_ALARM_ANIMATION;
        this.soundEffect = soundEffect;
        this.vibrate = vibrate;
        this.vibrationPattern = vibrationPattern;
    }

    // --- Static Factory Methods ---

    /**
     * Create a default payload for alarms.
     */
    public static EventPayload defaultPayload() {
        return new EventPayload(DEFAULT_ALARM_ANIMATION, null, true, null);
    }

    /**
     * Create a default payload for reminders (gentler).
     */
    public static EventPayload reminderPayload() {
        return new EventPayload(DEFAULT_REMINDER_ANIMATION, null, true, new long[]{0, 200, 100, 200});
    }

    /**
     * Create a silent payload (no sound, no vibration).
     */
    public static EventPayload silentPayload(String animation) {
        return new EventPayload(animation, null, false, null);
    }

    // --- Getters ---

    @NonNull
    public String getAnimation() {
        return animation;
    }

    @Nullable
    public String getSoundEffect() {
        return soundEffect;
    }

    public boolean shouldVibrate() {
        return vibrate;
    }

    @Nullable
    public long[] getVibrationPattern() {
        return vibrationPattern;
    }

    /**
     * Check if this payload has a custom sound effect.
     */
    public boolean hasCustomSound() {
        return soundEffect != null && !soundEffect.isEmpty();
    }

    /**
     * Check if this payload has a custom vibration pattern.
     */
    public boolean hasCustomVibration() {
        return vibrationPattern != null && vibrationPattern.length > 0;
    }

    // --- JSON Serialization ---

    /**
     * Serialize to JSON.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("animation", animation);
        
        if (soundEffect != null) {
            json.put("sound_effect", soundEffect);
        }
        
        json.put("vibrate", vibrate);
        
        if (vibrationPattern != null && vibrationPattern.length > 0) {
            StringBuilder pattern = new StringBuilder();
            for (int i = 0; i < vibrationPattern.length; i++) {
                if (i > 0) pattern.append(",");
                pattern.append(vibrationPattern[i]);
            }
            json.put("vibration_pattern", pattern.toString());
        }
        
        return json;
    }

    /**
     * Deserialize from JSON.
     */
    public static EventPayload fromJson(JSONObject json) throws JSONException {
        String animation = json.optString("animation", DEFAULT_ALARM_ANIMATION);
        String soundEffect = json.has("sound_effect") ? json.getString("sound_effect") : null;
        boolean vibrate = json.optBoolean("vibrate", true);
        
        long[] vibrationPattern = null;
        if (json.has("vibration_pattern")) {
            String patternStr = json.getString("vibration_pattern");
            String[] parts = patternStr.split(",");
            vibrationPattern = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vibrationPattern[i] = Long.parseLong(parts[i].trim());
            }
        }
        
        return new EventPayload(animation, soundEffect, vibrate, vibrationPattern);
    }

    // --- Builder Pattern ---

    /**
     * Create a copy with a different animation.
     */
    public EventPayload withAnimation(String newAnimation) {
        return new EventPayload(newAnimation, soundEffect, vibrate, vibrationPattern);
    }

    /**
     * Create a copy with a different sound effect.
     */
    public EventPayload withSoundEffect(String newSoundEffect) {
        return new EventPayload(animation, newSoundEffect, vibrate, vibrationPattern);
    }

    /**
     * Create a copy with vibration enabled/disabled.
     */
    public EventPayload withVibrate(boolean newVibrate) {
        return new EventPayload(animation, soundEffect, newVibrate, vibrationPattern);
    }

    /**
     * Create a copy with a custom vibration pattern.
     */
    public EventPayload withVibrationPattern(long[] newPattern) {
        return new EventPayload(animation, soundEffect, vibrate, newPattern);
    }

    // --- Object Methods ---

    @NonNull
    @Override
    public String toString() {
        return "EventPayload{" +
                "animation='" + animation + '\'' +
                ", soundEffect='" + soundEffect + '\'' +
                ", vibrate=" + vibrate +
                '}';
    }
}
