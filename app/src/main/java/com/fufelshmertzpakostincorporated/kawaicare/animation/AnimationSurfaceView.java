package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LruCache;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-performance animation renderer using SurfaceView.
 * 
 * This is an alternative to AnimationRenderer that provides:
 * - Dedicated render thread (no UI thread blocking)
 * - Direct control over drawing via Canvas
 * - Better frame timing control with nanosecond precision
 * - Immunity to layout/orientation changes (the SurfaceView handles its own drawing)
 * - Full alpha/transparency support for PNG sequences
 * 
 * Use this when:
 * - You need guaranteed smooth 30 FPS
 * - Orientation changes are causing issues with ImageView
 * - You want complete control over the render loop
 * 
 * Keep using AnimationRenderer when:
 * - You need simpler integration
 * - Memory is very constrained
 * - You need to overlay other Views on top easily
 */
public class AnimationSurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final String TAG = "AnimationSurfaceView";
    private static final long TARGET_FRAME_TIME_NS = 33_333_333L; // ~30 FPS in nanoseconds
    private static final int CACHE_SIZE_PERCENTAGE = 15; // % of available memory

    // Rendering
    private Thread renderThread;
    private volatile boolean isRunning = false;
    private final Paint bitmapPaint;
    private final Paint clearPaint;
    private final Matrix drawMatrix = new Matrix();

    // Animation state
    private volatile String currentFolderPath;
    private final List<String> frameFiles = Collections.synchronizedList(new ArrayList<>());
    private volatile int currentFrameIndex = 0;
    private volatile AnimationRenderer.AnimState currentState = AnimationRenderer.AnimState.IDLE;

    // Frame cache using LRU
    private LruCache<String, Bitmap> frameCache;

    // Background color (transparent by default for alpha support)
    private int backgroundColor = Color.TRANSPARENT;

    // Bitmap currently being displayed (prevent recycling)
    private volatile Bitmap currentDisplayedBitmap;
    private final Object displayLock = new Object();

    // Listeners
    private StateChangeListener stateChangeListener;
    private AnimationDurationCallback durationCallback;

    /**
     * Listener for animation cycle events.
     */
    public interface StateChangeListener {
        /**
         * Called when an animation cycle completes (loops back to frame 0).
         */
        void onAnimationCycleComplete(AnimationRenderer.AnimState state);
    }

    /**
     * Callback for duration calculations.
     */
    public interface AnimationDurationCallback {
        void onAnimationDurationCalculated(String folderPath, long durationMs, int frameCount);
    }

    public AnimationSurfaceView(Context context) {
        super(context);
        bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        clearPaint = new Paint();
        init();
    }

    public AnimationSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        clearPaint = new Paint();
        init();
    }

    public AnimationSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        clearPaint = new Paint();
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        
        // Enable transparency support
        setZOrderOnTop(false);
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);

        // Initialize LRU cache based on available memory
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(maxMemory * CACHE_SIZE_PERCENTAGE / 100, 4 * 1024); // Min 4MB
        
        frameCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                        Bitmap oldValue, Bitmap newValue) {
                if (evicted && oldValue != null) {
                    synchronized (displayLock) {
                        if (oldValue != currentDisplayedBitmap && !oldValue.isRecycled()) {
                            oldValue.recycle();
                        }
                    }
                }
            }
        };

        Log.d(TAG, "AnimationSurfaceView initialized with " + cacheSize + "KB cache");
    }

    /**
     * Set the background color for the animation area.
     * Default is transparent to support alpha in PNG sequences.
     */
    public void setAnimationBackgroundColor(int color) {
        this.backgroundColor = color;
    }

    /**
     * Set animation from an assets folder.
     * 
     * @param assetsFolderPath Path in assets folder (e.g., "blinks")
     */
    public void setAnimation(String assetsFolderPath) {
        if (assetsFolderPath == null || assetsFolderPath.equals(currentFolderPath)) {
            return;
        }

        currentFolderPath = assetsFolderPath;
        currentFrameIndex = 0;
        loadFramesList(assetsFolderPath);

        // Notify duration callback
        if (durationCallback != null && !frameFiles.isEmpty()) {
            long duration = frameFiles.size() * (TARGET_FRAME_TIME_NS / 1_000_000);
            durationCallback.onAnimationDurationCalculated(assetsFolderPath, duration, frameFiles.size());
        }

        // Preload first few frames
        preloadFrames(0, 3);
    }

    private void loadFramesList(String folderPath) {
        frameFiles.clear();
        try {
            String[] files = getContext().getAssets().list(folderPath);
            if (files != null) {
                List<String> imageFiles = new ArrayList<>();
                for (String file : files) {
                    String lower = file.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || 
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        imageFiles.add(file);
                    }
                }
                Collections.sort(imageFiles);
                frameFiles.addAll(imageFiles);
                Log.d(TAG, "Loaded " + frameFiles.size() + " frames from " + folderPath);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading frames from " + folderPath, e);
        }
    }

    private void preloadFrames(int startIndex, int count) {
        if (currentFolderPath == null || frameFiles.isEmpty()) return;

        int fileCount = frameFiles.size();
        for (int i = 0; i < count; i++) {
            int index = (startIndex + i) % fileCount;
            String path = currentFolderPath + "/" + frameFiles.get(index);
            if (frameCache.get(path) == null) {
                Bitmap frame = loadBitmap(path);
                if (frame != null) {
                    frameCache.put(path, frame);
                }
            }
        }
    }

    /**
     * Set the current animation state.
     */
    public void setState(AnimationRenderer.AnimState state) {
        if (currentState != state) {
            currentState = state;
            currentFrameIndex = 0;
        }
    }

    /**
     * Get the current animation state.
     */
    public AnimationRenderer.AnimState getState() {
        return currentState;
    }

    /**
     * Calculate duration for a folder without loading it.
     */
    public long calculateFolderDuration(String assetsFolderPath) {
        try {
            String[] files = getContext().getAssets().list(assetsFolderPath);
            if (files != null) {
                int imageCount = 0;
                for (String file : files) {
                    String lower = file.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || 
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                        imageCount++;
                    }
                }
                return imageCount * (TARGET_FRAME_TIME_NS / 1_000_000);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error calculating duration", e);
        }
        return 0;
    }

    // --- SurfaceHolder.Callback Implementation ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
        isRunning = true;
        renderThread = new Thread(this, "AnimationRenderThread");
        renderThread.setPriority(Thread.MAX_PRIORITY - 1);
        renderThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height + " format=" + format);
        // Recalculate draw matrix will happen automatically on next frame
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed");
        isRunning = false;
        
        // Wait for render thread to finish
        boolean retry = true;
        while (retry) {
            try {
                renderThread.join(1000);
                retry = false;
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted waiting for render thread", e);
            }
        }
    }

    // --- Render Loop ---

    @Override
    public void run() {
        Log.d(TAG, "Render thread started");
        long lastFrameTime = System.nanoTime();

        while (isRunning) {
            long currentTime = System.nanoTime();
            long elapsed = currentTime - lastFrameTime;

            if (elapsed >= TARGET_FRAME_TIME_NS) {
                lastFrameTime = currentTime;

                Canvas canvas = null;
                try {
                    canvas = getHolder().lockCanvas();
                    if (canvas != null) {
                        synchronized (getHolder()) {
                            drawFrame(canvas);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error drawing frame", e);
                } finally {
                    if (canvas != null) {
                        try {
                            getHolder().unlockCanvasAndPost(canvas);
                        } catch (Exception e) {
                            Log.e(TAG, "Error posting canvas", e);
                        }
                    }
                }

                // Advance to next frame
                advanceFrame();
            } else {
                // Sleep to avoid busy-waiting and save battery
                long sleepTime = (TARGET_FRAME_TIME_NS - elapsed) / 1_000_000;
                if (sleepTime > 1) {
                    try {
                        Thread.sleep(sleepTime - 1);
                    } catch (InterruptedException e) {
                        if (!isRunning) break;
                    }
                }
            }
        }

        Log.d(TAG, "Render thread stopped");
    }

    private void drawFrame(Canvas canvas) {
        // Clear with background color (supports transparency)
        canvas.drawColor(backgroundColor, PorterDuff.Mode.SRC);

        if (frameFiles.isEmpty() || currentFolderPath == null) {
            return;
        }

        int frameIndex = currentFrameIndex % frameFiles.size();
        String framePath = currentFolderPath + "/" + frameFiles.get(frameIndex);

        Bitmap frame = getFrame(framePath);
        if (frame != null && !frame.isRecycled()) {
            // Calculate scaling to fit the surface while maintaining aspect ratio
            float canvasWidth = canvas.getWidth();
            float canvasHeight = canvas.getHeight();
            float bitmapWidth = frame.getWidth();
            float bitmapHeight = frame.getHeight();

            float scaleX = canvasWidth / bitmapWidth;
            float scaleY = canvasHeight / bitmapHeight;
            float scale = Math.min(scaleX, scaleY);

            // Center the bitmap
            float dx = (canvasWidth - bitmapWidth * scale) / 2f;
            float dy = (canvasHeight - bitmapHeight * scale) / 2f;

            drawMatrix.reset();
            drawMatrix.postScale(scale, scale);
            drawMatrix.postTranslate(dx, dy);

            // Draw with alpha preservation
            synchronized (displayLock) {
                currentDisplayedBitmap = frame;
            }
            canvas.drawBitmap(frame, drawMatrix, bitmapPaint);
        }
    }

    private void advanceFrame() {
        if (frameFiles.isEmpty()) return;

        int nextIndex = currentFrameIndex + 1;
        if (nextIndex >= frameFiles.size()) {
            nextIndex = 0;
            // Notify cycle complete
            if (stateChangeListener != null) {
                // Post to main thread for safety
                post(() -> stateChangeListener.onAnimationCycleComplete(currentState));
            }
        }
        currentFrameIndex = nextIndex;

        // Preload next frames
        preloadFrames(nextIndex + 1, 2);
    }

    private Bitmap getFrame(String path) {
        Bitmap cached = frameCache.get(path);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        Bitmap loaded = loadBitmap(path);
        if (loaded != null) {
            frameCache.put(path, loaded);
        }
        return loaded;
    }

    private Bitmap loadBitmap(String path) {
        try (InputStream is = getContext().getAssets().open(path)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // ARGB_8888 preserves full alpha channel for transparency
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inScaled = false;
            opts.inMutable = false;
            
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (bitmap != null) {
                bitmap.setHasAlpha(true);
            }
            return bitmap;
        } catch (IOException e) {
            Log.w(TAG, "Failed to load: " + path);
            return null;
        }
    }

    // --- Public API ---

    /**
     * Set listener for animation cycle events.
     */
    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Set callback for duration calculations.
     */
    public void setDurationCallback(AnimationDurationCallback callback) {
        this.durationCallback = callback;
    }

    /**
     * Check if animation is currently running.
     */
    public boolean isAnimating() {
        return isRunning;
    }

    /**
     * Get current frame index.
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex;
    }

    /**
     * Get total frame count.
     */
    public int getFrameCount() {
        return frameFiles.size();
    }

    /**
     * Release all resources. Call when done with the view.
     */
    public void release() {
        isRunning = false;
        
        synchronized (displayLock) {
            currentDisplayedBitmap = null;
        }
        
        frameCache.evictAll();
        frameFiles.clear();
        
        Log.d(TAG, "AnimationSurfaceView released");
    }
}
