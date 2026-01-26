package com.fufelshmertzpakostincorporated.kawaicare;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/**
 * Modular Animation Manager.
 * Handles frame-by-frame cycling.
 * Uses FaceCompositor to render layered animations.
 */
public class AnimationRenderer {

    // State Pattern: Enums defining abstract states
    public enum AnimState {
        IDLE,
        TILTED,
        GESTURE_ACTION,
        SHAKE,
        ALARM
    }

    private final Context context;
    private final ImageView targetView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // Default initial state
    private AnimState currentState = AnimState.IDLE;
    private boolean isRunning = false;
    private int currentFrameIndex = 0;

    // Optimization: Frames per second target (e.g., 15 FPS for battery saving)
    private static final long FRAME_DELAY_MS = 52;

    private Face face;
    private FaceCompositor compositor;

    public AnimationRenderer(Context context, ImageView targetView) {
        this.context = context;
        this.targetView = targetView;
    }

    public void setFace(Face face) {
        this.face = face;
        // Reset compositor to force re-initialization with new face
        compositor = null;
    }

    /**
     * Changes the current animation state.
     * Resets frame index if state changed.
     */
    public void setState(AnimState state) {
        if (currentState != state) {
            currentState = state;
            currentFrameIndex = 0;
            // Trigger immediate update
            handler.removeCallbacks(loop);
            if (isRunning) {
                handler.post(loop);
            }
        }
    }

    public AnimState getCurrentState() {
        return currentState;
    }

    /**
     * Lifecycle: Start animation loop.
     * Should be called in onResume.
     */
    public void start() {
        if (!isRunning) {
            isRunning = true;
            handler.post(loop);
        }
    }

    /**
     * Lifecycle: Stop animation loop.
     * Critical for battery optimization in onPause.
     */
    public void stop() {
        isRunning = false;
        handler.removeCallbacks(loop);
    }

    // The Animation Loop
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            renderNextFrame();

            // Schedule next frame
            handler.postDelayed(this, FRAME_DELAY_MS);
        }
    };

    private void renderNextFrame() {
        if (face == null) return;

        // Lazy init to ensure view dimensions are available
        if (compositor == null) {
            if (targetView.getWidth() > 0 && targetView.getHeight() > 0) {
                compositor = new FaceCompositor(context, face, targetView.getWidth(), targetView.getHeight());
            } else {
                // Skip this frame if view not ready
                return;
            }
        }

        compositor.composeFrame(currentState, currentFrameIndex);
        
        // Efficiently update view with the reused master bitmap
        targetView.setImageBitmap(compositor.getLatestFrame());
        // Invalidate to ensure the view redraws the updated bitmap content
        targetView.invalidate();
        
        currentFrameIndex++;
    }
}
