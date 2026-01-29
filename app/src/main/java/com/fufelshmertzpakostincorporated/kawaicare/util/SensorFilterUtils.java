package com.fufelshmertzpakostincorporated.kawaicare.util;

/**
 * Shared utility for sensor data filtering and normalization.
 * Eliminates duplicate low-pass filter implementations across the codebase.
 */
public final class SensorFilterUtils {

    private static final float DEFAULT_LOW_PASS_ALPHA = 0.25f;

    private SensorFilterUtils() {
        // Prevent instantiation
    }

    /**
     * Apply low-pass filter to smooth sensor data.
     *
     * @param input       Raw sensor values
     * @param output      Filtered output values (modified in place)
     * @param initialized Whether the filter has been initialized
     * @param alpha       Filter coefficient (0-1, lower = more smoothing)
     * @return true (filter is now initialized)
     */
    public static boolean applyLowPassFilter(float[] input, float[] output,
                                              boolean initialized, float alpha) {
        if (!initialized) {
            System.arraycopy(input, 0, output, 0, input.length);
        } else {
            for (int i = 0; i < input.length; i++) {
                output[i] = alpha * input[i] + (1f - alpha) * output[i];
            }
        }
        return true;
    }

    /**
     * Apply low-pass filter with default alpha (0.25).
     *
     * @param input       Raw sensor values
     * @param output      Filtered output values (modified in place)
     * @param initialized Whether the filter has been initialized
     * @return true (filter is now initialized)
     */
    public static boolean applyLowPassFilter(float[] input, float[] output, boolean initialized) {
        return applyLowPassFilter(input, output, initialized, DEFAULT_LOW_PASS_ALPHA);
    }

    /**
     * Clamp a value between min and max.
     *
     * @param value The value to clamp
     * @param min   Minimum value
     * @param max   Maximum value
     * @return Clamped value
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Normalize a value to 0-1 range.
     *
     * @param value The value to normalize
     * @param max   The maximum value for normalization
     * @return Normalized value between 0 and 1
     */
    public static float normalize(float value, float max) {
        return (max > 0) ? clamp(value / max, 0f, 1f) : 0.5f;
    }

    /**
     * Reset filter arrays to zero.
     *
     * @param arrays Variable number of float arrays to reset
     */
    public static void resetFilters(float[]... arrays) {
        for (float[] array : arrays) {
            for (int i = 0; i < array.length; i++) {
                array[i] = 0;
            }
        }
    }
}
