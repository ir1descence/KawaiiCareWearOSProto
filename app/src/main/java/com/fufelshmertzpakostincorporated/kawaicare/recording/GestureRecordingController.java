package com.fufelshmertzpakostincorporated.kawaicare.recording;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import com.fufelshmertzpakostincorporated.kawaicare.model.GestureSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for gesture recording in Learning Mode.
 * Handles dual-sensor recording (touch + motion), data fusion,
 * normalization, storage, and wearable messaging.
 * 
 * Follows the same pattern as SensorController for consistency.
 */
public class GestureRecordingController implements SensorEventListener {

    private static final String TAG = "GestureRecordingCtrl";

    // Wearable message paths
    public static final String PATH_RECORDING_SUCCESS = "/recording_success";
    public static final String PATH_RECORDING_FAILED = "/recording_failed";

    // Recording configuration
    private static final long MAX_RECORDING_DURATION_MS = 10_000; // 10 seconds
    private static final long MIN_RECORDING_DURATION_MS = 500;    // 0.5 seconds
    private static final float LOW_PASS_ALPHA = 0.25f;

    // Haptic patterns (ms)
    private static final long VIBRATE_START_MS = 100;
    private static final long[] VIBRATE_PATTERN_SUCCESS = {0, 50, 100, 50};
    private static final long[] VIBRATE_PATTERN_FAIL = {0, 200, 100, 200};

    /**
     * Listener interface for recording state changes.
     */
    public interface RecordingListener {
        void onRecordingStarted();
        void onRecordingStopped(boolean success);
    }

    // Context and system services
    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private final Vibrator vibrator;
    private final Handler mainHandler;
    private final ExecutorService executor;

    // Listener
    private RecordingListener listener;

    // Recording state
    private GestureSession currentSession;
    private boolean isRecording = false;
    private long sessionStartNanos;
    private int screenWidth;
    private int screenHeight;

    // Low-pass filtered sensor values
    private final float[] filteredAccel = new float[3];
    private final float[] filteredGyro = new float[3];
    private int accelAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    private int gyroAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    private boolean accelInitialized = false;
    private boolean gyroInitialized = false;

    // Auto-stop runnable
    private final Runnable autoStopRunnable = () -> {
        if (isRecording) {
            Log.d(TAG, "Auto-stopping after max duration");
            stopRecording(true);
        }
    };

    public GestureRecordingController(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();

        // Initialize sensors
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        } else {
            accelerometer = null;
            gyroscope = null;
        }

        // Initialize vibrator
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Set the listener for recording state changes.
     */
    public void setListener(RecordingListener listener) {
        this.listener = listener;
    }

