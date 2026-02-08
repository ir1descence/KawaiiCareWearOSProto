package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Singleton registry for managing emoji selection used by the avatar's
 * eye-replacement animation.
 * 
 * Responsibilities:
 * <ul>
 *   <li>Stores the currently selected emoji string and its rendered bitmap</li>
 *   <li>Renders arbitrary Unicode emoji text to {@link Bitmap} via Android's
 *       built-in emoji font support</li>
 *   <li>Provides a curated list of available/suggested emojis suitable for
 *       a watch-face avatar</li>
 * </ul>
 * 
 * Thread-safe: all mutable state is guarded by {@code synchronized} blocks.
 */
public class EmojiRegistry {

    private static final String TAG = "EmojiRegistry";

    private static volatile EmojiRegistry instance;

    // =========================================
    // Constants
    // =========================================

    /** Default pixel size for the rendered emoji bitmap (square). */
    private static final int DEFAULT_EMOJI_SIZE = 128;

    /**
     * Curated list of emojis that work well as eye-replacements on the avatar.
     * Clients can call {@link #getAvailableEmojis()} to present these to the user.
     */
    private static final List<String> AVAILABLE_EMOJIS = Collections.unmodifiableList(Arrays.asList(
            "❤️", "😍", "🥰", "😊", "🌟", "⭐", "✨", "💖", "💕", "💗",
            "😴", "😎", "🤗", "😇", "🥺", "😂", "🤣", "😭", "😤", "😡",
            "🎵", "🎶", "💤", "💧", "🔥", "❄️", "🌸", "🌺", "🍀", "🌈"
    ));

    // =========================================
    // Mutable State
    // =========================================

    private volatile String currentEmoji;
    private volatile Bitmap currentEmojiBitmap;
    private int emojiRenderSize = DEFAULT_EMOJI_SIZE;

    private volatile EmojiChangeListener listener;

    // =========================================
    // Listener
    // =========================================

    /**
     * Callback interface for observing emoji selection changes.
     */
    public interface EmojiChangeListener {
        /**
         * Called when the selected emoji changes.
         * @param emoji The new emoji string, or null if cleared
         * @param emojiBitmap The rendered bitmap, or null if cleared
         */
        void onEmojiChanged(String emoji, Bitmap emojiBitmap);
    }

    // =========================================
    // Singleton
    // =========================================

    private EmojiRegistry() {}

    public static EmojiRegistry getInstance() {
        if (instance == null) {
            synchronized (EmojiRegistry.class) {
                if (instance == null) {
                    instance = new EmojiRegistry();
                }
            }
        }
        return instance;
    }

    // =========================================
    // Public API
    // =========================================

    /**
     * Set the currently selected emoji.
     * The emoji string is rendered to a bitmap for use by the {@link EmojiCompositor}.
     * 
     * @param emoji Unicode emoji string (e.g. "😍"), or null/empty to clear
     * @return true if the emoji was set (or cleared) successfully
     */
    public synchronized boolean setEmoji(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            clearEmoji();
            return true;
        }

        Bitmap rendered = renderEmojiBitmap(emoji, emojiRenderSize);
        if (rendered == null) {
            Log.w(TAG, "Failed to render emoji: " + emoji);
            return false;
        }

        // Recycle old bitmap
        if (currentEmojiBitmap != null && !currentEmojiBitmap.isRecycled()) {
            currentEmojiBitmap.recycle();
        }

        currentEmoji = emoji;
        currentEmojiBitmap = rendered;

        Log.i(TAG, "Emoji set: " + emoji
                + " (" + rendered.getWidth() + "×" + rendered.getHeight() + ")");

        if (listener != null) {
            listener.onEmojiChanged(emoji, currentEmojiBitmap);
        }
        return true;
    }

    /**
     * Clear the current emoji selection.
     */
    public synchronized void clearEmoji() {
        if (currentEmojiBitmap != null && !currentEmojiBitmap.isRecycled()) {
            currentEmojiBitmap.recycle();
        }
        currentEmoji = null;
        currentEmojiBitmap = null;

        if (listener != null) {
            listener.onEmojiChanged(null, null);
        }
    }

    /** Get the currently selected emoji string, or null if none. */
    public String getCurrentEmoji() {
        return currentEmoji;
    }

    /** Get the current emoji as a pre-rendered bitmap, or null if none. */
    public Bitmap getCurrentEmojiBitmap() {
        return currentEmojiBitmap;
    }

    /** Check if an emoji is currently selected and its bitmap is valid. */
    public boolean hasEmoji() {
        return currentEmoji != null
                && currentEmojiBitmap != null
                && !currentEmojiBitmap.isRecycled();
    }

    /** Get the curated list of available/suggested emojis. */
    public List<String> getAvailableEmojis() {
        return AVAILABLE_EMOJIS;
    }

    /**
     * Set the render size for emoji bitmaps (square).
     * @param size Pixel size, clamped to [32, 512]
     */
    public void setEmojiRenderSize(int size) {
        this.emojiRenderSize = Math.max(32, Math.min(512, size));
    }

    /** Register a listener for emoji selection changes. */
    public void setListener(EmojiChangeListener listener) {
        this.listener = listener;
    }

    // =========================================
    // Bitmap Rendering
    // =========================================

    /**
     * Render a Unicode emoji string to a square {@link Bitmap} using Android's
     * built-in emoji font.
     * 
     * @param emoji The emoji character(s)
     * @param size  Desired bitmap width/height in pixels
     * @return The rendered bitmap, or null on failure
     */
    private Bitmap renderEmojiBitmap(String emoji, int size) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(size * 0.75f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT);

            // Center the glyph vertically
            Paint.FontMetrics fm = paint.getFontMetrics();
            float x = size / 2f;
            float y = size / 2f - (fm.ascent + fm.descent) / 2f;

            canvas.drawText(emoji, x, y, paint);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error rendering emoji bitmap", e);
            return null;
        }
    }

    // =========================================
    // Cleanup
    // =========================================

    /** Release resources. Safe to call multiple times. */
    public void release() {
        clearEmoji();
        listener = null;
    }
}
