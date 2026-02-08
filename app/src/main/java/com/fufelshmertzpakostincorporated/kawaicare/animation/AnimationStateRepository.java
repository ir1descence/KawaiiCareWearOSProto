package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe Animation State Repository with FSM integration.
 * 
 * Supports two types of state changes:
 * 1. Local state changes (sensor/gesture) - managed through FSM with queueing
 * 2. External state changes (TCP) - bypass FSM, block local changes
 * 
 * The FSM ensures:
 * - Current animation completes before new one starts
 * - Single-slot buffering (only latest queued emotion kept)
 * - Configurable cooldown between animations
 * 
 * During external animations, sensor-based state changes are blocked
 * until the external animation completes.
 */
public class AnimationStateRepository implements AnimationFSM.FSMListener {
    private static final String TAG = "AnimationStateRepo";
    
    private static AnimationStateRepository instance;
    private volatile AnimationRenderer.AnimState currentState = AnimationRenderer.AnimState.IDLE;
    private final List<AnimationStateListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // FSM for managing animation transitions
    private final AnimationFSM animationFSM;
    
    // External animation tracking (legacy support)
    private final AtomicBoolean isExternalAnimationActive = new AtomicBoolean(false);
    private volatile long externalAnimationStartTime = 0;
    private volatile long externalAnimationDuration = 0;
    private Runnable externalAnimationEndRunnable;

    /**
     * Listener interface for animation state changes.
     */
    public interface AnimationStateListener {
        void onAnimationStateChanged(AnimationRenderer.AnimState state);
        
        /**
         * Called when an external (TCP) animation starts.
         * Allows MainActivity to block sensor processing.
         */
        default void onExternalAnimationStarted() {}
        
        /**
         * Called when an external (TCP) animation ends.
         * Allows MainActivity to re-enable sensor processing.
         */
        default void onExternalAnimationEnded() {}
    }

    private AnimationStateRepository() {
        animationFSM = new AnimationFSM();
        animationFSM.setListener(this);
    }

    public static synchronized AnimationStateRepository getInstance() {
        if (instance == null) {
            instance = new AnimationStateRepository();
        }
        return instance;
    }

    /**
     * Set state from local source (sensor/gesture).
     * The state change will be queued if an animation is currently playing.
     * Only the latest queued state is kept (previous buffered states are discarded).
     * 
     * @param state The new animation state
     * @return true if state was accepted (either started or buffered), false if blocked
     */
    public synchronized boolean setState(AnimationRenderer.AnimState state) {
        return setState(state, 0);
    }
    
    /**
     * Set state from local source with known duration.
     * The state change will be queued if an animation is currently playing.
     * 
     * @param state The new animation state
     * @param durationMs Duration of this animation (0 = unknown, will be calculated)
     * @return true if state was accepted (either started or buffered), false if blocked
     */
    public synchronized boolean setState(AnimationRenderer.AnimState state, long durationMs) {
        if (state == null) return false;
        
        // Block local state changes during external animations
        if (isExternalAnimationActive.get()) {
            Log.d(TAG, "Local setState blocked - external animation active. Requested: " + state);
            return false;
        }
        
        // Delegate to FSM for proper queueing and timing
        return animationFSM.requestEmotion(state, durationMs);
    }
    
    /**
     * Set state immediately, bypassing the FSM queue.
     * Use this for high-priority states like ALARM that shouldn't wait.
     * 
     * @param state The new animation state
     * @param durationMs Duration of this animation
     */
    public synchronized void setStateImmediate(AnimationRenderer.AnimState state, long durationMs) {
        if (state == null) return;
        
        if (isExternalAnimationActive.get()) {
            Log.d(TAG, "Immediate setState blocked - external animation active. Requested: " + state);
            return;
        }
        
        animationFSM.forceEmotion(state, durationMs);
    }

