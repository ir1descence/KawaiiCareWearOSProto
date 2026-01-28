package com.fufelshmertzpakostincorporated.kawaicare.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Sensor Controller.
 * Handles "Device Incline" and other sensor inputs.
 * Decoupled from View logic.
 */
public class SensorController implements SensorEventListener {

    // Observer Interface
    public interface SensorStateListener {
        void onTiltDetected(float zAxisValue); // Arm raised / Watch Tilted
        void onStable(); // Normal position

        void onShake();
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private SensorStateListener listener;
    private boolean isRegistered = false;

    // Thresholds
    // Z-axis ~9.8m/s² means looking at watch (screen up).
    // Defining a range for "Looking at watch"
    private static final float LOOKING_THRESHOLD_MIN = 7.0f; 

    // Shake detection constants
    // SHAKE_THRESHOLD_GRAVITY is the multiple of g-force to consider a shake (e.g., 2.7 => 2.7g)
    private static final float SHAKE_THRESHOLD_GRAVITY = 2.7f;
    private static final int SHAKE_SLOP_TIME_MS = 500;
    private long lastShakeTime = 0;

    public SensorController(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        } else {
            accelerometer = null;
        }
    }

    public void setListener(SensorStateListener listener) {
        this.listener = listener;
    }

    /**
     * Start listening to sensors.
     * Should be called in onResume.
     */
    public void start() {
        if (!isRegistered && sensorManager != null && accelerometer != null) {
            // SENSOR_DELAY_UI is sufficient for UI updates, saves battery over GAME/NORMAL
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            isRegistered = true;
        }
    }

    /**
     * Stop listening.
     * Critical for Battery Optimization in onPause.
     */
    public void stop() {
        if (isRegistered && sensorManager != null) {
            sensorManager.unregisterListener(this);
            isRegistered = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (listener == null) return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // axis Z is perpendicular to the screen.
            // When looking at watch, Z is +9.8 (approx).
            // When arm is down, Y is usually dominant.
            
            float gX = event.values[0];
            float gY = event.values[1];
            float gZ = event.values[2];

            // Detect shake (high overall acceleration).
            // Normalize to G-force and compare against threshold.
            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ) / SensorManager.GRAVITY_EARTH;
            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                final long now = System.currentTimeMillis();
                if (lastShakeTime + SHAKE_SLOP_TIME_MS < now) {
                    lastShakeTime = now;
                    listener.onShake();
                    // Don't also treat this sample as a tilt/stable update.
                    return;
                }
            }

            // Simple Logic: If Z is high positive, we are looking at the screen.
            if (gZ > LOOKING_THRESHOLD_MIN) {
                 listener.onTiltDetected(gZ);
            } else {
                 listener.onStable();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }
}
