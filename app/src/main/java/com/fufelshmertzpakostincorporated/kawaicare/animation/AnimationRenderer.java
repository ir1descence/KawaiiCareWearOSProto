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

    // Optimization: Target ~30 FPS for smooth animation on Wear OS
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
    
    // Track currently displayed bitmap to prevent recycling while in use
    private volatile Bitmap currentlyDisplayedBitmap;
    
    // Lock for bitmap operations
    private final Object bitmapLock = new Object();
    
    // Animation duration callback for external animation support
    private AnimationDurationCallback durationCallback;
    
    /**
     * Callback interface for notifying when animation duration is calculated.
     */
    public interface AnimationDurationCallback {
        void onAnimationDurationCalculated(String folderPath, long durationMs, int frameCount);
    }

    public AnimationRenderer(Context context, ImageView targetView) {
        this.context = context;
        this.targetView = targetView;
        
        // Initialize background loading thread
        loadingThread = new HandlerThread("AnimationLoader", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        loadingThread.start();
        loadingHandler = new Handler(loadingThread.getLooper());
    }
    
    /**
     * Set callback for animation duration notifications.
     */
    public void setDurationCallback(AnimationDurationCallback callback) {
        this.durationCallback = callback;
    }
    
    /**
     * Get the frame delay in milliseconds.
     */
    public static long getFrameDelayMs() {
        return FRAME_DELAY_MS;
    }
    
    /**
     * Get the current frame count for the loaded animation.
     * @return Number of frames, or 0 if no animation loaded
     */
    public int getFrameCount() {
        return frameFiles.size();
    }
    
    /**
     * Calculate the duration of one full animation cycle in milliseconds.
     * @return Duration in ms, or 0 if no animation loaded
     */
    public long getAnimationDurationMs() {
        return frameFiles.size() * FRAME_DELAY_MS;
    }
    
    /**
     * Calculate the duration for a specific asset folder without loading it.
     * @param assetsFolderPath Path to the assets folder
     * @return Duration in ms, or 0 if folder not found or empty
     */
    public long calculateFolderDuration(String assetsFolderPath) {
        try {
            String[] files = context.getAssets().list(assetsFolderPath);
            if (files != null) {
                int imageCount = 0;
                for (String file : files) {
                    String lower = file.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                        imageCount++;
                    }
                }
                return imageCount * FRAME_DELAY_MS;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
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
        // Skip if same folder already loaded and cache is populated
        if (assetsFolderPath.equals(currentFolderPath) && !cachedFrames.isEmpty()) {
            return;
        }
        
        // Stop animation loop briefly to prevent race conditions during transition
        boolean wasRunning = isRunning;
        if (wasRunning) {
            handler.removeCallbacks(loop);
        }
        
        synchronized (bitmapLock) {
            // Clear the ImageView SYNCHRONOUSLY if on main thread before recycling bitmaps
            // This is critical - async posting can cause the ImageView to draw a recycled bitmap
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (targetView != null) {
                    targetView.setImageBitmap(null);
                }
            } else {
                handler.post(() -> {
                    if (targetView != null) {
                        targetView.setImageBitmap(null);
                    }
                });
            }
            currentlyDisplayedBitmap = null;
            
            // Recycle old non-cached frame
            if (previousNonCachedFrame != null && !previousNonCachedFrame.isRecycled()) {
                previousNonCachedFrame.recycle();
                previousNonCachedFrame = null;
            }
            
            // Recycle old cached frames
            recycleCachedFramesInternal();
        }
        
        this.mode = AnimationMode.FOLDER;
        this.currentFolderPath = assetsFolderPath;
        this.enableFrameCache = cacheFrames;
        this.currentFrameIndex = 0;
        
        // Clear legacy mode
        face = null;
        compositor = null;
        
        loadFramesList();
        
        // Notify callback of calculated duration
        if (durationCallback != null && !frameFiles.isEmpty()) {
            long duration = getAnimationDurationMs();
            durationCallback.onAnimationDurationCalculated(assetsFolderPath, duration, frameFiles.size());
        }
        
        if (enableFrameCache) {
            preloadFramesAsync();
        }
        
        // Resume animation loop if it was running
        if (wasRunning) {
            handler.post(loop);
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
                synchronized (bitmapLock) {
                    cachedFrames.clear();
                    cachedFrames.addAll(loadedFrames);
                }
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

    /**
     * Recycle cached frames - clears ImageView first to prevent drawing recycled bitmaps.
     * Thread-safe version that can be called from any thread.
     */
    private void recycleCachedFrames() {
        synchronized (bitmapLock) {
            // Clear the ImageView SYNCHRONOUSLY if on main thread to prevent drawing recycled bitmaps
            // This is critical - async posting can cause the ImageView to draw a recycled bitmap
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (targetView != null) {
                    targetView.setImageBitmap(null);
                }
            } else {
                // Not on main thread - post and wait
                handler.post(() -> {
                    if (targetView != null) {
                        targetView.setImageBitmap(null);
                    }
                });
            }
            
            recycleCachedFramesInternal();
        }
    }
    
    /**
     * Internal method to recycle cached frames.
     * MUST be called while holding bitmapLock.
     */
    private void recycleCachedFramesInternal() {
        // Mark currently displayed bitmap as no longer in use
        currentlyDisplayedBitmap = null;
        
        // Now safe to recycle
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
        
        synchronized (bitmapLock) {
            // Try to get from cache first
            if (enableFrameCache && currentFrameIndex < cachedFrames.size()) {
                try {
                    frame = cachedFrames.get(currentFrameIndex);
                    // Verify the frame is still valid
                    if (frame != null && frame.isRecycled()) {
                        frame = null;
                    }
                } catch (IndexOutOfBoundsException e) {
                    // Cache might be clearing, will load on-demand below
                    frame = null;
                }
            }
        }
        
        // If not cached or cache miss, load on-demand (but this should be rare with async preload)
        if (frame == null) {
            final String folderPath = currentFolderPath;
            if (folderPath == null || currentFrameIndex >= frameFiles.size()) {
                return; // Folder changed during iteration
            }
            
            String framePath = folderPath + "/" + frameFiles.get(currentFrameIndex);
            frame = loadFrameFromAssets(framePath);
            
            if (frame == null) {
                currentFrameIndex++;
                return;
            }
            
            // Store old frame reference before updating
            Bitmap oldFrame = previousNonCachedFrame;
            previousNonCachedFrame = frame;
            
            // Update the currently displayed bitmap reference
            currentlyDisplayedBitmap = frame;
            
            // Set the bitmap to ImageView
            targetView.setImageBitmap(frame);
            
            // Only recycle the old frame AFTER setting new bitmap to ImageView
            // This prevents "Canvas: trying to use a recycled bitmap" crash
            if (oldFrame != null && !oldFrame.isRecycled() && oldFrame != frame && oldFrame != currentlyDisplayedBitmap) {
                oldFrame.recycle();
            }
            
            currentFrameIndex++;
            return;
        }

        // Using cached frame - update tracking and display
        synchronized (bitmapLock) {
            // Double-check frame is still valid (could have been recycled during folder transition)
            if (frame != null && !frame.isRecycled()) {
                currentlyDisplayedBitmap = frame;
                // Final safety check before setting bitmap to prevent recycled bitmap crash
                if (!frame.isRecycled()) {
                    targetView.setImageBitmap(frame);
                }
            }
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
     * Cleanup method to release resources.
     * Should be called in onDestroy to prevent memory leaks.
     */
    public void release() {
        stop();
        
        synchronized (bitmapLock) {
            // Clear ImageView first
            handler.post(() -> {
                if (targetView != null) {
                    targetView.setImageBitmap(null);
                }
            });
            
            currentlyDisplayedBitmap = null;
            
            // Recycle cached frames
            recycleCachedFramesInternal();
            
            // Recycle non-cached frame if exists
            if (previousNonCachedFrame != null && !previousNonCachedFrame.isRecycled()) {
                previousNonCachedFrame.recycle();
                previousNonCachedFrame = null;
            }
        }
        
        // Shutdown loading thread
        if (loadingThread != null) {
            loadingThread.quitSafely();
            loadingThread = null;
            loadingHandler = null;
        }
    }
}
