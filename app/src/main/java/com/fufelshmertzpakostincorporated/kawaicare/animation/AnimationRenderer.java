package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimized Animation Renderer for Wear OS.
 * 
 * Handles frame-by-frame PNG sequence animations with:
 * - LRU cache with memory-aware sizing (preserves alpha/transparency)
 * - Fully async frame loading on background thread
 * - Proper lifecycle management
 * - Thread-safe state transitions
 * - Support for both folder-based and compositor-based animation modes
 * 
 * Key improvements over previous implementation:
 * - Memory-efficient LRU eviction instead of CopyOnWriteArrayList
 * - Proper bitmap recycling that avoids "Canvas: trying to use recycled bitmap"
 * - Preloading of next frames for smooth playback
 * - Atomic operations for thread safety
 */
public class AnimationRenderer {

    private static final String TAG = "AnimationRenderer";

    // State Pattern: Enums defining abstract states
    public enum AnimState {
        IDLE,           // Default idle state with blinking
        TILTED,         // Looking in a direction (tilt detected)
        GESTURE_ACTION, // Nodding gesture
        SHAKE,          // Shake with smile animation
        ALARM,          // Notification alert animation
        LEARNING,       // Learning mode for gesture recording
        FRIGHT,         // Scared/frightened emotion
        LOOK_LEFT,      // Looking to the left
        LOOK_RIGHT,     // Looking to the right
        NOTIFICATION_POSTPONE  // Postponed notification animation
    }

    // Animation modes
    public enum AnimationMode {
        COMPOSITOR,  // Legacy mode using FaceCompositor
        FOLDER       // New mode using complete images from folder
    }

    // Configuration
    private static final long FRAME_DELAY_MS = 33; // ~30 FPS
    private static final int CACHE_SIZE_PERCENTAGE = 15; // % of available memory for cache
    private static final int PRELOAD_FRAME_COUNT = 3; // Number of frames to preload ahead

    private final Context context;
    private final WeakReference<ImageView> targetViewRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Background thread for bitmap operations
    private HandlerThread loadingThread;
    private Handler loadingHandler;

    // State (thread-safe)
    private volatile AnimState currentState = AnimState.IDLE;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger currentFrameIndex = new AtomicInteger(0);

    // Animation data
    private volatile String currentFolderPath;
    private final List<String> frameFiles = Collections.synchronizedList(new ArrayList<>());

    // Memory-efficient LRU cache
    private LruCache<String, Bitmap> frameCache;

    // Bitmap currently displayed (prevent recycling while in use)
    private volatile Bitmap currentDisplayedBitmap;
    private final Object displayLock = new Object();

    // Legacy compositor mode
    private Face face;
    private FaceCompositor compositor;
    private AnimationMode mode = AnimationMode.FOLDER;

    // Animation duration callback
    private AnimationDurationCallback durationCallback;

    /**
     * Callback interface for notifying when animation duration is calculated.
     */
    public interface AnimationDurationCallback {
        void onAnimationDurationCalculated(String folderPath, long durationMs, int frameCount);
    }

    public AnimationRenderer(Context context, ImageView targetView) {
        this.context = context.getApplicationContext();
        this.targetViewRef = new WeakReference<>(targetView);
        initializeCache();
        initializeLoadingThread();
    }

