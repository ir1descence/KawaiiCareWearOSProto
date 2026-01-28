package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Modular Animation Manager.
 * Handles frame-by-frame cycling.
 * Supports two modes: legacy FaceCompositor or direct folder animation.
 */
public class AnimationRenderer {

    // State Pattern: Enums defining abstract states
    public enum AnimState {
        IDLE,
        TILTED,
        GESTURE_ACTION,
        SHAKE,
        ALARM,
        LEARNING  // Learning mode for gesture recording
    }

    // Animation modes
    public enum AnimationMode {
        COMPOSITOR,  // Legacy mode using FaceCompositor
        FOLDER       // New mode using complete images from folder
    }

    private final Context context;
    private final ImageView targetView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // Default initial state
    private AnimState currentState = AnimState.IDLE;
    private boolean isRunning = false;
    private int currentFrameIndex = 0;

    // Optimization: Frames per second target (e.g., 15 FPS for battery saving)
    private static final long FRAME_DELAY_MS = 1;

    // Legacy compositor mode
    private Face face;
    private FaceCompositor compositor;

    // New folder animation mode
    private AnimationMode mode = AnimationMode.COMPOSITOR;
    private String currentFolderPath;
    private List<String> frameFiles = new ArrayList<>();
    private List<Bitmap> cachedFrames = new ArrayList<>();
    private boolean enableFrameCache = false;

    public AnimationRenderer(Context context, ImageView targetView) {
        this.context = context;
        this.targetView = targetView;
    }

    // Legacy mode setter
    public void setFace(Face face) {
        this.face = face;
        this.mode = AnimationMode.COMPOSITOR;
        compositor = null;
        clearFolderAnimation();
    }

    /**
     * New mode: Set animation from folder containing complete face images.
     * @param assetsFolderPath Path in assets folder (e.g., "emotions/happy")
     * @param cacheFrames If true, preloads all frames into memory (faster but uses more RAM)
     */
    public void setFolderAnimation(String assetsFolderPath, boolean cacheFrames) {
        this.mode = AnimationMode.FOLDER;
        this.currentFolderPath = assetsFolderPath;
        this.enableFrameCache = cacheFrames;
        this.currentFrameIndex = 0;
        
        // Clear legacy mode
        face = null;
        compositor = null;
        
        loadFramesList();
        
        if (enableFrameCache) {
            preloadFrames();
        }
    }

    /**
     * Convenience method without caching
     */
    public void setFolderAnimation(String assetsFolderPath) {
        setFolderAnimation(assetsFolderPath, false);
    }

    private void loadFramesList() {
        frameFiles.clear();
        try {
            String[] files = context.getAssets().list(currentFolderPath);
            if (files != null) {
                List<String> imageFiles = new ArrayList<>();
                for (String file : files) {
                    String lower = file.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                        imageFiles.add(file);
                    }
                }
                Collections.sort(imageFiles);  // Natural alphabetical order
                frameFiles.addAll(imageFiles);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void preloadFrames() {
        cachedFrames.clear();
        for (String fileName : frameFiles) {
            Bitmap frame = loadFrameFromAssets(currentFolderPath + "/" + fileName);
            if (frame != null) {
                cachedFrames.add(frame);
            }
        }
    }

    private void clearFolderAnimation() {
        frameFiles.clear();
        recycleCachedFrames();
    }

    private void recycleCachedFrames() {
        for (Bitmap bitmap : cachedFrames) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        cachedFrames.clear();
    }

    private Bitmap loadFrameFromAssets(String path) {
        try {
            InputStream is = context.getAssets().open(path);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            return bitmap;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
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
        if (mode == AnimationMode.FOLDER) {
            renderFolderFrame();
        } else {
            renderCompositorFrame();
        }
    }

    private void renderFolderFrame() {
        if (frameFiles.isEmpty()) return;

        // Loop animation
        if (currentFrameIndex >= frameFiles.size()) {
            currentFrameIndex = 0;
        }

        Bitmap frame;
        if (enableFrameCache && currentFrameIndex < cachedFrames.size()) {
            frame = cachedFrames.get(currentFrameIndex);
        } else {
            String framePath = currentFolderPath + "/" + frameFiles.get(currentFrameIndex);
            frame = loadFrameFromAssets(framePath);
        }

        if (frame != null) {
            targetView.setImageBitmap(frame);
            targetView.invalidate();
        }

        currentFrameIndex++;
    }

    private void renderCompositorFrame() {
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

    /**
     * Cleanup method to release resources
     */
    public void release() {
        stop();
        recycleCachedFrames();
    }
}
