package com.fufelshmertzpakostincorporated.kawaicare.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Shared vibration utility to eliminate duplication across the codebase.
 * Handles backward compatibility for different Android versions.
 */
public final class VibrationUtils {

    private VibrationUtils() {
        // Prevent instantiation
    }

    /**
     * Vibrate for a single duration.
     *
     * @param context    Application context
     * @param durationMs Duration in milliseconds
     */
    public static void vibrate(Context context, long durationMs) {
        Vibrator vibrator = getVibrator(context);
        if (vibrator == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(durationMs);
        }
    }

    /**
     * Vibrate with a pattern.
     *
     * @param context Application context
     * @param pattern Pattern of on/off durations in milliseconds
     * @param repeat  Index to repeat from, or -1 for no repeat
     */
    public static void vibrate(Context context, long[] pattern, int repeat) {
        Vibrator vibrator = getVibrator(context);
        if (vibrator == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat));
        } else {
            vibrator.vibrate(pattern, repeat);
        }
    }

    /**
     * Cancel any ongoing vibration.
     *
     * @param context Application context
     */
    public static void cancel(Context context) {
        Vibrator vibrator = getVibrator(context);
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    /**
     * Check if the device has a vibrator.
     *
     * @param context Application context
     * @return true if vibrator is available
     */
    public static boolean hasVibrator(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        return vibrator != null && vibrator.hasVibrator();
    }

    private static Vibrator getVibrator(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return null;
        }
        return vibrator;
    }
}
