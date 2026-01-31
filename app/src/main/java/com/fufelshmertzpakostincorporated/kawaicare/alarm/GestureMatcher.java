package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fufelshmertzpakostincorporated.kawaicare.model.GestureSession;
import com.fufelshmertzpakostincorporated.kawaicare.util.GestureFileUtils;
import com.fufelshmertzpakostincorporated.kawaicare.util.SensorFilterUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Gesture matching utility for alarm dismissal.
 * Compares real-time touch and motion input against stored gesture patterns.
 * 
 * Uses DTW (Dynamic Time Warping) algorithm for robust gesture matching
 * that handles variations in speed and timing.
 */
public class GestureMatcher implements SensorEventListener {

    private static final String TAG = "GestureMatcher";

    // Matching configuration
    private static final float DTW_MATCH_THRESHOLD = 0.35f;  // Lower = stricter matching
    private static final long MIN_GESTURE_DURATION_MS = 300;
    private static final long MAX_GESTURE_DURATION_MS = 8000;

    // Shake detection for fallback
    private static final float SHAKE_THRESHOLD_GRAVITY = 2.5f;
    private static final int REQUIRED_SHAKE_COUNT = 3;
    private static final long SHAKE_WINDOW_MS = 1500;

    /**
     * Listener interface for gesture matching results.
     */
    public interface GestureMatchListener {
        void onGestureMatched();
        void onShakeDetected();
        void onMatchingStarted();
        void onMatchingStopped();
    }

    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;

    private GestureMatchListener listener;
    private boolean isMatching = false;
    private boolean hasCustomGesture = false;

    // Current recording state
    private List<GestureSession.GestureFrame> currentFrames;
    private long matchingStartTimeNanos;
    private int screenWidth = 1;
    private int screenHeight = 1;

    // Low-pass filtered sensor values
    private final float[] filteredAccel = new float[3];
    private final float[] filteredGyro = new float[3];
    private boolean accelInitialized = false;
    private boolean gyroInitialized = false;

    // Shake detection state
    private final List<Long> shakeTimes = new ArrayList<>();
    private long lastShakeTime = 0;

    // Stored gesture for comparison
    private GestureSession storedGesture;

    public GestureMatcher(Context context) {
        this.context = context.getApplicationContext();

        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        } else {
            accelerometer = null;
            gyroscope = null;
        }

