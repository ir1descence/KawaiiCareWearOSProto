package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.ImageView;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Modular Animation Manager.
 * Handles frame-by-frame cycling.
 * Supports two modes: legacy FaceCompositor or direct folder animation.
 * 
 * Bitmap loading is done on a background thread to prevent UI freezing.
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
    
    // Background thread for bitmap loading
    private HandlerThread loadingThread;
    private Handler loadingHandler;
    
    // Default initial state
    private AnimState currentState = AnimState.IDLE;
    private volatile boolean isRunning = false;
    private int currentFrameIndex = 0;

    // Optimization: Target ~20 FPS for battery efficiency on Wear OS
    private static final long FRAME_DELAY_MS = 33;

    // Legacy compositor mode
    private Face face;
    private FaceCompositor compositor;

    // New folder animation mode
    private AnimationMode mode = AnimationMode.COMPOSITOR;
    private volatile String currentFolderPath;
    private List<String> frameFiles = new ArrayList<>();
    private final CopyOnWriteArrayList<Bitmap> cachedFrames = new CopyOnWriteArrayList<>();
    private boolean enableFrameCache = false;
    private volatile boolean isPreloading = false;

    // Track previous non-cached frame for memory management
    private Bitmap previousNonCachedFrame;

    public AnimationRenderer(Context context, ImageView targetView) {
        this.context = context;
        this.targetView = targetView;
        
        // Initialize background loading thread
        loadingThread = new HandlerThread("AnimationLoader", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        loadingThread.start();
        loadingHandler = new Handler(loadingThread.getLooper());
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
        // Skip if same folder already loaded
        if (assetsFolderPath.equals(currentFolderPath) && !cachedFrames.isEmpty()) {
            return;
        }
        
        this.mode = AnimationMode.FOLDER;
        this.currentFolderPath = assetsFolderPath;
        this.enableFrameCache = cacheFrames;
        this.currentFrameIndex = 0;
        
        // Clear legacy mode
        face = null;
        compositor = null;
        
        loadFramesList();
        
        if (enableFrameCache) {
            preloadFramesAsync();
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

    private void preloadFramesAsync() {
        if (isPreloading) return;
        isPreloading = true;
        
        final String folderToLoad = currentFolderPath;
        final List<String> filesToLoad = new ArrayList<>(frameFiles);
        
        // Clear old cached frames first
        recycleCachedFrames();
        
        loadingHandler.post(() -> {
            List<Bitmap> loadedFrames = new ArrayList<>();
            for (String fileName : filesToLoad) {
                // Check if we're still loading the same folder
                if (!folderToLoad.equals(currentFolderPath)) {
                    // Folder changed, abort loading
                    for (Bitmap b : loadedFrames) {
                        if (b != null && !b.isRecycled()) b.recycle();
                    }
                    isPreloading = false;
                    return;
                }
                
                Bitmap frame = loadFrameFromAssets(folderToLoad + "/" + fileName);
                if (frame != null) {
                    loadedFrames.add(frame);
                }
            }
            
            // Only update if still the same folder
            if (folderToLoad.equals(currentFolderPath)) {
                cachedFrames.clear();
                cachedFrames.addAll(loadedFrames);
            } else {
                // Clean up since folder changed
                for (Bitmap b : loadedFrames) {
                    if (b != null && !b.isRecycled()) b.recycle();
                }
            }
            isPreloading = false;
        });
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
        try (InputStream is = context.getAssets().open(path)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888; // preserve alpha
            opts.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (bitmap != null) bitmap.setHasAlpha(true);
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

        Bitmap frame = null;
        
        // Try to get from cache first
        if (enableFrameCache && currentFrameIndex < cachedFrames.size()) {
            try {
                frame = cachedFrames.get(currentFrameIndex);
            } catch (IndexOutOfBoundsException e) {
                // Cache might be clearing, will load on-demand below
            }
        }
        
        // If not cached or cache miss, load on-demand (but this should be rare with async preload)
        if (frame == null || frame.isRecycled()) {
            // Recycle previous non-cached frame to prevent memory leak
            if (previousNonCachedFrame != null && !previousNonCachedFrame.isRecycled()) {
                previousNonCachedFrame.recycle();
            }
            String framePath = currentFolderPath + "/" + frameFiles.get(currentFrameIndex);
            frame = loadFrameFromAssets(framePath);
            previousNonCachedFrame = frame;
        }

        if (frame != null && !frame.isRecycled()) {
            targetView.setImageBitmap(frame);
            // Removed explicit invalidate() - setImageBitmap handles this
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
        
        currentFrameIndex++;
    }

    /**
     * Cleanup method to release resources
     */
    public void release() {
        stop();
        recycleCachedFrames();
        // Recycle non-cached frame if exists
        if (previousNonCachedFrame != null && !previousNonCachedFrame.isRecycled()) {
            previousNonCachedFrame.recycle();
            previousNonCachedFrame = null;
        }
        // Shutdown loading thread
        if (loadingThread != null) {
            loadingThread.quitSafely();
            loadingThread = null;
            loadingHandler = null;
        }
    }
}
