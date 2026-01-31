package com.fufelshmertzpakostincorporated.kawaicare.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sensor Controller.
 * Handles "Device Incline" and "Shake" sensor inputs.
 * 
 * Uses two sensors:
 * - TYPE_ACCELEROMETER for tilt detection (needs gravity reference)
 * - TYPE_LINEAR_ACCELERATION for shake detection (gravity filtered out)
 * 
 * All sensor processing happens on a background HandlerThread.
 * Callbacks are dispatched to the main thread.
 */
public class SensorController implements SensorEventListener {

    private static final String TAG = "SensorController";

    // Observer Interface
    public interface SensorStateListener {
        void onTiltDetected(float zAxisValue);
        void onStable();
        void onShakeStarted();
        void onShakeContinuing();
        void onShakeEnded();
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;          // For tilt detection
    private final Sensor linearAccelerometer;    // For shake detection (preferred)
    private final boolean hasLinearAccelerometer;
    
    private SensorStateListener listener;
    private final AtomicBoolean isRegistered = new AtomicBoolean(false);

    // Threading
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // --- Tilt/Stable Detection with Hysteresis ---
    private static final float TILT_ENTER_THRESHOLD = 6.5f;  // Z-axis threshold to enter tilt
    private static final float TILT_EXIT_THRESHOLD = 7.5f;   // Z-axis threshold to exit tilt
    private static final long TILT_DEBOUNCE_MS = 300;
    
    private enum TiltState { STABLE, TILTED }
    private final AtomicReference<TiltState> currentTiltState = new AtomicReference<>(TiltState.STABLE);
    private volatile long lastTiltStateChangeTime = 0;

    // --- Shake Detection ---
    // Threshold for LINEAR_ACCELERATION (no gravity, so lower value)
    // 1.5g = gentle but intentional shake, 2.5g = vigorous shake
    private static final float SHAKE_THRESHOLD_LINEAR = 0.8f;  // ~8 m/s²
    // Fallback threshold for raw ACCELEROMETER (includes gravity ~9.8)
    private static final float SHAKE_THRESHOLD_RAW = 1.6f;     // ~18 m/s² total
    
    private static final long SHAKE_REQUIRED_DURATION_MS = 1000;
    private static final long SHAKE_GAP_TOLERANCE_MS = 350;      // Slightly more tolerant
    private static final long SHAKE_END_COOLDOWN_MS = 5000;
    private static final long SHAKE_CONTINUE_NOTIFY_INTERVAL_MS = 500;
    
    private volatile long firstShakeImpulseTime = 0;
    private volatile long lastShakeImpulseTime = 0;
    private volatile boolean shakeTriggered = false;
    private volatile long lastShakeContinueNotifyTime = 0;
    
    // Cache the threshold to avoid repeated checks
    private float currentShakeThreshold;
    
