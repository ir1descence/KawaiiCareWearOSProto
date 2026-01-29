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

    /** Path for alarm status data */
    public static final String PATH_ALARM_STATUS = "/alarm_status";

    /** Path for animation state data */
    public static final String PATH_ANIMATION_STATE = "/animation_state";

    /** Path for available emotions list */
    public static final String PATH_AVAILABLE_EMOTIONS = "/available_emotions";

    /** Path for active alarm gesture/signal */
    public static final String PATH_ACTIVE_ALARM_GESTURE = "/active_alarm_gesture";

    /** Path for supported signals list */
    public static final String PATH_SUPPORTED_SIGNALS = "/supported_signals";

    /** Path for signal validation errors */
    public static final String PATH_SIGNAL_VALIDATION_ERROR = "/signal_validation_error";

    // ===========================================
    // Data Layer Keys
    // ===========================================

    /** Key for alarm status value */
    public static final String KEY_ALARM_STATUS = "key_alarm_status";

    /** Key for animation state value */
    public static final String KEY_ANIM_STATE = "key_animation_state";

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
    // Alarm Status Values
    // ===========================================

    /** Alarm is active */
    public static final String ALARM_STATUS_ON = "ON";

    /** Alarm is inactive */
    public static final String ALARM_STATUS_OFF = "OFF";
}
