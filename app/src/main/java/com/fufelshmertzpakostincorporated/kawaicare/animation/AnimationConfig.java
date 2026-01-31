package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

/**
 * Configuration helper for animation rendering on Wear OS.
 * 
 * Provides device-aware settings for optimal performance and memory usage
 * on memory-constrained wearable devices.
 */
public final class AnimationConfig {

    private AnimationConfig() {
        // Utility class - prevent instantiation
    }

    // Frame rate constants
    public static final long FRAME_DELAY_30FPS = 33;  // ~30 FPS (33.33ms)
    public static final long FRAME_DELAY_25FPS = 40;  // 25 FPS (for battery saving)
    public static final long FRAME_DELAY_20FPS = 50;  // 20 FPS (low battery mode)
    public static final long FRAME_DELAY_15FPS = 66;  // 15 FPS (critical battery mode)

    // Cache size constraints
    public static final int MIN_CACHE_SIZE_KB = 4 * 1024;  // 4MB minimum
    public static final int MAX_CACHE_PERCENTAGE = 20;     // Max 20% of heap
    public static final int DEFAULT_CACHE_PERCENTAGE = 15; // Default 15% of heap

    /**
     * Get optimal bitmap config based on transparency requirements.
     * 
     * @param needsAlpha True if the images have transparency (PNG with alpha)
     * @return The optimal Bitmap.Config for this use case
     */
    public static Bitmap.Config getOptimalBitmapConfig(boolean needsAlpha) {
        if (needsAlpha) {
            // ARGB_8888 preserves full alpha channel
            return Bitmap.Config.ARGB_8888;
        }
        // RGB_565 uses half the memory but no alpha support
        // Good for JPEG or opaque PNG images
        return Bitmap.Config.RGB_565;
    }

    /**
     * Calculate optimal cache size for this device.
     * 
     * @return Cache size in KB
     */
    public static int getOptimalCacheSize() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int calculatedSize = maxMemory * DEFAULT_CACHE_PERCENTAGE / 100;
        return Math.max(calculatedSize, MIN_CACHE_SIZE_KB);
    }

    /**
     * Calculate optimal cache size with custom percentage.
     * 
     * @param percentage Percentage of max heap to use (1-20)
     * @return Cache size in KB
     */
    public static int getCacheSize(int percentage) {
        percentage = Math.max(1, Math.min(percentage, MAX_CACHE_PERCENTAGE));
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int calculatedSize = maxMemory * percentage / 100;
        return Math.max(calculatedSize, MIN_CACHE_SIZE_KB);
    }

    /**
     * Get optimal frame rate based on animation complexity.
     * Shorter animations can run faster, longer ones should be slower
     * to conserve battery.
     * 
     * @param frameCount Number of frames in the animation
     * @return Frame delay in milliseconds
     */
    public static long getFrameDelayMs(int frameCount) {
        if (frameCount <= 10) {
            return FRAME_DELAY_20FPS; // Short animations at 20 FPS
        } else if (frameCount <= 30) {
            return FRAME_DELAY_30FPS; // Medium animations at 30 FPS
        } else {
            return FRAME_DELAY_25FPS; // Long animations at 25 FPS to save battery
        }
    }

    /**
     * Get frame delay based on battery level.
     * Automatically reduces frame rate when battery is low.
     * 
     * @param batteryPercentage Current battery level (0-100)
     * @return Frame delay in milliseconds
     */
    public static long getFrameDelayForBattery(float batteryPercentage) {
        if (batteryPercentage <= 15) {
            return FRAME_DELAY_15FPS; // Critical battery - 15 FPS
        } else if (batteryPercentage <= 30) {
            return FRAME_DELAY_20FPS; // Low battery - 20 FPS
        } else if (batteryPercentage <= 50) {
            return FRAME_DELAY_25FPS; // Medium battery - 25 FPS
        } else {
            return FRAME_DELAY_30FPS; // Good battery - 30 FPS
        }
    }

    /**
     * Determine if hardware acceleration should be used.
     * On Wear OS, hardware acceleration is generally beneficial.
     * 
     * @param context Application context
     * @return True if hardware acceleration should be enabled
     */
    public static boolean shouldUseHardwareAcceleration(Context context) {
        // Hardware acceleration is beneficial on modern Wear OS devices
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    /**
     * Check if WebP format is supported (Android 4.0+).
     * WebP provides better compression than PNG with alpha support.
     * 
     * @return True if WebP is supported
     */
    public static boolean isWebPSupported() {
        // WebP (lossy) supported since API 14 (Android 4.0)
        // WebP (lossless/alpha) supported since API 18 (Android 4.3)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    }

    /**
     * Check if animated WebP is supported (Android 9.0+).
     * 
     * @return True if animated WebP is supported
     */
    public static boolean isAnimatedWebPSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /**
     * Get recommended preload frame count based on available memory.
     * More memory = more preloaded frames = smoother playback.
     * 
     * @return Number of frames to preload ahead
     */
    public static int getPreloadFrameCount() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024)); // MB
        
        if (maxMemory >= 512) {
            return 5; // Generous memory - preload 5 frames
        } else if (maxMemory >= 256) {
            return 3; // Standard memory - preload 3 frames
        } else {
            return 2; // Low memory - preload 2 frames minimum
        }
    }

    /**
     * Check if the device is likely to be memory-constrained.
     * Useful for deciding whether to use aggressive caching.
     * 
     * @return True if device has limited memory
     */
    public static boolean isLowMemoryDevice() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024)); // MB
        return maxMemory < 256;
    }

    /**
     * Get the estimated memory footprint for a single frame.
     * 
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param hasAlpha True if frame has alpha channel (ARGB_8888)
     * @return Estimated memory in bytes
     */
    public static int estimateFrameMemory(int width, int height, boolean hasAlpha) {
        int bytesPerPixel = hasAlpha ? 4 : 2; // ARGB_8888 = 4, RGB_565 = 2
        return width * height * bytesPerPixel;
    }

    /**
     * Calculate how many frames can fit in a given cache size.
     * 
     * @param cacheSizeKB Cache size in KB
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @param hasAlpha True if frames have alpha channel
     * @return Approximate number of frames that can be cached
     */
    public static int estimateCacheableFrames(int cacheSizeKB, int frameWidth, int frameHeight, boolean hasAlpha) {
        int frameBytes = estimateFrameMemory(frameWidth, frameHeight, hasAlpha);
        int frameSizeKB = frameBytes / 1024;
        
        if (frameSizeKB == 0) frameSizeKB = 1; // Prevent division by zero
        
        return cacheSizeKB / frameSizeKB;
    }
}
