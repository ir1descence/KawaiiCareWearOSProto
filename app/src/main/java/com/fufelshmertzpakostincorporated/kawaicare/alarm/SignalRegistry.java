package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Signal Registry for managing supported alarm-stop signals.
 * 
 * Provides a predetermined list of supported signals, hardware validation,
 * and custom gesture mapping for the alarm dismissal system.
 */
public class SignalRegistry {

    private static final String TAG = "SignalRegistry";

    // Predetermined list of supported alarm-stop signal constants
    public static final String SIGNAL_INCLINE = "SIGNAL_INCLINE";       // Watch tilt/raise to wake
    public static final String SIGNAL_CIRCLE = "SIGNAL_CIRCLE";         // Circular touch gesture (requires gyroscope)
    public static final String SIGNAL_SHAKE = "SIGNAL_SHAKE";           // Shake to dismiss (requires accelerometer)
    public static final String SIGNAL_LONG_TOUCH = "SIGNAL_LONG_TOUCH"; // Long press on screen
    public static final String SIGNAL_CUSTOM = "SIGNAL_CUSTOM";         // User-recorded custom gesture

    // All available signal constants
    private static final String[] ALL_SIGNALS = {
            SIGNAL_INCLINE,
            SIGNAL_CIRCLE,
            SIGNAL_SHAKE,
            SIGNAL_LONG_TOUCH,
            SIGNAL_CUSTOM
    };

    // Gestures directory for custom gesture files
    private static final String GESTURES_DIR = "gestures";
    private static final String GESTURE_FILE_EXTENSION = ".gesture";

    // Error codes for validation
    public static final String ERROR_NO_ACCELEROMETER = "ERROR_NO_ACCELEROMETER";
    public static final String ERROR_NO_GYROSCOPE = "ERROR_NO_GYROSCOPE";
    public static final String ERROR_NO_CUSTOM_GESTURE = "ERROR_NO_CUSTOM_GESTURE";
    public static final String ERROR_HARDWARE_NOT_SUPPORTED = "ERROR_HARDWARE_NOT_SUPPORTED";

    private final Context context;
    private final SensorManager sensorManager;

    // Hardware availability cache
    private Boolean hasAccelerometer = null;
    private Boolean hasGyroscope = null;