        // Load stored gesture on initialization
        loadStoredGesture();
    }

    /**
     * Set listener for gesture match events.
     */
    public void setListener(@Nullable GestureMatchListener listener) {
        this.listener = listener;
    }

    /**
     * Set screen dimensions for touch normalization.
     */
    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width > 0 ? width : 1;
        this.screenHeight = height > 0 ? height : 1;
    }

    /**
     * Check if a custom gesture is stored.
     */
    public boolean hasCustomGesture() {
        return hasCustomGesture;
    }

    /**
     * Check if gesture matching is currently active.
     */
    public boolean isMatching() {
        return isMatching;
    }

    /**
     * Start gesture matching mode.
     * Begins listening for touch and sensor input.
     */
    public void startMatching() {
        if (isMatching) {
            Log.w(TAG, "Already matching, ignoring start request");
            return;
        }

        Log.d(TAG, "Starting gesture matching, hasCustomGesture=" + hasCustomGesture);

        currentFrames = new ArrayList<>();
        matchingStartTimeNanos = System.nanoTime();
        shakeTimes.clear();
        
        resetFilters();
        registerSensors();
        
        isMatching = true;

        if (listener != null) {
            listener.onMatchingStarted();
        }
    }

    /**
     * Stop gesture matching mode.
     */
    public void stopMatching() {
        if (!isMatching) return;

        isMatching = false;
        unregisterSensors();
        currentFrames = null;

        Log.d(TAG, "Gesture matching stopped");

        if (listener != null) {
            listener.onMatchingStopped();
        }
    }

    /**
     * Process a touch event during matching.
     * 
     * @param event The motion event
     * @return true if gesture was matched
     */
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (!isMatching) return false;

        // Record touch frame
        recordTouchFrame(event);

        // On touch up, check for match if we have a custom gesture
        if (event.getAction() == MotionEvent.ACTION_UP && hasCustomGesture) {
            long durationMs = getDurationMs();
            
            if (durationMs >= MIN_GESTURE_DURATION_MS && durationMs <= MAX_GESTURE_DURATION_MS) {
                if (matchesStoredGesture()) {
                    Log.d(TAG, "Gesture matched!");
                    if (listener != null) {
                        listener.onGestureMatched();
                    }
                    return true;
                }
            }

            // Reset for next attempt
            currentFrames.clear();
            matchingStartTimeNanos = System.nanoTime();
        }

        return false;
    }

    /**
     * Release resources.
     */
    public void release() {
        stopMatching();
    }

    // --- Private Methods ---

    private void loadStoredGesture() {
        // Use centralized GestureFileUtils to find newest gesture file
        File newestFile = GestureFileUtils.getNewestGestureFile(context);
        
        if (newestFile == null) {
            hasCustomGesture = false;
            Log.d(TAG, "No stored gestures found");
            return;
        }

        // Load the gesture
        try (FileInputStream fis = new FileInputStream(newestFile);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            
            storedGesture = (GestureSession) ois.readObject();
            hasCustomGesture = storedGesture != null && storedGesture.getFrameCount() > 0;
            
            Log.d(TAG, "Loaded stored gesture: " + newestFile.getName() + 
                    ", frames=" + (storedGesture != null ? storedGesture.getFrameCount() : 0));
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading stored gesture", e);
            hasCustomGesture = false;
        }
    }

    /**
     * Reload stored gesture (call after new gesture is recorded).
     */
    public void reloadStoredGesture() {
        loadStoredGesture();
    }

    private void resetFilters() {
        accelInitialized = false;
        gyroInitialized = false;
        SensorFilterUtils.resetFilters(filteredAccel, filteredGyro);
    }

    private void registerSensors() {
        if (sensorManager == null) return;

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    private long getDurationMs() {
        return (System.nanoTime() - matchingStartTimeNanos) / 1_000_000L;
    }

    private void recordTouchFrame(MotionEvent event) {
        if (currentFrames == null) return;

        GestureSession.GestureFrame frame = new GestureSession.GestureFrame(
                System.nanoTime() - matchingStartTimeNanos
        );

        frame.touchPoint = new GestureSession.TouchPoint(
                normalizeValue(event.getX(), screenWidth),
                normalizeValue(event.getY(), screenHeight),
                clamp(event.getPressure(), 0f, 1f),
                event.getAction(),
                event.getPointerId(0)
        );

        frame.accelerometer = new GestureSession.SensorReading(
                Sensor.TYPE_ACCELEROMETER,
                filteredAccel[0], filteredAccel[1], filteredAccel[2],
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        );

        frame.gyroscope = new GestureSession.SensorReading(
                Sensor.TYPE_GYROSCOPE,
                filteredGyro[0], filteredGyro[1], filteredGyro[2],
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        );

        currentFrames.add(frame);
    }

    private float normalizeValue(float value, float max) {
        return SensorFilterUtils.normalize(value, max);
    }

    private float clamp(float value, float min, float max) {
        return SensorFilterUtils.clamp(value, min, max);
    }

    // --- Gesture Matching (DTW Algorithm) ---

    private boolean matchesStoredGesture() {
        if (storedGesture == null || currentFrames == null || currentFrames.isEmpty()) {
            return false;
        }

        List<GestureSession.GestureFrame> storedFrames = storedGesture.getFrames();
        if (storedFrames == null || storedFrames.isEmpty()) {
            return false;
        }

        // Calculate DTW distance
        float distance = calculateDTWDistance(currentFrames, storedFrames);
        
        Log.d(TAG, "DTW distance: " + distance + ", threshold: " + DTW_MATCH_THRESHOLD);
        
        return distance < DTW_MATCH_THRESHOLD;
    }

    /**
     * Calculate Dynamic Time Warping distance between two gesture sequences.
     * Considers both touch path and sensor data.
     */
    private float calculateDTWDistance(List<GestureSession.GestureFrame> seq1, 
                                       List<GestureSession.GestureFrame> seq2) {
        int n = seq1.size();
        int m = seq2.size();

        if (n == 0 || m == 0) return Float.MAX_VALUE;

        // DTW matrix
        float[][] dtw = new float[n + 1][m + 1];
        
        // Initialize with infinity
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                dtw[i][j] = Float.MAX_VALUE;
            }
        }
        dtw[0][0] = 0;

        // Fill the matrix
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                float cost = frameDistance(seq1.get(i - 1), seq2.get(j - 1));
                dtw[i][j] = cost + Math.min(Math.min(
                        dtw[i - 1][j],      // insertion
                        dtw[i][j - 1]),     // deletion
                        dtw[i - 1][j - 1]   // match
                );
            }
        }

        // Normalize by path length
        return dtw[n][m] / (n + m);
    }

    /**
     * Calculate distance between two gesture frames.
     * Combines touch position and sensor data distances.
     */
    private float frameDistance(GestureSession.GestureFrame f1, GestureSession.GestureFrame f2) {
        float touchDist = 0;
        float sensorDist = 0;

        // Touch position distance (Euclidean)
        if (f1.touchPoint != null && f2.touchPoint != null) {
            float dx = f1.touchPoint.normalizedX - f2.touchPoint.normalizedX;
            float dy = f1.touchPoint.normalizedY - f2.touchPoint.normalizedY;
            touchDist = (float) Math.sqrt(dx * dx + dy * dy);
        }

        // Sensor distance (accelerometer + gyroscope)
        if (f1.accelerometer != null && f2.accelerometer != null) {
            float ax = f1.accelerometer.x - f2.accelerometer.x;
            float ay = f1.accelerometer.y - f2.accelerometer.y;
            float az = f1.accelerometer.z - f2.accelerometer.z;
            // Normalize by typical accelerometer range (~20 m/s²)
            sensorDist += (float) Math.sqrt(ax * ax + ay * ay + az * az) / 20f;
        }

        if (f1.gyroscope != null && f2.gyroscope != null) {
            float gx = f1.gyroscope.x - f2.gyroscope.x;
            float gy = f1.gyroscope.y - f2.gyroscope.y;
            float gz = f1.gyroscope.z - f2.gyroscope.z;
            // Normalize by typical gyroscope range (~10 rad/s)
            sensorDist += (float) Math.sqrt(gx * gx + gy * gy + gz * gz) / 10f;
        }

        // Weight: 60% touch, 40% sensors
        return 0.6f * touchDist + 0.4f * sensorDist;
    }

    // --- Shake Detection (Fallback when no custom gesture) ---

    private void checkForShake(float gX, float gY, float gZ) {
        // Calculate total acceleration in G-force
        float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ) / SensorManager.GRAVITY_EARTH;

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            long now = System.currentTimeMillis();

            // Debounce individual shake events
            if (now - lastShakeTime > 200) {
                lastShakeTime = now;
                shakeTimes.add(now);

                // Remove old shake times outside the window
                long windowStart = now - SHAKE_WINDOW_MS;
                shakeTimes.removeIf(time -> time < windowStart);

                Log.d(TAG, "Shake detected, count in window: " + shakeTimes.size());

                // Check if enough shakes occurred
                if (shakeTimes.size() >= REQUIRED_SHAKE_COUNT) {
                    Log.d(TAG, "Shake pattern matched!");
                    shakeTimes.clear();
                    
                    if (listener != null) {
                        listener.onShakeDetected();
                    }
                }
            }
        }
    }

    // --- SensorEventListener ---

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isMatching) return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                accelInitialized = SensorFilterUtils.applyLowPassFilter(event.values, filteredAccel, accelInitialized);

                // Check for shake if no custom gesture
                if (!hasCustomGesture) {
                    checkForShake(event.values[0], event.values[1], event.values[2]);
                }
                break;

            case Sensor.TYPE_GYROSCOPE:
                gyroInitialized = SensorFilterUtils.applyLowPassFilter(event.values, filteredGyro, gyroInitialized);
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }
}