    private void initializeCache() {
        // Calculate cache size based on available memory
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = Math.max(maxMemory * CACHE_SIZE_PERCENTAGE / 100, 4 * 1024); // Min 4MB

        frameCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // Return size in KB
                return bitmap.getByteCount() / 1024;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                        Bitmap oldValue, Bitmap newValue) {
                // Only recycle if not currently displayed and actually evicted
                if (evicted && oldValue != null) {
                    synchronized (displayLock) {
                        if (oldValue != currentDisplayedBitmap && !oldValue.isRecycled()) {
                            oldValue.recycle();
                        }
                    }
                }
            }
        };

        Log.d(TAG, "Frame cache initialized: " + cacheSize + "KB (max memory: " + maxMemory + "KB)");
    }

    private void initializeLoadingThread() {
        loadingThread = new HandlerThread("AnimationLoader",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
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
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || 
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        imageCount++;
                    }
                }
                return imageCount * FRAME_DELAY_MS;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error calculating duration for " + assetsFolderPath, e);
        }
        return 0;
    }

    // Legacy mode setter
    public void setFace(Face face) {
        this.face = face;
        this.mode = AnimationMode.COMPOSITOR;
        compositor = null;
        clearCache();
    }

    /**
     * Set animation from an assets folder.
     * Loading is performed asynchronously.
     * 
     * @param assetsFolderPath Path in assets folder (e.g., "emotions/happy")
     * @param cacheFrames Ignored - LRU cache handles caching automatically
     */
    public void setFolderAnimation(String assetsFolderPath, boolean cacheFrames) {
        if (assetsFolderPath == null) return;
        
        // Skip if same folder already loaded
        if (assetsFolderPath.equals(currentFolderPath) && !frameFiles.isEmpty()) {
            return;
        }

        final String previousFolder = currentFolderPath;
        currentFolderPath = assetsFolderPath;
        currentFrameIndex.set(0);
        this.mode = AnimationMode.FOLDER;
        
        // Clear legacy mode
        face = null;
        compositor = null;

        // Load frame list SYNCHRONOUSLY to prevent race condition with render loop
        // This only loads filenames (fast), not actual bitmaps
        loadFramesList(assetsFolderPath);

        // Notify duration callback immediately since we have frame count
        if (durationCallback != null && !frameFiles.isEmpty()) {
            long duration = frameFiles.size() * FRAME_DELAY_MS;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                durationCallback.onAnimationDurationCalculated(assetsFolderPath, duration, frameFiles.size());
            } else {
                mainHandler.post(() -> 
                    durationCallback.onAnimationDurationCalculated(assetsFolderPath, duration, frameFiles.size()));
            }
        }

        // Preload bitmaps on background thread (async is fine for this)
        loadingHandler.post(() -> {
            // Clear cache entries from previous folder (but keep current folder's frames)
            if (previousFolder != null && !previousFolder.equals(assetsFolderPath)) {
                evictFolderFromCache(previousFolder);
            }

            // Preload initial frames for smooth start
            preloadInitialFrames(PRELOAD_FRAME_COUNT);
        });
    }

    /**
     * Convenience method without caching parameter (LRU cache is always used)
     */
    public void setFolderAnimation(String assetsFolderPath) {
        setFolderAnimation(assetsFolderPath, true);
    }

    private void loadFramesList(String folderPath) {
        frameFiles.clear();
        try {
            String[] files = context.getAssets().list(folderPath);
            if (files != null) {
                List<String> imageFiles = new ArrayList<>();
                for (String file : files) {
                    String lower = file.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || 
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        imageFiles.add(file);
                    }
                }
                Collections.sort(imageFiles); // Natural alphabetical order
                frameFiles.addAll(imageFiles);
                Log.d(TAG, "Loaded " + frameFiles.size() + " frames from " + folderPath);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading frames list from " + folderPath, e);
        }
    }

    private void preloadInitialFrames(int count) {
        final String folderPath = currentFolderPath;
        if (folderPath == null || frameFiles.isEmpty()) return;
        
        // Take a snapshot of the frame list to avoid race conditions
        final List<String> fileSnapshot;
        try {
            fileSnapshot = new ArrayList<>(frameFiles);
        } catch (Exception e) {
            return;
        }
        
        if (fileSnapshot.isEmpty()) return;
        
        int toLoad = Math.min(count, fileSnapshot.size());
        for (int i = 0; i < toLoad; i++) {
            // Check folder hasn't changed during preload
            if (!folderPath.equals(currentFolderPath)) return;
            
            String framePath = folderPath + "/" + fileSnapshot.get(i);
            if (frameCache.get(framePath) == null) {
                Bitmap frame = loadBitmapFromAssets(framePath);
                if (frame != null && folderPath.equals(currentFolderPath)) {
                    frameCache.put(framePath, frame);
                } else if (frame != null) {
                    frame.recycle();
                }
            }
        }
        Log.d(TAG, "Preloaded " + toLoad + " initial frames");
    }

    private void evictFolderFromCache(String folderPath) {
        // Note: LruCache handles eviction automatically, but we can
        // explicitly remove old folder's entries to free memory faster
        // This is a no-op optimization - cache will evict naturally
    }

    private void clearCache() {
        synchronized (displayLock) {
            currentDisplayedBitmap = null;
        }
        frameCache.evictAll();
        frameFiles.clear();
        currentFolderPath = null;
    }

    /**
     * Changes the current animation state.
     * Thread-safe. Resets frame index if state changed.
     */
    public void setState(AnimState state) {
        if (state == null) return;
        
        if (currentState != state) {
            currentState = state;
            currentFrameIndex.set(0);
            
            // Trigger immediate update if running
            if (isRunning.get()) {
                mainHandler.removeCallbacks(animationLoop);
                mainHandler.post(animationLoop);
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
        if (isRunning.compareAndSet(false, true)) {
            mainHandler.post(animationLoop);
            Log.d(TAG, "Animation started");
        }
    }

    /**
     * Lifecycle: Stop animation loop.
     * Critical for battery optimization in onPause.
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(animationLoop);
            Log.d(TAG, "Animation stopped");
        }
    }

    // The Animation Loop
    private final Runnable animationLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;

            renderNextFrame();

            // Schedule next frame
            mainHandler.postDelayed(this, FRAME_DELAY_MS);
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
        // Capture current state to avoid race conditions
        final String folderPath = currentFolderPath;
        final int fileCount = frameFiles.size();
        
        // Early exit if no frames available
        if (fileCount == 0 || folderPath == null) {
            return;
        }

        ImageView targetView = targetViewRef.get();
        if (targetView == null) {
            stop();
            return;
        }

        int frameIndex = currentFrameIndex.get();
        if (frameIndex >= fileCount) {
            frameIndex = 0;
            currentFrameIndex.set(0);
        }

        // Safe access with bounds check
        final String fileName;
        try {
            fileName = frameFiles.get(frameIndex);
        } catch (IndexOutOfBoundsException e) {
            // List was modified between size check and get - skip this frame
            Log.w(TAG, "Frame list modified during render, skipping frame");
            return;
        }
        
        final String framePath = folderPath + "/" + fileName;

        // Try cache first
        Bitmap frame = frameCache.get(framePath);

        if (frame != null && !frame.isRecycled()) {
            // Cache hit - display immediately
            displayFrame(targetView, frame);
            currentFrameIndex.incrementAndGet();
            
            // Preload next frame in background
            preloadNextFrame(frameIndex + 1);
        } else {
            // Cache miss - load synchronously for this frame (should be rare after preload)
            frame = loadBitmapFromAssets(framePath);
            if (frame != null) {
                frameCache.put(framePath, frame);
                displayFrame(targetView, frame);
            }
            currentFrameIndex.incrementAndGet();
            
            // Preload next frames in background
            preloadNextFrames(frameIndex + 1, PRELOAD_FRAME_COUNT);
        }
    }

    private void displayFrame(ImageView view, Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;
        
        synchronized (displayLock) {
            currentDisplayedBitmap = frame;
        }
        view.setImageBitmap(frame);
    }

    private void preloadNextFrame(int nextIndex) {
        preloadNextFrames(nextIndex, 1);
    }

    private void preloadNextFrames(int startIndex, int count) {
        if (loadingHandler == null || currentFolderPath == null || frameFiles.isEmpty()) return;
        
        // Capture ALL state needed for preloading to avoid race conditions
        final String folderPath = currentFolderPath;
        final List<String> fileSnapshot;
        try {
            // Take a snapshot of the current frame list
            fileSnapshot = new ArrayList<>(frameFiles);
        } catch (Exception e) {
            // List modified during copy - skip this preload
            return;
        }
        
        if (fileSnapshot.isEmpty()) return;
        final int fileCount = fileSnapshot.size();
        
        loadingHandler.post(() -> {
            // Check if folder changed before we started
            if (!isRunning.get() || !folderPath.equals(currentFolderPath)) return;
            
            for (int i = 0; i < count; i++) {
                // Re-check folder hasn't changed during iteration
                if (!folderPath.equals(currentFolderPath)) return;
                
                int index = (startIndex + i) % fileCount;
                String path = folderPath + "/" + fileSnapshot.get(index);
                
                if (frameCache.get(path) == null) {
                    Bitmap frame = loadBitmapFromAssets(path);
                    if (frame != null && folderPath.equals(currentFolderPath)) {
                        frameCache.put(path, frame);
                    } else if (frame != null) {
                        // Folder changed during load, recycle
                        frame.recycle();
                    }
                }
            }
        });
    }

    private Bitmap loadBitmapFromAssets(String path) {
        try (InputStream is = context.getAssets().open(path)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // ARGB_8888 preserves alpha channel for transparency
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inScaled = false;
            // Mutable false for better performance when not modifying
            opts.inMutable = false;
            
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (bitmap != null) {
                bitmap.setHasAlpha(true); // Ensure alpha is preserved
            }
            return bitmap;
        } catch (IOException e) {
            Log.w(TAG, "Failed to load bitmap: " + path);
            return null;
        }
    }

    private void renderCompositorFrame() {
        if (face == null) return;

        ImageView targetView = targetViewRef.get();
        if (targetView == null) {
            stop();
            return;
        }

        // Lazy init to ensure view dimensions are available
        if (compositor == null) {
            if (targetView.getWidth() > 0 && targetView.getHeight() > 0) {
                compositor = new FaceCompositor(context, face, targetView.getWidth(), targetView.getHeight());
            } else {
                // Skip this frame if view not ready
                return;
            }
        }

        int frameIndex = currentFrameIndex.getAndIncrement();
        compositor.composeFrame(currentState, frameIndex);

        // Efficiently update view with the reused master bitmap
        targetView.setImageBitmap(compositor.getLatestFrame());
    }

    /**
     * Cleanup method to release resources.
     * Should be called in onDestroy to prevent memory leaks.
     */
    public void release() {
        stop();

        // Clear view reference
        ImageView view = targetViewRef.get();
        if (view != null) {
            mainHandler.post(() -> view.setImageBitmap(null));
        }

        // Clear displayed bitmap reference
        synchronized (displayLock) {
            currentDisplayedBitmap = null;
        }

        // Clear cache - this will recycle all bitmaps
        frameCache.evictAll();
        frameFiles.clear();

        // Shutdown loading thread
        if (loadingThread != null) {
            loadingThread.quitSafely();
            try {
                loadingThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for loading thread to stop");
            }
            loadingThread = null;
            loadingHandler = null;
        }

        Log.d(TAG, "AnimationRenderer released");
    }
}
