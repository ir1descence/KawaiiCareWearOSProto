package com.fufelshmertzpakostincorporated.kawaicare.wear;

/**
 * Shared constants for Wearable Data Layer communication.
 * Centralizes all path and key constants to avoid duplication and ensure consistency.
 */
public final class WearableConstants {

    private WearableConstants() {
        // Prevent instantiation
    }

    // ===========================================
    // Data Layer Paths
    // ===========================================

    /** Path for animation state data */
    public static final String PATH_ANIMATION_STATE = "/animation_state";

    // ===========================================
    // Event System Paths (New Architecture)
    // ===========================================

    /** Path for scheduled events sync */
    public static final String PATH_EVENTS_SYNC = "/events_sync";

    /** Path for event triggered notification */
    public static final String PATH_EVENT_TRIGGERED = "/event_triggered";

    /** Path for event dismissed notification */
    public static final String PATH_EVENT_DISMISSED = "/event_dismissed";

    /** Path for available emotions list */
    public static final String PATH_AVAILABLE_EMOTIONS = "/available_emotions";

    /** Path for active alarm gesture/signal */
    public static final String PATH_ACTIVE_ALARM_GESTURE = "/active_alarm_gesture";

    /** Path for supported signals list */
    public static final String PATH_SUPPORTED_SIGNALS = "/supported_signals";

    /** Path for signal validation errors */
    public static final String PATH_SIGNAL_VALIDATION_ERROR = "/signal_validation_error";

    /** Path for emoji selection */
    public static final String PATH_SET_EMOJI = "/set_emoji";

    /** Path for requesting available emojis */
    public static final String PATH_REQUEST_AVAILABLE_EMOJIS = "/request_available_emojis";

    // ===========================================
    // Data Layer Keys
    // ===========================================

    /** Key for animation state value */
    public static final String KEY_ANIM_STATE = "key_animation_state";

    // ===========================================
    // Event System Keys
    // ===========================================

    /** Key for event ID */
    public static final String KEY_EVENT_ID = "key_event_id";

    /** Key for event type (ALARM or REMINDER) */
    public static final String KEY_EVENT_TYPE = "key_event_type";

    /** Key for events JSON array */
    public static final String KEY_EVENTS_JSON = "key_events_json";

    /** Key for dismissed by reason */
    public static final String KEY_DISMISSED_BY = "key_dismissed_by";

    /** Key for animation duration in milliseconds */
    public static final String KEY_ANIM_DURATION = "key_animation_duration";

    /** Key for emotions list */
    public static final String KEY_EMOTIONS_LIST = "key_emotions_list";

    /** Key for active signal */
    public static final String KEY_ACTIVE_SIGNAL = "key_active_signal";

    /** Key for signals list */
    public static final String KEY_SIGNALS_LIST = "key_signals_list";

    /** Key for signals JSON data */
    public static final String KEY_SIGNALS_JSON = "key_signals_json";

    /** Key for timestamps (force update) */
    public static final String KEY_TIMESTAMP = "timestamp";

    /** Key for emoji string (Unicode emoji character) */
    public static final String KEY_EMOJI = "key_emoji";

    /** Key for available emojis list */
    public static final String KEY_AVAILABLE_EMOJIS = "key_available_emojis";

    // ===========================================
    // Message Paths
    // ===========================================

    /** Message path to start recording custom gesture */
    public static final String PATH_START_RECORDING = "/start_custom_recording";

    /** Message path to stop recording custom gesture */
    public static final String PATH_STOP_RECORDING = "/stop_custom_recording";

    /** Message path to request available emotions */
    public static final String PATH_REQUEST_EMOTIONS = "/request_emotions";

    /** Message path to request supported signals */
    public static final String PATH_REQUEST_SUPPORTED_SIGNALS = "/request_supported_signals";

    /** Message path for successful recording */
    public static final String PATH_RECORDING_SUCCESS = "/recording_success";

    /** Message path for failed recording */
    public static final String PATH_RECORDING_FAILED = "/recording_failed";

    // ===========================================
    // Event System Values
    // ===========================================

    /** Event type: Alarm */
    public static final String EVENT_TYPE_ALARM = "ALARM";

    /** Event type: Reminder */
    public static final String EVENT_TYPE_REMINDER = "REMINDER";

    /** Dismissed by gesture */
    public static final String DISMISSED_BY_GESTURE = "gesture";

    /** Dismissed by timeout */
    public static final String DISMISSED_BY_TIMEOUT = "timeout";

    /** Dismissed manually */
    public static final String DISMISSED_BY_MANUAL = "manual";
}