    /**
     * Set state from external source (TCP) with auto-return to IDLE.
     * Blocks sensor-based state changes until the animation completes.
     * 
     * @param state The animation state to play
     * @param durationMs How long to play before returning to IDLE (0 = indefinite)
     */
    public synchronized void setExternalState(AnimationRenderer.AnimState state, long durationMs) {
        if (state == null) return;
        
        Log.i(TAG, "External animation started: " + state + ", duration: " + durationMs + "ms");
        
        // Cancel any pending auto-return
        cancelPendingExternalEnd();
        
        // Mark external animation as active
        isExternalAnimationActive.set(true);
        externalAnimationStartTime = System.currentTimeMillis();
        externalAnimationDuration = durationMs;
        
        // Notify listeners that external animation started
        notifyExternalAnimationStarted();
        
        // Use FSM's external animation mode
        animationFSM.startExternalAnimation(state, durationMs);
        
        // Schedule auto-return to IDLE if duration specified
        if (durationMs > 0) {
            externalAnimationEndRunnable = () -> {
                Log.i(TAG, "External animation auto-ending, returning to IDLE");
                endExternalAnimation();
            };
            mainHandler.postDelayed(externalAnimationEndRunnable, durationMs);
        }
    }
    
    /**
     * Special duration value indicating the duration should be calculated
     * from the actual animation frame count.
     */
    public static final long DURATION_AUTO = -1;
    
    /**
     * Duration provider interface for calculating animation durations dynamically.
     */
    public interface AnimationDurationProvider {
        /**
         * Calculate the duration for a given animation state.
         * @param state The animation state
         * @return Duration in milliseconds, or 0 if unknown
         */
        long getDurationForState(AnimationRenderer.AnimState state);
    }
    
    private AnimationDurationProvider durationProvider;
    
    /**
     * Set the duration provider for auto-calculating animation durations.
     */
    public void setDurationProvider(AnimationDurationProvider provider) {
        this.durationProvider = provider;
    }
    
    /**
     * Convenience method for external state with auto-calculated duration.
     * Uses the duration provider if set, otherwise defaults to 5 seconds.
     */
    public void setExternalState(AnimationRenderer.AnimState state) {
        long duration = 5000; // Default fallback
        
        if (durationProvider != null) {
            long calculatedDuration = durationProvider.getDurationForState(state);
            if (calculatedDuration > 0) {
                duration = calculatedDuration;
                Log.d(TAG, "Auto-calculated duration for " + state + ": " + duration + "ms");
            }
        }
        
        setExternalState(state, duration);
    }

    /**
     * Manually end an external animation and return to IDLE.
     * Call this when the animation cycle completes or when interrupted.
     */
    public synchronized void endExternalAnimation() {
        if (!isExternalAnimationActive.getAndSet(false)) {
            return; // Already ended
        }
        
        cancelPendingExternalEnd();
        
        Log.i(TAG, "External animation ended, returning to IDLE");
        
        // End the external animation in FSM
        animationFSM.endExternalAnimation();
        
        // Notify listeners that external animation ended
        notifyExternalAnimationEnded();
    }

    /**
     * Check if an external (TCP) animation is currently blocking sensor input.
     */
    public boolean isExternalAnimationActive() {
        return isExternalAnimationActive.get();
    }
    
    /**
     * Force reset to IDLE state, clearing any external animation lock.
     * Use this for emergency reset (e.g., alarm dismissal).
     */
    public synchronized void forceResetToIdle() {
        Log.w(TAG, "Force reset to IDLE requested");
        cancelPendingExternalEnd();
        isExternalAnimationActive.set(false);
        animationFSM.forceResetToIdle();
        notifyExternalAnimationEnded();
    }
    
    /**
     * Clear the external animation lock without changing state.
     * Use this when you want to allow a new state to be set immediately
     * without triggering an intermediate IDLE state change.
     * This prevents bitmap race conditions when rapidly switching states.
     */
    public synchronized void clearExternalAnimationLock() {
        if (isExternalAnimationActive.getAndSet(false)) {
            Log.d(TAG, "External animation lock cleared (without state change)");
            cancelPendingExternalEnd();
            animationFSM.endExternalAnimation();
            notifyExternalAnimationEnded();
        }
    }
    
    /**
     * Get the underlying FSM for advanced configuration.
     * @return The AnimationFSM instance
     */
    public AnimationFSM getFSM() {
        return animationFSM;
    }
    
