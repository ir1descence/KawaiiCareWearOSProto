package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Battery-aware animation frame rate controller for Wear OS.
 * 
 * Automatically adjusts the animation frame rate based on battery level
 * to extend battery life on wearable devices. This is particularly important
 * for watch faces and always-on apps.
 * 
 * Usage:
 * <pre>
 * BatteryAwareRenderer batteryRenderer = new BatteryAwareRenderer(context);
 * batteryRenderer.setCallback(newDelay -> {
 *     // Update your animation renderer's frame delay
 *     animationRenderer.setFrameDelay(newDelay);
 * });
 * batteryRenderer.start(); // In onResume()
 * batteryRenderer.stop();  // In onPause()
 * </pre>
 * 
 * Frame rate tiers:
 * - 100-51% battery: 30 FPS (33ms delay)
 * - 50-31% battery: 25 FPS (40ms delay)
 * - 30-16% battery: 20 FPS (50ms delay)
 * - 15-0% battery: 15 FPS (66ms delay)
 */
public class BatteryAwareRenderer {

    private static final String TAG = "BatteryAwareRenderer";

    // Frame delay tiers based on battery level
    private static final long NORMAL_FRAME_DELAY = AnimationConfig.FRAME_DELAY_30FPS;     // 30 FPS
    private static final long MEDIUM_BATTERY_DELAY = AnimationConfig.FRAME_DELAY_25FPS;   // 25 FPS
    private static final long LOW_BATTERY_DELAY = AnimationConfig.FRAME_DELAY_20FPS;      // 20 FPS
    private static final long CRITICAL_BATTERY_DELAY = AnimationConfig.FRAME_DELAY_15FPS; // 15 FPS

    // Battery thresholds
    private static final float MEDIUM_BATTERY_THRESHOLD = 50f;
    private static final float LOW_BATTERY_THRESHOLD = 30f;
    private static final float CRITICAL_BATTERY_THRESHOLD = 15f;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FrameRateCallback callback;
    private boolean isRegistered = false;
    private long currentFrameDelay = NORMAL_FRAME_DELAY;
    private float lastBatteryLevel = -1f;
    private boolean isCharging = false;

    /**
     * Callback interface for frame rate changes.
     */
    public interface FrameRateCallback {
        /**
         * Called when the recommended frame delay changes.
         * 
         * @param frameDelayMs New frame delay in milliseconds
         */
        void onFrameRateChanged(long frameDelayMs);
    }

    /**
     * Broadcast receiver for battery status changes.
     */
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL;

            if (level >= 0 && scale > 0) {
                float batteryPct = level * 100f / scale;
                
                // Only update if battery level changed significantly (>1%)
                if (Math.abs(batteryPct - lastBatteryLevel) > 1f || lastBatteryLevel < 0) {
                    lastBatteryLevel = batteryPct;
                    adjustFrameRate(batteryPct);
                }
            }
        }
    };

    /**
     * Create a new BatteryAwareRenderer.
     * 
     * @param context Application context
     */
    public BatteryAwareRenderer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Set the callback to receive frame rate change notifications.
     * 
     * @param callback Callback to receive notifications
     */
    public void setCallback(FrameRateCallback callback) {
        this.callback = callback;
    }

    /**
     * Start monitoring battery level and adjusting frame rate.
     * Should be called in Activity.onResume() or Service.onCreate().
     */
    public void start() {
        if (!isRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            context.registerReceiver(batteryReceiver, filter);
            isRegistered = true;
            Log.d(TAG, "Battery monitoring started");
        }
    }

    /**
     * Stop monitoring battery level.
     * Should be called in Activity.onPause() or Service.onDestroy().
     */
    public void stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Battery receiver already unregistered");
            }
            isRegistered = false;
            Log.d(TAG, "Battery monitoring stopped");
        }
    }

    /**
     * Get the current recommended frame delay.
     * 
     * @return Frame delay in milliseconds
     */
    public long getCurrentFrameDelay() {
        return currentFrameDelay;
    }

    /**
     * Get the last known battery level.
     * 
     * @return Battery level (0-100), or -1 if unknown
     */
    public float getLastBatteryLevel() {
        return lastBatteryLevel;
    }

    /**
     * Check if the device is currently charging.
     * 
     * @return True if charging
     */
    public boolean isCharging() {
        return isCharging;
    }

    /**
     * Force a specific frame delay, overriding battery-based calculation.
     * Useful when the user wants maximum performance regardless of battery.
     * 
     * @param frameDelayMs Frame delay in milliseconds (0 to restore auto mode)
     */
    public void forceFrameDelay(long frameDelayMs) {
        if (frameDelayMs > 0) {
            updateFrameDelay(frameDelayMs);
        } else {
            // Restore auto mode - recalculate from last known battery level
            if (lastBatteryLevel >= 0) {
                adjustFrameRate(lastBatteryLevel);
            }
        }
    }

    /**
     * Calculate and apply appropriate frame rate based on battery percentage.
     * When charging, always use the normal (highest) frame rate.
     */
    private void adjustFrameRate(float batteryPct) {
        long newDelay;

        if (isCharging) {
            // When charging, use full frame rate
            newDelay = NORMAL_FRAME_DELAY;
            Log.d(TAG, "Charging - using normal frame rate");
        } else if (batteryPct <= CRITICAL_BATTERY_THRESHOLD) {
            newDelay = CRITICAL_BATTERY_DELAY;
            Log.d(TAG, "Critical battery (" + batteryPct + "%) - reducing to 15 FPS");
        } else if (batteryPct <= LOW_BATTERY_THRESHOLD) {
            newDelay = LOW_BATTERY_DELAY;
            Log.d(TAG, "Low battery (" + batteryPct + "%) - reducing to 20 FPS");
        } else if (batteryPct <= MEDIUM_BATTERY_THRESHOLD) {
            newDelay = MEDIUM_BATTERY_DELAY;
            Log.d(TAG, "Medium battery (" + batteryPct + "%) - reducing to 25 FPS");
        } else {
            newDelay = NORMAL_FRAME_DELAY;
            Log.d(TAG, "Good battery (" + batteryPct + "%) - using 30 FPS");
        }

        updateFrameDelay(newDelay);
    }

    /**
     * Update the frame delay and notify callback if changed.
     */
    private void updateFrameDelay(long newDelay) {
        if (newDelay != currentFrameDelay) {
            currentFrameDelay = newDelay;

            if (callback != null) {
                // Notify on main thread for UI safety
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    callback.onFrameRateChanged(newDelay);
                } else {
                    mainHandler.post(() -> callback.onFrameRateChanged(newDelay));
                }
            }
        }
    }

    /**
     * Get a human-readable description of current status.
     * Useful for debugging and diagnostics.
     * 
     * @return Status string
     */
    public String getStatusDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Battery: ");
        if (lastBatteryLevel < 0) {
            sb.append("Unknown");
        } else {
            sb.append(String.format("%.0f%%", lastBatteryLevel));
        }
        sb.append(isCharging ? " (Charging)" : "");
        sb.append("\nFrame Rate: ");
        sb.append(String.format("%.0f FPS", 1000.0 / currentFrameDelay));
        sb.append(" (").append(currentFrameDelay).append("ms delay)");
        return sb.toString();
    }
}
