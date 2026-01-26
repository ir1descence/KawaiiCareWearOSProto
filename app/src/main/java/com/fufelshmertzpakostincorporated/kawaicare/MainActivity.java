package com.fufelshmertzpakostincorporated.kawaicare;

import android.app.Activity;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.content.Intent;
import android.graphics.Color;

/**
 * Main Activity / Watch Face Home Launcher.
 * Co-ordinates Sensor Input, User Gestures, and the Animation State Engine.
 */
public class MainActivity extends Activity implements SensorController.SensorStateListener, AnimationStateRepository.AnimationStateListener {

    private SensorController sensorController;
    private AnimationRenderer animationRenderer;
    private GestureDetector gestureDetector;
    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. Setup Full Screen View
        imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); // Or CENTER_CROP
        setContentView(imageView);

        // 2. Initialize Animation Subsystem
        animationRenderer = new AnimationRenderer(this, imageView);
        loadAssets(); // Setup frames

        // 3. Initialize Sensor Subsystem
        sensorController = new SensorController(this);
        sensorController.setListener(this);

        // 4. Initialize Gesture Subsystem
        gestureDetector = new GestureDetector(this, new GestureListener());
    }

    private void loadAssets() {
        // Initialize Face Data Model
        Face face = new Face();

        // 1. Set Coordinates (Example placeholder values)
        face.setCoordinate(Face.BODY, 0, 0);
        face.setCoordinate(Face.LEFT_EYEBROW, 300, 80);
        face.setCoordinate(Face.RIGHT_EYEBROW, 80, 80);
        face.setCoordinate(Face.LEFT_EYE, 250, 150);
        face.setCoordinate(Face.RIGHT_EYE, 30, 150);
        face.setCoordinate(Face.NOSE, 200, 320);
        face.setCoordinate(Face.MOUTH, 200, 375);

        // 2. Configure Paths
        face.setBodyPath("skins/body.png");

        // Map IDLE State to 'looks_right' emotion folder
        face.initializeEmotion(AnimationRenderer.AnimState.IDLE, "looks_right");
        
        // Map other states to the same data for now, as only one emotion exists
        face.initializeEmotion(AnimationRenderer.AnimState.TILTED, "looks_right");
        face.initializeEmotion(AnimationRenderer.AnimState.GESTURE_ACTION, "looks_right");
        face.initializeEmotion(AnimationRenderer.AnimState.SHAKE, "looks_right");
        face.initializeEmotion(AnimationRenderer.AnimState.ALARM, "looks_right");

        // 3. Inject into Renderer
        animationRenderer.setFace(face);
        animationRenderer.setState(AnimationRenderer.AnimState.IDLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Optimization: Only run when visible
        sensorController.start();
        animationRenderer.start();
        // Listen for animation state updates coming from the connected device
        AnimationStateRepository.getInstance().addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Optimization: Stop everything to save battery
        sensorController.stop();
        animationRenderer.stop();
        // Stop listening while paused
        AnimationStateRepository.getInstance().removeListener(this);
    }

    // --- Gesture Handling ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Trigger Action State
            animationRenderer.setState(AnimationRenderer.AnimState.GESTURE_ACTION);
            
            // Revert back to IDLE after some time or let AnimationRenderer handle one-shot
            // For this architecture, we let the Renderer loop reset or we reset here with a handler.
            imageView.postDelayed(() -> {
               if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.GESTURE_ACTION) {
                   animationRenderer.setState(AnimationRenderer.AnimState.IDLE);
               }
            }, 2000); // 2 seconds action
            
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            // Open Settings on Long Press
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        }
    }

    // --- Sensor Handling (Observer Pattern) ---

    @Override
    public void onTiltDetected(float zAxisValue) {
        // Logic: Only switch to TILTED if we are currently IDLE
        // (Don't interrupt a Gesture Action)
        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.IDLE) {
            animationRenderer.setState(AnimationRenderer.AnimState.TILTED);
        }
    }

    @Override
    public void onStable() {
        // Go back to IDLE if we were Tilted
        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.TILTED) {
            animationRenderer.setState(AnimationRenderer.AnimState.IDLE);
        }
    }

    @Override
    public void onShake() {
        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.IDLE) {
            animationRenderer.setState(AnimationRenderer.AnimState.TILTED);
        }
    }

    @Override
    public void onAnimationStateChanged(AnimationRenderer.AnimState state) {
        // Ensure UI thread when applying state changes from the repository
        if (imageView != null) {
            imageView.post(() -> {
                animationRenderer.setState(state);
            });
        }
    }

    /**
     * Example method to be called by a BroadcastReceiver or AlarmManager callback.
     */
    public void onAlarmTriggered() {
        animationRenderer.setState(AnimationRenderer.AnimState.ALARM);
        // Reset after 5 seconds
        imageView.postDelayed(() -> animationRenderer.setState(AnimationRenderer.AnimState.IDLE), 5000);
    }
}
