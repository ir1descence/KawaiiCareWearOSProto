package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe Animation State Repository.
 * 
 * Supports two types of state changes:
 * 1. Local state changes (sensor/gesture) - immediate, no auto-return
 * 2. External state changes (TCP) - with optional auto-return to IDLE after duration
 * 
 * During external animations, sensor-based state changes are blocked
 * until the external animation completes.
 */
public class AnimationStateRepository {
    private static final String TAG = "AnimationStateRepo";
    
    private static AnimationStateRepository instance;
    private volatile AnimationRenderer.AnimState currentState = AnimationRenderer.AnimState.IDLE;
    private final List<AnimationStateListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // External animation tracking
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

    private AnimationStateRepository() {}

    public static synchronized AnimationStateRepository getInstance() {
        if (instance == null) {
            instance = new AnimationStateRepository();
        }
        return instance;
    }

    /**
     * Set state from local source (sensor/gesture).
     * Will be ignored if an external animation is active.
     * 
     * @param state The new animation state
     * @return true if state was changed, false if blocked by external animation
     */
    public synchronized boolean setState(AnimationRenderer.AnimState state) {
        if (state == null) return false;
        
        // Block local state changes during external animations
        if (isExternalAnimationActive.get()) {
            Log.d(TAG, "Local setState blocked - external animation active. Requested: " + state);
            return false;
        }
        
        return setStateInternal(state);
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
        
        // Set the state
        setStateInternal(state);
        
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
        
        // Reset to IDLE
        setStateInternal(AnimationRenderer.AnimState.IDLE);
        
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
        setStateInternal(AnimationRenderer.AnimState.IDLE);
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
            notifyExternalAnimationEnded();
        }
    }

    public AnimationRenderer.AnimState getState() {
        return currentState;
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
}