    /**
     * Set the cooldown duration between animations.
     * @param cooldownMs Duration in milliseconds (100-2000ms)
     */
    public void setCooldownDuration(long cooldownMs) {
        animationFSM.setCooldownDuration(cooldownMs);
    }
    
    /**
     * Notify the FSM that an animation cycle has completed.
     * Call this from the animation renderer's cycle callback.
     */
    public void notifyAnimationCycleComplete() {
        animationFSM.notifyAnimationCycleComplete();
    }
    
    /**
     * Notify the FSM of the actual duration for the current animation.
     * @param state The animation state
     * @param durationMs Duration in milliseconds
     */
    public void notifyAnimationDuration(AnimationRenderer.AnimState state, long durationMs) {
        animationFSM.notifyAnimationDuration(state, durationMs);
    }

    public AnimationRenderer.AnimState getState() {
        return currentState;
    }

    /**
     * Centralized mapping of States to Asset Folder paths.
     * Maps the abstract state enum to the concrete asset folder name.
     */
    public String getFolderPathForState(AnimationRenderer.AnimState state) {
        if (state == null) return "blinks";
        
        switch (state) {
            case IDLE:
                return "blinks";
            case TILTED:
            case LOOK_LEFT:
                return "looks_to_the_left";
            case GESTURE_ACTION:
                return "nod";
            case SHAKE:
                return "shake_smile";
            case ALARM:
                return "notification";
            case LEARNING:
            case LOOK_RIGHT:
                return "looks_to_the_right";
            case FRIGHT:
                return "fright";
            case NOTIFICATION_POSTPONE:
                return "notification_postpone";
            case NOTIFICATION_EMOJI:
                // Emoji animation is handled specially by EmojiCompositor
                // (two folders + overlay). Return the before-blink folder as primary.
                return EmojiCompositor.FOLDER_BEFORE_BLINK;
            default:
                return "blinks";
        }
    }

    public void addListener(AnimationStateListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(AnimationStateListener listener) {
        listeners.remove(listener);
    }
    
    // --- Private Helpers ---

    private boolean setStateInternal(AnimationRenderer.AnimState state) {
        if (currentState == state) {
            return false; // No change
        }
        currentState = state;
        notifyStateChanged();
        return true;
    }
    
    private void cancelPendingExternalEnd() {
        if (externalAnimationEndRunnable != null) {
            mainHandler.removeCallbacks(externalAnimationEndRunnable);
            externalAnimationEndRunnable = null;
        }
    }
    
    private void notifyStateChanged() {
        // Always notify on main thread for UI safety
        final AnimationRenderer.AnimState state = currentState;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (AnimationStateListener listener : listeners) {
                listener.onAnimationStateChanged(state);
            }
        } else {
            mainHandler.post(() -> {
                for (AnimationStateListener listener : listeners) {
                    listener.onAnimationStateChanged(state);
                }
            });
        }
    }
    
    private void notifyExternalAnimationStarted() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (AnimationStateListener listener : listeners) {
                listener.onExternalAnimationStarted();
            }
        } else {
            mainHandler.post(() -> {
                for (AnimationStateListener listener : listeners) {
                    listener.onExternalAnimationStarted();
                }
            });
        }
    }
    
    private void notifyExternalAnimationEnded() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (AnimationStateListener listener : listeners) {
                listener.onExternalAnimationEnded();
            }
        } else {
            mainHandler.post(() -> {
                for (AnimationStateListener listener : listeners) {
                    listener.onExternalAnimationEnded();
                }
            });
        }
    }
    
    // --- AnimationFSM.FSMListener Implementation ---
    
    @Override
    public void onPlayAnimation(AnimationRenderer.AnimState emotion) {
        Log.d(TAG, "FSM requested animation: " + emotion);
        setStateInternal(emotion);
    }
    
    @Override
    public void onReturnToIdle() {
        Log.d(TAG, "FSM returned to IDLE");
        setStateInternal(AnimationRenderer.AnimState.IDLE);
    }
}