    // Runnable to check if shake has ended
    private final Runnable shakeEndChecker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (shakeTriggered && (now - lastShakeImpulseTime > SHAKE_END_COOLDOWN_MS)) {
                Log.i(TAG, "Shake ended after cooldown");
                shakeTriggered = false;
                firstShakeImpulseTime = 0;
                lastShakeImpulseTime = 0;
                lastShakeContinueNotifyTime = 0;
                
                mainHandler.post(() -> {
                    if (listener != null) listener.onShakeEnded();
                });
            } else if (shakeTriggered && sensorHandler != null) {
                sensorHandler.postDelayed(this, SHAKE_END_COOLDOWN_MS);
            }
        }
    };

    public SensorController(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            hasLinearAccelerometer = (linearAccelerometer != null);
            
            // Set threshold based on available sensor
            currentShakeThreshold = hasLinearAccelerometer 
                    ? SHAKE_THRESHOLD_LINEAR 
                    : SHAKE_THRESHOLD_RAW;
            
            Log.i(TAG, "Linear accelerometer available: " + hasLinearAccelerometer 
                    + ", shake threshold: " + currentShakeThreshold + "g");
        } else {
            accelerometer = null;
            linearAccelerometer = null;
            hasLinearAccelerometer = false;
            currentShakeThreshold = SHAKE_THRESHOLD_RAW;
        }
    }

    public void setListener(SensorStateListener listener) {
        this.listener = listener;
    }

    /**
     * Start listening to sensors on a background thread.
     */
    public void start() {
        if (!isRegistered.get() && sensorManager != null) {
            sensorThread = new HandlerThread("SensorThread", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            sensorThread.start();
            sensorHandler = new Handler(sensorThread.getLooper());

            // Register accelerometer for tilt detection (always needed)
            // Use SENSOR_DELAY_UI - sufficient for tilt, reduces CPU
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, 
                        SensorManager.SENSOR_DELAY_UI, sensorHandler);
            }
            
            // Register linear accelerometer for shake if available
            // Use SENSOR_DELAY_UI - 16Hz is enough for 2-second shake detection
            if (hasLinearAccelerometer) {
                sensorManager.registerListener(this, linearAccelerometer, 
                        SensorManager.SENSOR_DELAY_UI, sensorHandler);
            }
            
            isRegistered.set(true);
            Log.d(TAG, "Sensors registered with SENSOR_DELAY_UI");
        }
    }

    /**
     * Stop listening. Critical for battery optimization.
     */
    public void stop() {
        if (isRegistered.getAndSet(false) && sensorManager != null) {
            sensorManager.unregisterListener(this);
            
            if (sensorHandler != null) {
                sensorHandler.removeCallbacks(shakeEndChecker);
            }
            
            if (sensorThread != null) {
                sensorThread.quitSafely();
                sensorThread = null;
                sensorHandler = null;
            }
        }
        resetState();
    }
    
    public boolean isShaking() {
        return shakeTriggered;
    }
    
    public boolean isTilted() {
        return currentTiltState.get() == TiltState.TILTED;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (listener == null) return;
        
        final int sensorType = event.sensor.getType();
        final long now = System.currentTimeMillis();
        
        // Route to appropriate handler based on sensor type
        if (sensorType == Sensor.TYPE_LINEAR_ACCELERATION) {
            // Preferred shake detection - no gravity
            handleShakeDetection(event.values, now, false);
        } else if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            // Tilt detection always uses accelerometer
            handleTiltDetection(event.values, now);
            
            // Fallback shake detection if no linear accelerometer
            if (!hasLinearAccelerometer) {
                handleShakeDetection(event.values, now, true);
            }
        }
    }
    
    /**
     * Handle shake detection from sensor values.
     * @param values Sensor values [x, y, z]
     * @param now Current timestamp
     * @param includesGravity True if using raw accelerometer (includes gravity)
     */
    private void handleShakeDetection(float[] values, long now, boolean includesGravity) {
        float gX = values[0];
        float gY = values[1];
        float gZ = values[2];
        
        // Calculate magnitude
        float magnitude = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);
        
        // Convert to g-force
        float gForce = magnitude / SensorManager.GRAVITY_EARTH;
        
        // Use appropriate threshold
        float threshold = includesGravity ? SHAKE_THRESHOLD_RAW : SHAKE_THRESHOLD_LINEAR;
        
        if (gForce > threshold) {
            // High acceleration detected
            if (firstShakeImpulseTime == 0) {
                firstShakeImpulseTime = now;
                Log.v(TAG, "Shake impulse started, gForce=" + gForce);
            }
            lastShakeImpulseTime = now;

            if (!shakeTriggered) {
                // Check if duration requirement met
                if (now - firstShakeImpulseTime >= SHAKE_REQUIRED_DURATION_MS) {
                    shakeTriggered = true;
                    lastShakeContinueNotifyTime = now;
                    Log.i(TAG, "Shake STARTED after 2s continuous shaking");
                    
                    mainHandler.post(() -> {
                        if (listener != null) listener.onShakeStarted();
                    });
                    
                    scheduleShakeEndCheck();
                }
            } else {
                // Notify continuing at intervals
                if (now - lastShakeContinueNotifyTime >= SHAKE_CONTINUE_NOTIFY_INTERVAL_MS) {
                    lastShakeContinueNotifyTime = now;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onShakeContinuing();
                    });
                }
                scheduleShakeEndCheck();
            }
        } else {
            // Low acceleration - check if we should reset (before trigger)
            if (!shakeTriggered && firstShakeImpulseTime > 0) {
                if (now - lastShakeImpulseTime > SHAKE_GAP_TOLERANCE_MS) {
                    Log.v(TAG, "Shake reset - gap too long before trigger");
                    firstShakeImpulseTime = 0;
                    lastShakeImpulseTime = 0;
                }
            }
        }
    }
    
    /**
     * Handle tilt detection from accelerometer values.
     */
    private void handleTiltDetection(float[] values, long now) {
        // Skip tilt detection while shaking
        if (shakeTriggered || firstShakeImpulseTime > 0) {
            return;
        }
        
        // Debounce
        if (now - lastTiltStateChangeTime < TILT_DEBOUNCE_MS) {
            return;
        }
        
        float gZ = values[2];
        float absZ = Math.abs(gZ);
        TiltState state = currentTiltState.get();
        
        switch (state) {
            case STABLE:
                // Treat higher |Z| (face-up) as "tilted" for Wear OS orientation
                if (absZ >= TILT_EXIT_THRESHOLD) {
                    if (currentTiltState.compareAndSet(TiltState.STABLE, TiltState.TILTED)) {
                        lastTiltStateChangeTime = now;
                        final float zVal = gZ;
                        mainHandler.post(() -> {
                            if (listener != null) listener.onTiltDetected(zVal);
                        });
                    }
                }
                break;
                
            case TILTED:
                if (absZ <= TILT_ENTER_THRESHOLD) {
                    if (currentTiltState.compareAndSet(TiltState.TILTED, TiltState.STABLE)) {
                        lastTiltStateChangeTime = now;
                        mainHandler.post(() -> {
                            if (listener != null) listener.onStable();
                        });
                    }
                }
                break;
        }
    }
    
    /**
     * Schedule the shake end checker runnable.
     */
    private void scheduleShakeEndCheck() {
        if (sensorHandler != null) {
            sensorHandler.removeCallbacks(shakeEndChecker);
            sensorHandler.postDelayed(shakeEndChecker, SHAKE_END_COOLDOWN_MS);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }
    
    public void resetState() {
        currentTiltState.set(TiltState.STABLE);
        lastTiltStateChangeTime = 0;
        firstShakeImpulseTime = 0;
        lastShakeImpulseTime = 0;
        shakeTriggered = false;
        lastShakeContinueNotifyTime = 0;
    }
}
