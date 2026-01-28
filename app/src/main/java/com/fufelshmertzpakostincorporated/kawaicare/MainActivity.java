package com.fufelshmertzpakostincorporated.kawaicare;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ImageView;

/**
 * Main Activity / Watch Face Home Launcher.
 * Coordinates Sensor Input, User Gestures, Learning Mode, and the Animation State Engine.
 */
public class MainActivity extends Activity implements
        SensorController.SensorStateListener,
        AnimationStateRepository.AnimationStateListener,
        GestureRecordingController.RecordingListener {

    // Broadcast actions for Learning Mode
    public static final String ACTION_START_LEARNING = "com.fufelshmertzpakostincorporated.kawaicare.START_LEARNING";
    public static final String ACTION_STOP_LEARNING = "com.fufelshmertzpakostincorporated.kawaicare.STOP_LEARNING";

    // Controllers
    private SensorController sensorController;
    private GestureRecordingController gestureRecordingController;
    private AnimationRenderer animationRenderer;
    private GestureDetector gestureDetector;

    // Views
    private ImageView imageView;

    // Learning Mode state
    private AnimationRenderer.AnimState stateBeforeLearning;

    // Broadcast receiver for learning mode commands
    private final BroadcastReceiver learningModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            switch (intent.getAction()) {
                case ACTION_START_LEARNING:
                    startLearningMode();
                    break;
                case ACTION_STOP_LEARNING:
                    stopLearningMode();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Setup Full Screen View
        imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        setContentView(imageView);

        // 2. Initialize Animation Subsystem
        animationRenderer = new AnimationRenderer(this, imageView);
        loadAssets();

        // 3. Initialize Sensor Controller
        sensorController = new SensorController(this);
        sensorController.setListener(this);

        // 4. Initialize Gesture Recording Controller
        gestureRecordingController = new GestureRecordingController(this);
        gestureRecordingController.setListener(this);

        // 5. Initialize Gesture Detector
        gestureDetector = new GestureDetector(this, new GestureListener());

        // 6. Set screen dimensions once layout is complete
        imageView.post(() -> 
            gestureRecordingController.setScreenDimensions(imageView.getWidth(), imageView.getHeight())
        );
    }

    private void loadAssets() {
        // Initialize with default animation
        animationRenderer.setFolderAnimation("wink", true);
        animationRenderer.setState(AnimationRenderer.AnimState.IDLE);
    }

    // Add this helper method to switch animations based on state
    private void setAnimationForState(AnimationRenderer.AnimState state) {
        String folderPath;
        switch (state) {
            case IDLE:
                folderPath = "wink";
                break;
            case TILTED:
                folderPath = "turn_left";
                break;
            case GESTURE_ACTION:
                folderPath = "nods";
                break;
            case SHAKE:
                folderPath = "shake";
                break;
            case ALARM:
                folderPath = "turn_right";
                break;
            case LEARNING:
                folderPath = "turn_right";
                break;
            default:
                folderPath = "wink";
                break;
        }
        
        animationRenderer.setFolderAnimation(folderPath, true);
        animationRenderer.setState(state);
    }

    // --- Lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        sensorController.start();
        animationRenderer.start();
        AnimationStateRepository.getInstance().addListener(this);

        // Register learning mode receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_LEARNING);
        filter.addAction(ACTION_STOP_LEARNING);
        registerReceiver(learningModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorController.stop();
        animationRenderer.stop();
        AnimationStateRepository.getInstance().removeListener(this);

        // Unregister receiver
        try {
            unregisterReceiver(learningModeReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        // Stop recording if active
        if (gestureRecordingController.isRecording()) {
            gestureRecordingController.stopRecording(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gestureRecordingController.release();
    }

    // --- Touch Handling ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Learning mode: delegate to gesture recording controller
        if (gestureRecordingController.isRecording()) {
            boolean shouldStop = gestureRecordingController.onTouchEvent(event);
            if (shouldStop) {
                gestureRecordingController.stopRecording(true);
            }
            return true;
        }

        // Normal mode: use gesture detector
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // --- Gesture Listener ---

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            setAnimationForState(AnimationRenderer.AnimState.GESTURE_ACTION);

            // Revert to IDLE after 2 seconds
            imageView.postDelayed(() -> {
                if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.GESTURE_ACTION) {
                    setAnimationForState(AnimationRenderer.AnimState.IDLE);
                }
            }, 2000);

            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        }
    }

    // --- SensorController.SensorStateListener ---

    @Override
    public void onTiltDetected(float zAxisValue) {
        if (gestureRecordingController.isRecording()) return;

        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.IDLE) {
            setAnimationForState(AnimationRenderer.AnimState.TILTED);
        }
    }

    @Override
    public void onStable() {
        if (gestureRecordingController.isRecording()) return;

        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.TILTED) {
            setAnimationForState(AnimationRenderer.AnimState.IDLE);
        }
    }

    @Override
    public void onShake() {
        if (gestureRecordingController.isRecording()) return;

        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.IDLE) {
            setAnimationForState(AnimationRenderer.AnimState.SHAKE);
            
            // Return to idle after shake animation
            imageView.postDelayed(() -> {
                if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.SHAKE) {
                    setAnimationForState(AnimationRenderer.AnimState.IDLE);
                }
            }, 1000);
        }
    }

    // --- AnimationStateRepository.AnimationStateListener ---

    @Override
    public void onAnimationStateChanged(AnimationRenderer.AnimState state) {
        if (imageView != null) {
            imageView.post(() -> setAnimationForState(state));
        }
    }

    // --- GestureRecordingController.RecordingListener ---

    @Override
    public void onRecordingStarted() {
        // Recording started - animation already set to LEARNING
    }

    @Override
    public void onRecordingStopped(boolean success) {
        // Restore previous animation state
        AnimationRenderer.AnimState restoreState = (stateBeforeLearning != null)
                ? stateBeforeLearning
                : AnimationRenderer.AnimState.IDLE;
        setAnimationForState(restoreState);
        stateBeforeLearning = null;
    }

    // --- Learning Mode Control ---

    private void startLearningMode() {
        if (gestureRecordingController.isRecording()) return;

        // Save current state to restore later
        stateBeforeLearning = animationRenderer.getCurrentState();

        // Switch to LEARNING emotion
        setAnimationForState(AnimationRenderer.AnimState.LEARNING);

        // Start recording
        gestureRecordingController.startRecording();
    }

    private void stopLearningMode() {
        gestureRecordingController.stopRecording(true);
    }

    // --- Alarm Trigger (for external use) ---

    public void onAlarmTriggered() {
        setAnimationForState(AnimationRenderer.AnimState.ALARM);
        imageView.postDelayed(() -> setAnimationForState(AnimationRenderer.AnimState.IDLE), 5000);
    }
}
