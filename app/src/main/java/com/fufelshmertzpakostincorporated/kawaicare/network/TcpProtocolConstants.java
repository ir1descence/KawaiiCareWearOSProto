package com.fufelshmertzpakostincorporated.kawaicare.network;

/**
 * Constants for the TCP Socket communication protocol.
 * 
 * This defines the JSON-based message format used between clients
 * and the TcpWearService server.
 * 
 * Message Format:
 * All messages are JSON objects terminated by a newline character.
 * Each message must contain a "command" field (for requests) or "type" field (for responses).
 * 
 * Example Request:
 * {"command":"set_alarm_status","status":"ON"}
 * 
 * Example Response:
 * {"type":"success","message":"Alarm status set to: ON","timestamp":1234567890}
 */
public final class TcpProtocolConstants {

    private TcpProtocolConstants() {
        // Prevent instantiation
    }

    // =========================================
    // Server Configuration
    // =========================================

    /** Default TCP port for the service */
    public static final int DEFAULT_PORT = 8888;

    /** Service name for NSD registration */
    public static final String NSD_SERVICE_NAME = "KawaiiCareWear";

    /** Service type for NSD (must end with a dot) */
    public static final String NSD_SERVICE_TYPE = "_kawaicare._tcp.";

    // =========================================
    // Command Constants (Request -> Server)
    // =========================================

    /**
     * Set alarm status.
     * Payload: {"command":"set_alarm_status","status":"ON|OFF"}
     */
    public static final String CMD_SET_ALARM_STATUS = "set_alarm_status";

    /**
     * Set animation state.
     * Payload: {"command":"set_animation_state","state":"IDLE|HAPPY|SAD|..."}
     */
    public static final String CMD_SET_ANIMATION_STATE = "set_animation_state";

    /**
     * Set active gesture for alarm dismissal.
     * Payload: {"command":"set_active_gesture","signal":"SIGNAL_INCLINE|SIGNAL_SHAKE|..."}
     */
    public static final String CMD_SET_ACTIVE_GESTURE = "set_active_gesture";

    /**
     * Start recording custom gesture.
     * Payload: {"command":"start_recording"}
     */
    public static final String CMD_START_RECORDING = "start_recording";

    /**
     * Stop recording custom gesture.
     * Payload: {"command":"stop_recording"}
     */
    public static final String CMD_STOP_RECORDING = "stop_recording";

    /**
     * Request list of available emotions/animations.
     * Payload: {"command":"request_emotions"}
     */
    public static final String CMD_REQUEST_EMOTIONS = "request_emotions";

    /**
     * Request list of supported signals.
     * Payload: {"command":"request_signals"}
     */
    public static final String CMD_REQUEST_SIGNALS = "request_signals";

    /**
     * Ping for connection keepalive.
     * Payload: {"command":"ping","timestamp":1234567890}
     */
    public static final String CMD_PING = "ping";

    /**
     * Get current server status.
     * Payload: {"command":"get_status"}
     */
    public static final String CMD_GET_STATUS = "get_status";

    // =========================================
    // Response Type Constants (Server -> Client)
    // =========================================

    /** Generic success response */
    public static final String RESP_SUCCESS = "success";

    /** Generic error response */
    public static final String RESP_ERROR = "error";

    /** Available emotions list */
    public static final String RESP_EMOTIONS = "emotions";

    /** Supported signals list */
    public static final String RESP_SIGNALS = "signals";

    /** Signal validation error */
    public static final String RESP_VALIDATION_ERROR = "validation_error";

    /** Pong response to ping */
    public static final String RESP_PONG = "pong";

    /** Server status response */
    public static final String RESP_STATUS = "status";

    /** Welcome message on connect */
    public static final String RESP_WELCOME = "welcome";

    // =========================================
    // JSON Field Names
    // =========================================

    /** Command field in requests */
    public static final String FIELD_COMMAND = "command";

    /** Response type field */
    public static final String FIELD_TYPE = "type";

    /** Status field (ON/OFF) */
    public static final String FIELD_STATUS = "status";

    /** Animation state field */
    public static final String FIELD_STATE = "state";

    /** Signal field */
    public static final String FIELD_SIGNAL = "signal";

    /** Timestamp field */
    public static final String FIELD_TIMESTAMP = "timestamp";

    /** Message field */
    public static final String FIELD_MESSAGE = "message";

    /** Error code field */
    public static final String FIELD_ERROR_CODE = "error_code";

    /** Error message field */
    public static final String FIELD_ERROR_MESSAGE = "error_message";

    /** Emotions array field */
    public static final String FIELD_EMOTIONS = "emotions";

    /** Signals array field */
    public static final String FIELD_SIGNALS = "signals";

    /** Details object field */
    public static final String FIELD_DETAILS = "details";

    // =========================================
    // Mapping from WearableConstants paths
    // =========================================

    /*
     * Path Mapping Reference:
     * 
     * WearableConstants Path              -> TCP Command
     * -----------------------------------------------------------------
     * PATH_ALARM_STATUS (/alarm_status)   -> CMD_SET_ALARM_STATUS
     * PATH_ANIMATION_STATE                -> CMD_SET_ANIMATION_STATE
     * PATH_ACTIVE_ALARM_GESTURE           -> CMD_SET_ACTIVE_GESTURE
     * PATH_START_RECORDING                -> CMD_START_RECORDING
     * PATH_STOP_RECORDING                 -> CMD_STOP_RECORDING
     * PATH_REQUEST_EMOTIONS               -> CMD_REQUEST_EMOTIONS
     * PATH_REQUEST_SUPPORTED_SIGNALS      -> CMD_REQUEST_SIGNALS
     * 
     * Response Mapping:
     * PATH_AVAILABLE_EMOTIONS             -> RESP_EMOTIONS
     * PATH_SUPPORTED_SIGNALS              -> RESP_SIGNALS
     * PATH_SIGNAL_VALIDATION_ERROR        -> RESP_VALIDATION_ERROR
     */
}