    /**
     * Set screen dimensions for touch normalization.
     * Should be called when view dimensions are known.
     */
    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Start a new recording session.
     */
    public void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording, ignoring start request");
            return;
        }

        Log.d(TAG, "Starting gesture recording");

        // Create new session
        currentSession = new GestureSession();
        currentSession.setScreenDimensions(screenWidth, screenHeight);

        // Reset filter state
        resetFilters();

        // Mark recording start
        sessionStartNanos = System.nanoTime();
        isRecording = true;

        // Register sensors
        registerSensors();

        // Haptic feedback
        vibrateStart();

        // Schedule auto-stop
        mainHandler.postDelayed(autoStopRunnable, MAX_RECORDING_DURATION_MS);

        // Notify listener
        if (listener != null) {
            listener.onRecordingStarted();
        }
    }

    /**
     * Stop the current recording session.
     *
     * @param save Whether to save the recorded session
     */
    public void stopRecording(boolean save) {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        // Unregister sensors
        unregisterSensors();

        // Cancel auto-stop
        mainHandler.removeCallbacks(autoStopRunnable);

        if (currentSession == null) {
            notifyRecordingStopped(false);
            return;
        }

        long durationMs = currentSession.getDurationMillis();
        int frameCount = currentSession.getFrameCount();
        Log.d(TAG, "Stopped recording. Duration: " + durationMs + "ms, Frames: " + frameCount);

        boolean isValid = save && durationMs >= MIN_RECORDING_DURATION_MS && frameCount > 0;

        if (isValid) {
            vibrateSuccess();
            saveSessionAsync(currentSession);
        } else {
            vibrateFail();
            sendMessage(PATH_RECORDING_FAILED);
            notifyRecordingStopped(false);
        }

        currentSession = null;
    }

    /**
     * Process a touch event during recording.
     * Call this from Activity's onTouchEvent when isRecording() is true.
     *
     * @param event The motion event
     * @return true if the event should stop recording (ACTION_UP after min duration)
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (!isRecording || currentSession == null) {
            return false;
        }

        recordTouchFrame(event);

        // Check if should stop (touch up after minimum duration)
        if (event.getAction() == MotionEvent.ACTION_UP) {
            return currentSession.getDurationMillis() >= MIN_RECORDING_DURATION_MS;
        }

        return false;
    }

    /**
     * Release resources. Call when the controller is no longer needed.
     */
    public void release() {
        stopRecording(false);
        executor.shutdown();
    }

    // --- Private Methods ---

    private void resetFilters() {
        accelInitialized = false;
        gyroInitialized = false;
        for (int i = 0; i < 3; i++) {
            filteredAccel[i] = 0;
            filteredGyro[i] = 0;
        }
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

    private void recordTouchFrame(MotionEvent event) {
        GestureSession.GestureFrame frame = currentSession.createFrame(System.nanoTime(), sessionStartNanos);

        // Normalize touch coordinates (0.0 to 1.0)
        frame.touchPoint = new GestureSession.TouchPoint(
                normalizeValue(event.getX(), screenWidth),
                normalizeValue(event.getY(), screenHeight),
                clamp(event.getPressure(), 0f, 1f),
                event.getAction(),
                event.getPointerId(0)
        );

        // Attach current sensor readings
        attachSensorData(frame);

        currentSession.addFrame(frame);
    }

    private void recordSensorOnlyFrame() {
        if (currentSession == null || currentSession.getFrameCount() == 0) {
            return;
        }

        long currentNanos = System.nanoTime();
        GestureSession.GestureFrame lastFrame = currentSession.getFrames().get(currentSession.getFrameCount() - 1);
        long timeSinceLastFrame = (currentNanos - sessionStartNanos) - lastFrame.timestampNanos;

        // Add sensor frame every ~50ms (20Hz) when no touch events
        if (timeSinceLastFrame > 50_000_000L) {
            GestureSession.GestureFrame frame = currentSession.createFrame(currentNanos, sessionStartNanos);
            attachSensorData(frame);
            currentSession.addFrame(frame);
        }
    }

    private void attachSensorData(GestureSession.GestureFrame frame) {
        frame.accelerometer = new GestureSession.SensorReading(
                Sensor.TYPE_ACCELEROMETER,
                filteredAccel[0], filteredAccel[1], filteredAccel[2],
                accelAccuracy
        );
        frame.gyroscope = new GestureSession.SensorReading(
                Sensor.TYPE_GYROSCOPE,
                filteredGyro[0], filteredGyro[1], filteredGyro[2],
                gyroAccuracy
        );
    }

    private float normalizeValue(float value, float max) {
        return (max > 0) ? clamp(value / max, 0f, 1f) : 0.5f;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applyLowPassFilter(float[] input, float[] output, boolean initialized) {
        if (!initialized) {
            System.arraycopy(input, 0, output, 0, input.length);
        } else {
            for (int i = 0; i < input.length; i++) {
                output[i] = LOW_PASS_ALPHA * input[i] + (1f - LOW_PASS_ALPHA) * output[i];
            }
        }
    }

    // --- SensorEventListener ---

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRecording) return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                applyLowPassFilter(event.values, filteredAccel, accelInitialized);
                accelInitialized = true;
                accelAccuracy = event.accuracy;
                break;

            case Sensor.TYPE_GYROSCOPE:
                applyLowPassFilter(event.values, filteredGyro, gyroInitialized);
                gyroInitialized = true;
                gyroAccuracy = event.accuracy;
                break;
        }

        // Periodically record sensor-only frames
        recordSensorOnlyFrame();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                accelAccuracy = accuracy;
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyroAccuracy = accuracy;
                break;
        }
    }

    // --- Storage ---

    private void saveSessionAsync(final GestureSession session) {
        executor.execute(() -> {
            boolean success = saveSessionToFile(session);
            mainHandler.post(() -> {
                if (success) {
                    Log.d(TAG, "Session saved: " + session.getSessionId());
                    sendMessage(PATH_RECORDING_SUCCESS);
                } else {
                    Log.e(TAG, "Failed to save session");
                    sendMessage(PATH_RECORDING_FAILED);
                }
                notifyRecordingStopped(success);
            });
        });
    }

    private boolean saveSessionToFile(GestureSession session) {
        File dir = new File(context.getFilesDir(), "gestures");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create gestures directory");
            return false;
        }

        File file = new File(dir, session.getSessionId() + ".gesture");

        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(session);
            Log.d(TAG, "Saved to: " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving session", e);
            return false;
        }
    }

    // --- Wearable Messaging ---

    private void sendMessage(final String path) {
        executor.execute(() -> {
            try {
                Task<List<Node>> nodeTask = Wearable.getNodeClient(context).getConnectedNodes();
                List<Node> nodes = Tasks.await(nodeTask);

                for (Node node : nodes) {
                    Task<Integer> sendTask = Wearable.getMessageClient(context)
                            .sendMessage(node.getId(), path, null);
                    Tasks.await(sendTask);
                    Log.d(TAG, "Sent '" + path + "' to " + node.getDisplayName());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send: " + path, e);
            }
        });
    }

    // --- Haptic Feedback ---

    private void vibrateStart() {
        vibrate(VIBRATE_START_MS);
    }

    private void vibrateSuccess() {
        vibrate(VIBRATE_PATTERN_SUCCESS);
    }

    private void vibrateFail() {
        vibrate(VIBRATE_PATTERN_FAIL);
    }

    private void vibrate(long durationMs) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(durationMs);
        }
    }

    private void vibrate(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    // --- Listener Notification ---

    private void notifyRecordingStopped(boolean success) {
        if (listener != null) {
            listener.onRecordingStopped(success);
        }
    }
}
