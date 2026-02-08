package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composites emoji overlays onto eyeless body animation frames.
 * 
 * The emoji animation consists of two sequential phases:
 * 1. Before-blink ({@link #FOLDER_BEFORE_BLINK}): Avatar closes eyes — emoji begins appearing
 * 2. After-blink ({@link #FOLDER_AFTER_BLINK}): Emoji disappears — avatar opens eyes
 * 
 * Between configurable start/end frames, the selected emoji is drawn on top
 * of the base animation frames (which show the body without eyes).
 * 
 * Combined frame flow:
 * <pre>
 * [before_blink: 0..N-1] → [after_blink: N..N+M-1]
 *         ^                            ^
 *    emojiStartFrame            emojiEndFrame
 *         |__________________________|
 *              emoji visible zone
 * </pre>
 * 
 * A cooldown mechanism prevents rapid re-triggering after the animation completes,
 * ensuring a smooth visual transition back to normal eyes.
 */
public class EmojiCompositor {

    private static final String TAG = "EmojiCompositor";

    // =========================================
    // Asset Folders
    // =========================================

    /** Folder with frames showing eyes closing before emoji appears */
    public static final String FOLDER_BEFORE_BLINK = "notification_emoji_before_blink";

    /** Folder with frames showing eyes opening after emoji disappears */
    public static final String FOLDER_AFTER_BLINK = "notification_emoji_after_blink";

    // =========================================
    // Default Configuration
    // =========================================

    /**
     * Default frame in the before-blink sequence where emoji first becomes visible.
     * At this point the avatar's eyes should be fully closed.
     */
    private static final int DEFAULT_EMOJI_START_FRAME = 25;

    /**
     * Default number of frames before the end of the after-blink sequence
     * where the emoji stops being visible (to allow the eye-open transition).
     */
    private static final int DEFAULT_EMOJI_END_OFFSET = 25;

    // Cooldown constraints (ms)
    private static final long DEFAULT_EMOJI_COOLDOWN_MS = 1500;
    private static final long MIN_EMOJI_COOLDOWN_MS = 500;
    private static final long MAX_EMOJI_COOLDOWN_MS = 5000;

    // =========================================
    // State
    // =========================================

    private final Context context;

    // Frame data for both phases
    private final List<String> beforeBlinkFrames = new ArrayList<>();
    private final List<String> afterBlinkFrames = new ArrayList<>();
    private int totalFrameCount = 0;

    // Emoji overlay configuration
    private int emojiStartFrame = DEFAULT_EMOJI_START_FRAME;
    private int emojiEndFrame;  // Calculated from totalFrameCount − offset
    private volatile Bitmap emojiBitmap;
    private final Paint emojiPaint;
    private RectF emojiRect;    // Custom position (null = auto-center)

    // Composition buffer (reused across frames to avoid allocation)
    private Bitmap compositionBitmap;
    private Canvas compositionCanvas;

    // Cooldown tracking
    private volatile long cooldownDurationMs = DEFAULT_EMOJI_COOLDOWN_MS;
    private volatile long lastAnimationEndTime = 0;

    // Runtime state
    private volatile boolean isInitialized = false;
    private volatile boolean isActive = false;

    // =========================================
    // Construction & Initialization
    // =========================================

    public EmojiCompositor(Context context) {
        this.context = context.getApplicationContext();
        this.emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }

    /**
     * Initialize by loading frame lists from both asset folders.
     * Must be called once before any rendering.
     */
    public void initialize() {
        loadFrameList(FOLDER_BEFORE_BLINK, beforeBlinkFrames);
        loadFrameList(FOLDER_AFTER_BLINK, afterBlinkFrames);

        totalFrameCount = beforeBlinkFrames.size() + afterBlinkFrames.size();

        // Calculate default end frame (offset from the end of the combined sequence)
        emojiEndFrame = Math.max(0, totalFrameCount - DEFAULT_EMOJI_END_OFFSET);

        isInitialized = totalFrameCount > 0;
        Log.i(TAG, "Initialized: " + beforeBlinkFrames.size() + " before-blink + "
                + afterBlinkFrames.size() + " after-blink = " + totalFrameCount + " total frames. "
                + "Emoji visible: frames " + emojiStartFrame + "–" + emojiEndFrame);
    }

    private void loadFrameList(String folder, List<String> target) {
        target.clear();
        try {
            String[] files = context.getAssets().list(folder);
            if (files != null) {
                for (String file : files) {
                    String lower = file.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp")) {
                        target.add(file);
                    }
                }
                Collections.sort(target);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading frame list from " + folder, e);
        }
    }

    // =========================================
    // Configuration
    // =========================================

    /**
     * Set the emoji bitmap to overlay on the animation frames.
     * @param emoji The emoji bitmap, or null to clear
     */
    public void setEmojiBitmap(Bitmap emoji) {
        this.emojiBitmap = emoji;
    }

    /**
     * Set the frame range within the combined sequence where the emoji is visible.
     * 
     * @param startFrame First frame where emoji appears (0-indexed, combined sequence)
     * @param endFrame   Last frame where emoji appears (0-indexed, combined sequence)
     */
    public void setEmojiFrameRange(int startFrame, int endFrame) {
        this.emojiStartFrame = Math.max(0, startFrame);
        this.emojiEndFrame = Math.min(endFrame, Math.max(0, totalFrameCount - 1));
        Log.d(TAG, "Emoji frame range set: " + this.emojiStartFrame + "–" + this.emojiEndFrame);
    }

    /**
     * Set a custom position and size for the emoji overlay.
     * @param rect Rectangle defining the drawing area, or null for auto-center
     */
    public void setEmojiPosition(RectF rect) {
        this.emojiRect = rect;
    }

    /**
     * Set the cooldown duration enforced after the animation completes.
     * Prevents rapid re-triggering for a smooth transition back to eyes.
     * 
     * @param cooldownMs Duration in milliseconds (clamped to 500–5000)
     */
    public void setCooldownDuration(long cooldownMs) {
        this.cooldownDurationMs = Math.max(MIN_EMOJI_COOLDOWN_MS,
                Math.min(MAX_EMOJI_COOLDOWN_MS, cooldownMs));
    }

    /** Get the configured cooldown duration in milliseconds. */
    public long getCooldownDuration() {
        return cooldownDurationMs;
    }

    // =========================================
    // Cooldown Management
    // =========================================

    /**
     * Check if the compositor is still in cooldown from a previous animation.
     */
    public boolean isInCooldown() {
        return System.currentTimeMillis() - lastAnimationEndTime < cooldownDurationMs;
    }

    /**
     * Get the remaining cooldown time in milliseconds.
     * @return Remaining ms, or 0 if cooldown has expired
     */
    public long getRemainingCooldownMs() {
        long elapsed = System.currentTimeMillis() - lastAnimationEndTime;
        return Math.max(0, cooldownDurationMs - elapsed);
    }

    /**
     * Mark the current emoji animation as complete, starting the cooldown period.
     * After cooldown expires, a new emoji animation can be triggered.
     */
    public void markAnimationComplete() {
        lastAnimationEndTime = System.currentTimeMillis();
        isActive = false;
        Log.d(TAG, "Emoji animation complete — cooldown: " + cooldownDurationMs + "ms");
    }

    // =========================================
    // Animation Lifecycle
    // =========================================

    /**
     * Attempt to start a new emoji animation.
     * 
     * @return true if started successfully, false if blocked by cooldown
     *         or no emoji is set
     */
    public boolean startAnimation() {
        if (isInCooldown()) {
            Log.d(TAG, "Emoji animation blocked — cooldown remaining: "
                    + getRemainingCooldownMs() + "ms");
            return false;
        }
        if (emojiBitmap == null || emojiBitmap.isRecycled()) {
            Log.w(TAG, "Emoji animation blocked — no emoji bitmap set");
            return false;
        }
        isActive = true;
        Log.i(TAG, "Emoji animation started");
        return true;
    }

    /** Check if the compositor is currently running an animation. */
    public boolean isActive() {
        return isActive;
    }

    /** Check if the compositor is initialized and ready for use. */
    public boolean isInitialized() {
        return isInitialized;
    }

    // =========================================
    // Frame Access
    // =========================================

    /** Total frame count across both phases of the animation. */
    public int getTotalFrameCount() {
        return totalFrameCount;
    }

    /** Total duration of the combined animation in milliseconds. */
    public long getTotalDurationMs() {
        return totalFrameCount * AnimationConfig.FRAME_DELAY_30FPS;
    }

    /** Frame count of the before-blink phase. */
    public int getBeforeBlinkFrameCount() {
        return beforeBlinkFrames.size();
    }

    /** Frame count of the after-blink phase. */
    public int getAfterBlinkFrameCount() {
        return afterBlinkFrames.size();
    }

    /**
     * Get the asset path for a given frame index in the combined sequence.
     * 
     * @param frameIndex 0-based index (0..totalFrameCount−1)
     * @return Full asset path (e.g. "notification_emoji_before_blink/notification_4010.png"),
     *         or null if index is out of range
     */
    public String getFramePath(int frameIndex) {
        if (!isInitialized || frameIndex < 0 || frameIndex >= totalFrameCount) {
            return null;
        }

        int beforeCount = beforeBlinkFrames.size();
        if (frameIndex < beforeCount) {
            return FOLDER_BEFORE_BLINK + "/" + beforeBlinkFrames.get(frameIndex);
        } else {
            int afterIndex = frameIndex - beforeCount;
            if (afterIndex < afterBlinkFrames.size()) {
                return FOLDER_AFTER_BLINK + "/" + afterBlinkFrames.get(afterIndex);
            }
        }
        return null;
    }

    // =========================================
    // Emoji Visibility
    // =========================================

    /**
     * Determine whether the emoji should be visible at the given combined frame index.
     * 
     * @param frameIndex 0-based index in the combined sequence
     * @return true if the emoji overlay should be drawn for this frame
     */
    public boolean isEmojiVisibleAtFrame(int frameIndex) {
        return emojiBitmap != null
                && !emojiBitmap.isRecycled()
                && frameIndex >= emojiStartFrame
                && frameIndex <= emojiEndFrame;
    }

    // =========================================
    // Frame Composition
    // =========================================

    /**
     * Compose a final frame by drawing the base body frame and (optionally) the
     * emoji overlay on top.
     * 
     * The returned bitmap is an internal buffer and must <b>not</b> be recycled
     * by the caller — it is reused across frames.
     * 
     * @param baseFrame The base animation frame (body without eyes)
     * @param frameIndex Current frame index in the combined sequence
     * @return The composed bitmap, or null if the base frame is invalid
     */
    public Bitmap composeFrame(Bitmap baseFrame, int frameIndex) {
        if (baseFrame == null || baseFrame.isRecycled()) return null;

        // Ensure the composition buffer matches the base frame dimensions
        ensureCompositionBuffer(baseFrame.getWidth(), baseFrame.getHeight());

        // Draw base frame
        compositionCanvas.drawBitmap(baseFrame, 0, 0, null);

        // Draw emoji overlay if this frame is in the visible zone
        if (isEmojiVisibleAtFrame(frameIndex)) {
            drawEmoji(compositionCanvas, baseFrame.getWidth(), baseFrame.getHeight());
        }

        return compositionBitmap;
    }

    private void ensureCompositionBuffer(int width, int height) {
        if (compositionBitmap == null
                || compositionBitmap.getWidth() != width
                || compositionBitmap.getHeight() != height) {
            if (compositionBitmap != null && !compositionBitmap.isRecycled()) {
                compositionBitmap.recycle();
            }
            compositionBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            compositionCanvas = new Canvas(compositionBitmap);
        }
    }

    private void drawEmoji(Canvas canvas, int canvasWidth, int canvasHeight) {
        Bitmap emoji = emojiBitmap;
        if (emoji == null || emoji.isRecycled()) return;

        if (emojiRect != null) {
            // Custom position supplied
            canvas.drawBitmap(emoji, null, emojiRect, emojiPaint);
        } else {
            // Auto-center: 40% of canvas width, slightly above center (eye region)
            float emojiSize = canvasWidth * 0.4f;
            float left = (canvasWidth - emojiSize) / 2f;
            float top = (canvasHeight - emojiSize) / 2f - (canvasHeight * 0.05f);
            canvas.drawBitmap(emoji, null,
                    new RectF(left, top, left + emojiSize, top + emojiSize), emojiPaint);
        }
    }

    // =========================================
    // Cleanup
    // =========================================

    /**
     * Release all resources held by this compositor.
     * The emoji bitmap is <i>not</i> recycled here — it is managed by {@link EmojiRegistry}.
     */
    public void release() {
        if (compositionBitmap != null && !compositionBitmap.isRecycled()) {
            compositionBitmap.recycle();
            compositionBitmap = null;
        }
        compositionCanvas = null;
        emojiBitmap = null;
        isActive = false;
        isInitialized = false;
    }
}