    public SignalRegistry(Context context) {
        this.context = context.getApplicationContext();
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    // --- Signal List Methods ---

    /**
     * Get the list of all supported signal constants.
     * Does not perform hardware validation.
     * 
     * @return Array of all signal constant strings
     */
    public static String[] getAllSignals() {
        return ALL_SIGNALS.clone();
    }

    /**
     * Get the list of signals supported by the current device hardware.
     * Performs hardware validation and excludes unsupported signals.
     * 
     * @return List of supported signal constants
     */
    public List<String> getSupportedSignals() {
        List<String> supported = new ArrayList<>();

        for (String signal : ALL_SIGNALS) {
            ValidationResult result = validateSignal(signal);
            if (result.isValid()) {
                supported.add(signal);
            }
        }

        Log.d(TAG, "Supported signals: " + supported);
        return supported;
    }

    /**
     * Get supported signals as a JSON array string for transmission.
     * 
     * @return JSON string containing supported signals, or error JSON if validation fails
     */
    public String getSupportedSignalsAsJson() {
        try {
            List<String> supported = getSupportedSignals();
            
            JSONObject response = new JSONObject();
            response.put("status", "success");
            
            JSONArray signalsArray = new JSONArray();
            for (String signal : supported) {
                signalsArray.put(signal);
            }
            response.put("signals", signalsArray);
            response.put("timestamp", System.currentTimeMillis());
            
            return response.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON response", e);
            return createErrorJson("JSON_ERROR", "Failed to create response: " + e.getMessage());
        }
    }

    // --- Validation Methods ---

    /**
     * Validate if a specific signal is supported by the device hardware.
     * 
     * @param signal The signal constant to validate
     * @return ValidationResult containing validity status and any error
     */
    public ValidationResult validateSignal(String signal) {
        if (signal == null || signal.isEmpty()) {
            return new ValidationResult(false, ERROR_HARDWARE_NOT_SUPPORTED, 
                    "Invalid signal: null or empty");
        }

        switch (signal) {
            case SIGNAL_INCLINE:
                // Incline detection requires accelerometer
                if (!hasAccelerometer()) {
                    return new ValidationResult(false, ERROR_NO_ACCELEROMETER,
                            "Accelerometer sensor not available for incline detection");
                }
                return ValidationResult.valid();

            case SIGNAL_CIRCLE:
                // Circle gesture requires gyroscope for rotation detection
                if (!hasGyroscope()) {
                    return new ValidationResult(false, ERROR_NO_GYROSCOPE,
                            "Gyroscope sensor not available for circle gesture detection");
                }
                return ValidationResult.valid();

            case SIGNAL_SHAKE:
                // Shake detection requires accelerometer
                if (!hasAccelerometer()) {
                    return new ValidationResult(false, ERROR_NO_ACCELEROMETER,
                            "Accelerometer sensor not available for shake detection");
                }
                return ValidationResult.valid();

            case SIGNAL_LONG_TOUCH:
                // Long touch always supported (uses touch screen)
                return ValidationResult.valid();

            case SIGNAL_CUSTOM:
                // Custom gesture requires both sensors and a saved gesture file
                if (!hasAccelerometer()) {
                    return new ValidationResult(false, ERROR_NO_ACCELEROMETER,
                            "Accelerometer sensor not available for custom gesture");
                }
                if (!hasGyroscope()) {
                    return new ValidationResult(false, ERROR_NO_GYROSCOPE,
                            "Gyroscope sensor not available for custom gesture");
                }
                if (!hasCustomGestureFile()) {
                    return new ValidationResult(false, ERROR_NO_CUSTOM_GESTURE,
                            "No custom gesture has been recorded");
                }
                return ValidationResult.valid();

            default:
                return new ValidationResult(false, ERROR_HARDWARE_NOT_SUPPORTED,
                        "Unknown signal type: " + signal);
        }
    }

    /**
     * Validate a signal request and return error JSON if not supported.
     * 
     * @param signal The signal to validate
     * @return null if valid, error JSON string if invalid
     */
    public String validateSignalRequest(String signal) {
        ValidationResult result = validateSignal(signal);
        if (result.isValid()) {
            return null;
        }
        return createErrorJson(result.getErrorCode(), result.getErrorMessage());
    }

    // --- Hardware Detection Methods ---

    /**
     * Check if the device has an accelerometer sensor.
     */
    public boolean hasAccelerometer() {
        if (hasAccelerometer == null) {
            if (sensorManager != null) {
                Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                hasAccelerometer = (accelerometer != null);
            } else {
                hasAccelerometer = false;
            }
            Log.d(TAG, "Accelerometer available: " + hasAccelerometer);
        }
        return hasAccelerometer;
    }

    /**
     * Check if the device has a gyroscope sensor.
     */
    public boolean hasGyroscope() {
        if (hasGyroscope == null) {
            if (sensorManager != null) {
                Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                hasGyroscope = (gyroscope != null);
            } else {
                hasGyroscope = false;
            }
            Log.d(TAG, "Gyroscope available: " + hasGyroscope);
        }
        return hasGyroscope;
    }

    // --- Custom Gesture Methods ---

    /**
     * Check if a custom gesture file has been recorded and saved.
     */
    public boolean hasCustomGestureFile() {
        File gesturesDir = new File(context.getFilesDir(), GESTURES_DIR);
        if (!gesturesDir.exists()) {
            return false;
        }

        File[] files = gesturesDir.listFiles((dir, name) -> 
                name.endsWith(GESTURE_FILE_EXTENSION));
        
        return files != null && files.length > 0;
    }

    /**
     * Get the path to the most recent custom gesture file.
     * 
     * @return File path string, or null if no gesture file exists
     */
    public String getCustomGestureFilePath() {
        File gesturesDir = new File(context.getFilesDir(), GESTURES_DIR);
        if (!gesturesDir.exists()) {
            return null;
        }

        File[] files = gesturesDir.listFiles((dir, name) -> 
                name.endsWith(GESTURE_FILE_EXTENSION));
        
        if (files == null || files.length == 0) {
            return null;
        }

        // Find the most recent gesture file
        File newestFile = files[0];
        for (File file : files) {
            if (file.lastModified() > newestFile.lastModified()) {
                newestFile = file;
            }
        }

        Log.d(TAG, "Custom gesture file: " + newestFile.getAbsolutePath());
        return newestFile.getAbsolutePath();
    }

    /**
     * Map a signal to its required resources.
     * For SIGNAL_CUSTOM, returns the path to the saved gesture file.
     * 
     * @param signal The signal constant
     * @return Resource mapping info, or null if not applicable
     */
    public SignalResourceMapping getSignalResourceMapping(String signal) {
        if (!SIGNAL_CUSTOM.equals(signal)) {
            // Non-custom signals don't have external resource mappings
            return new SignalResourceMapping(signal, null, null);
        }

        String gesturePath = getCustomGestureFilePath();
        if (gesturePath == null) {
            return new SignalResourceMapping(signal, null, ERROR_NO_CUSTOM_GESTURE);
        }

        return new SignalResourceMapping(signal, gesturePath, null);
    }

    // --- Helper Methods ---

    private String createErrorJson(String errorCode, String errorMessage) {
        try {
            JSONObject error = new JSONObject();
            error.put("status", "error");
            error.put("errorCode", errorCode);
            error.put("errorMessage", errorMessage);
            error.put("timestamp", System.currentTimeMillis());
            return error.toString();
        } catch (JSONException e) {
            return "{\"status\":\"error\",\"errorCode\":\"JSON_ERROR\"}";
        }
    }

    /**
     * Check if a given string is a valid signal constant.
     */
    public static boolean isValidSignal(String signal) {
        if (signal == null) return false;
        for (String s : ALL_SIGNALS) {
            if (s.equals(signal)) {
                return true;
            }
        }
        return false;
    }

    // --- Inner Classes ---

    /**
     * Result of signal validation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorCode;
        private final String errorMessage;

        public ValidationResult(boolean valid, String errorCode, String errorMessage) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Resource mapping for a signal.
     * Contains the signal type and any associated resource paths.
     */
    public static class SignalResourceMapping {
        private final String signal;
        private final String resourcePath;
        private final String error;

        public SignalResourceMapping(String signal, String resourcePath, String error) {
            this.signal = signal;
            this.resourcePath = resourcePath;
            this.error = error;
        }

        public String getSignal() {
            return signal;
        }

        public String getResourcePath() {
            return resourcePath;
        }

        public boolean hasError() {
            return error != null;
        }

        public String getError() {
            return error;
        }
    }
}
