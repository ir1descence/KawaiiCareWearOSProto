package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for managing alarm-related functionality.
 * Handles WakeLock, MediaPlayer for alarm sounds, and vibration.
 * 
 * Usage:
 * 1. Call startAlarm() when alarm triggers
 * 2. Call stopAlarm() when alarm is dismissed
 * 3. Call release() when no longer needed (e.g., onDestroy)
 */
public class AlarmManagerUtils {

    private static final String TAG = "AlarmManagerUtils";

    // WakeLock timeout (5 minutes max to prevent battery drain)
    private static final long WAKELOCK_TIMEOUT_MS = 5 * 60 * 1000L;

    // Vibration pattern for alarm (on-off-on-off in ms)
    private static final long[] VIBRATION_PATTERN = {0, 500, 200, 500, 200, 500};

    // Audio resources - place your alarm sound in res/raw/alarm_melody.mp3
    private static final String DEFAULT_ALARM_ASSET = "alarm_melody";

    /**
     * Listener interface for alarm state events.
     */
    public interface AlarmListener {
        void onAlarmStarted();
        void onAlarmStopped();
        void onAlarmError(String message);
    }

    private final Context context;
    private final Handler mainHandler;
    private AlarmListener listener;

    // ExecutorService for background sound loading (prevents memory leaks)
    private final ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

    // WakeLock for keeping screen on
    private PowerManager.WakeLock wakeLock;

    // MediaPlayer for alarm sound
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;

    // Vibrator for haptic feedback
    private Vibrator vibrator;
    private boolean isVibrating = false;

    // Configuration
    private boolean vibrateEnabled = true;
    private boolean soundEnabled = true;
    private float volume = 1.0f;

    public AlarmManagerUtils(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Initialize vibrator
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Set the listener for alarm events.
     */
    public void setListener(@Nullable AlarmListener listener) {
        this.listener = listener;
    }

    /**
     * Configure alarm settings.
     * 
     * @param soundEnabled Enable/disable alarm sound
     * @param vibrateEnabled Enable/disable vibration
     * @param volume Volume level (0.0 to 1.0)
     */
    public void configure(boolean soundEnabled, boolean vibrateEnabled, float volume) {
        this.soundEnabled = soundEnabled;
        this.vibrateEnabled = vibrateEnabled;
        this.volume = Math.max(0f, Math.min(1f, volume));
    }

    /**
     * Check if alarm is currently active.
     */
    public boolean isAlarmActive() {
        return isPlaying || isVibrating;
    }

    /**
     * Start the alarm (sound, vibration, wakelock).
     * Safe to call multiple times - will not restart if already playing.
     */
    public void startAlarm() {
        if (isAlarmActive()) {
            Log.w(TAG, "Alarm already active, ignoring start request");
            return;
        }

        Log.d(TAG, "Starting alarm");

        // 1. Acquire WakeLock
        acquireWakeLock();

        // 2. Start sound (on background thread to avoid blocking)
        if (soundEnabled) {
            startAlarmSound();
        }

        // 3. Start vibration
        if (vibrateEnabled) {
            startVibration();
        }

        // Notify listener
        notifyAlarmStarted();
    }

    /**
     * Stop the alarm completely.
     */
    public void stopAlarm() {
        Log.d(TAG, "Stopping alarm");

        // Stop sound
        stopAlarmSound();

        // Stop vibration
        stopVibration();

        // Release WakeLock
        releaseWakeLock();

        // Notify listener
        notifyAlarmStopped();
    }

    /**
     * Release all resources. Call in onDestroy.
     */
    public void release() {
        stopAlarm();
        
        // Shutdown executor to prevent memory leaks
        soundExecutor.shutdownNow();
        
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // --- WakeLock Management ---

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                Log.e(TAG, "PowerManager not available");
                return;
            }

            // Create partial wake lock (keeps CPU running)
            // Use SCREEN_BRIGHT_WAKE_LOCK to keep screen on for alarm
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "KawaiiCare:AlarmWakeLock"
            );

            // Acquire with timeout to prevent battery drain
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            Log.d(TAG, "WakeLock acquired");

        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing WakeLock", e);
        } finally {
            wakeLock = null;
        }
    }

    // --- Sound Management ---

    private void startAlarmSound() {
        // Run on background thread using managed ExecutorService to prevent memory leaks
        soundExecutor.execute(() -> {
            try {
                // Release previous player if exists
                if (mediaPlayer != null) {
                    mediaPlayer.release();
                }

                // Create new MediaPlayer
                mediaPlayer = new MediaPlayer();

                // Set audio attributes for alarm
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                mediaPlayer.setAudioAttributes(audioAttributes);

                // Try to load from raw resources first
                int rawResourceId = context.getResources().getIdentifier(
                        DEFAULT_ALARM_ASSET, "raw", context.getPackageName());

                if (rawResourceId != 0) {
                    // Load from raw resource
                    mediaPlayer = MediaPlayer.create(context, rawResourceId);
                    if (mediaPlayer != null) {
                        setupMediaPlayer();
                    } else {
                        throw new IOException("Failed to create MediaPlayer from raw resource");
                    }
                } else {
                    // Fallback: try loading from assets
                    try {
                        android.content.res.AssetFileDescriptor afd = 
                                context.getAssets().openFd("sounds/" + DEFAULT_ALARM_ASSET + ".mp3");
                        mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                        afd.close();
                        mediaPlayer.prepare();
                        setupMediaPlayer();
                    } catch (IOException assetError) {
                        Log.w(TAG, "No alarm sound found, using system default or silent");
                        // Could fall back to system ringtone here
                        mainHandler.post(() -> notifyAlarmError("No alarm sound file found"));
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error starting alarm sound", e);
                mainHandler.post(() -> notifyAlarmError("Failed to play alarm sound: " + e.getMessage()));
            }
        });
    }

    private void setupMediaPlayer() {
        if (mediaPlayer == null) return;

        mediaPlayer.setLooping(true);
        mediaPlayer.setVolume(volume, volume);

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
            isPlaying = false;
            return false;
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            // Should not happen with looping, but handle just in case
            Log.d(TAG, "MediaPlayer completed");
        });

        // Start playback on main thread
        mainHandler.post(() -> {
            try {
                mediaPlayer.start();
                isPlaying = true;
                Log.d(TAG, "Alarm sound started");
            } catch (Exception e) {
                Log.e(TAG, "Error starting MediaPlayer", e);
            }
        });
    }

    private void stopAlarmSound() {
        isPlaying = false;
        
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping alarm sound", e);
            }
        }
        Log.d(TAG, "Alarm sound stopped");
    }

    // --- Vibration Management ---

    private void startVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "Vibrator not available");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+: Use VibrationEffect
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)); // repeat from index 0
            } else {
                // Legacy vibration
                vibrator.vibrate(VIBRATION_PATTERN, 0);
            }
            isVibrating = true;
            Log.d(TAG, "Vibration started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting vibration", e);
        }
    }

    private void stopVibration() {
        isVibrating = false;
        
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping vibration", e);
            }
        }
        Log.d(TAG, "Vibration stopped");
    }

    // --- Listener Notifications ---

    private void notifyAlarmStarted() {
        if (listener != null) {
            mainHandler.post(listener::onAlarmStarted);
        }
    }

    private void notifyAlarmStopped() {
        if (listener != null) {
            mainHandler.post(listener::onAlarmStopped);
        }
    }

    private void notifyAlarmError(String message) {
        if (listener != null) {
            mainHandler.post(() -> listener.onAlarmError(message));
        }
    }
}
