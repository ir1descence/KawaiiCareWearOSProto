package com.fufelshmertzpakostincorporated.kawaicare.animation;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Animation Finite State Machine (FSM).
 * 
 * Manages animation state transitions with:
 * - Completion requirement: Current animation must finish before new one starts
 * - Single-slot buffer: Only the most recent queued emotion is kept
 * - Cooldown mechanism: Configurable delay between animations
 * - Debouncing: Prevents rapid jittery switching
 * 
 * FSM States:
 * - IDLE: No animation playing, ready to accept new emotion
 * - PLAYING: Animation in progress, new emotions are buffered
 * - COOLDOWN: Brief pause after animation, before transitioning to next
 * 
 * Best Practices Implemented:
 * 1. Only keeps the latest buffered emotion (discards older queued events)
 * 2. Sensor debouncing integrated via cooldown mechanism
 * 3. Thread-safe state transitions using atomic operations
 */
public class AnimationFSM {

    private static final String TAG = "AnimationFSM";

    /**
     * FSM States for the animation system.
     */
    public enum FSMState {
        IDLE,       // Ready to play new animation
        PLAYING,    // Animation currently in progress
        COOLDOWN    // Brief pause between animations
    }

    /**
     * Listener interface for FSM state changes.
     */
    public interface FSMListener {
        /**
         * Called when a new animation should start playing.
         * @param emotion The emotion/animation state to play
         */
        void onPlayAnimation(AnimationRenderer.AnimState emotion);

        /**
         * Called when the FSM returns to idle state.
         */
        void onReturnToIdle();
    }

    // Configuration
    private static final long DEFAULT_COOLDOWN_MS = 500;
    private static final long MIN_COOLDOWN_MS = 100;
    private static final long MAX_COOLDOWN_MS = 2000;

    // FSM State (thread-safe)
    private final AtomicReference<FSMState> currentFSMState = new AtomicReference<>(FSMState.IDLE);
    
    // Current playing emotion
    private volatile AnimationRenderer.AnimState currentEmotion = AnimationRenderer.AnimState.IDLE;
    
    // Buffered emotion (single-slot, only keeps latest)
    private volatile AnimationRenderer.AnimState bufferedEmotion = null;
    
    // Timing
    private volatile long cooldownDurationMs = DEFAULT_COOLDOWN_MS;
    private volatile long currentAnimationDurationMs = 0;
    private volatile long animationStartTime = 0;
    
    // Handler for timed callbacks
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Listener
    private FSMListener listener;
    
    // Runnables for scheduled transitions
    private Runnable animationCompleteRunnable;
    private Runnable cooldownCompleteRunnable;
    
    // External animation lock (for TCP/remote animations)
    private volatile boolean isExternalAnimationActive = false;

    /**
     * Create a new AnimationFSM with default cooldown.
     */
    public AnimationFSM() {
        this(DEFAULT_COOLDOWN_MS);
    }

    /**
     * Create a new AnimationFSM with specified cooldown.
     * @param cooldownMs Cooldown duration in milliseconds
     */
    public AnimationFSM(long cooldownMs) {
        setCooldownDuration(cooldownMs);
    }

    /**
     * Set the FSM listener for state change callbacks.
     */
    public void setListener(FSMListener listener) {
        this.listener = listener;
    }

    /**
     * Set the cooldown duration between animations.
     * @param cooldownMs Duration in milliseconds (clamped to valid range)
     */
    public void setCooldownDuration(long cooldownMs) {
        this.cooldownDurationMs = Math.max(MIN_COOLDOWN_MS, Math.min(MAX_COOLDOWN_MS, cooldownMs));
        Log.d(TAG, "Cooldown duration set to: " + this.cooldownDurationMs + "ms");
    }

    /**
     * Get the current cooldown duration.
     */
    public long getCooldownDuration() {
        return cooldownDurationMs;
    }

    /**
     * Request a new emotion to be played.
     * 
     * Behavior depends on current FSM state:
     * - IDLE: Start playing immediately (unless already playing IDLE)
     * - PLAYING/COOLDOWN: Buffer the emotion (non-IDLE emotions have priority)
     * 
     * @param emotion The emotion/animation state to request
     * @param durationMs The duration of this animation in milliseconds
     * @return true if emotion was accepted (either started or buffered), false if rejected
     */
    public synchronized boolean requestEmotion(AnimationRenderer.AnimState emotion, long durationMs) {
        if (emotion == null) {
            Log.w(TAG, "Null emotion requested, ignoring");
            return false;
        }

        // Block sensor-based emotions during external animations
        if (isExternalAnimationActive) {
            Log.d(TAG, "Emotion request blocked - external animation active: " + emotion);
            return false;
        }

        FSMState state = currentFSMState.get();
        Log.d(TAG, "Emotion requested: " + emotion + " (duration: " + durationMs + "ms) in state: " + state + 
                ", current: " + currentEmotion + ", buffered: " + bufferedEmotion);

        switch (state) {
            case IDLE:
                // Skip if already in IDLE state and requesting IDLE
                // This prevents unnecessary PLAYING transitions for the default state
                if (emotion == AnimationRenderer.AnimState.IDLE && currentEmotion == AnimationRenderer.AnimState.IDLE) {
                    Log.d(TAG, "Already in IDLE state, skipping redundant IDLE request");
                    return true; // Accept but don't transition
                }
                // Immediately start the new emotion
                startAnimation(emotion, durationMs);
                return true;

            case PLAYING:
            case COOLDOWN:
                // Priority-based buffering:
                // - Non-IDLE emotions always override the buffer
                // - IDLE only buffers if no other emotion is buffered (or current is IDLE)
                AnimationRenderer.AnimState previousBuffer = bufferedEmotion;
                
                if (emotion == AnimationRenderer.AnimState.IDLE) {
                    // IDLE request - only buffer if no important emotion is waiting
                    if (previousBuffer != null && previousBuffer != AnimationRenderer.AnimState.IDLE) {
                        Log.d(TAG, "IDLE request ignored - higher priority emotion buffered: " + previousBuffer);
                        return false; // Don't override important buffered emotion with IDLE
                    }
                }
                
                bufferedEmotion = emotion;
                
                if (previousBuffer != null && previousBuffer != emotion) {
                    Log.d(TAG, "Replaced buffered emotion: " + previousBuffer + " -> " + emotion);
                } else if (previousBuffer != emotion) {
                    Log.d(TAG, "Buffered emotion: " + emotion);
                }
                return true;

            default:
                Log.w(TAG, "Unknown FSM state: " + state);
                return false;
        }
    }

    /**
     * Request an emotion without specifying duration.
     * Duration will need to be provided via notifyAnimationDuration().
     */
    public boolean requestEmotion(AnimationRenderer.AnimState emotion) {
        return requestEmotion(emotion, 0);
    }

    /**
     * Notify the FSM of the actual animation duration.
     * Call this when the animation renderer calculates the duration from frame count.
     * 
     * @param emotion The emotion this duration applies to
     * @param durationMs The duration in milliseconds
     */
    public synchronized void notifyAnimationDuration(AnimationRenderer.AnimState emotion, long durationMs) {
        if (currentEmotion == emotion && currentFSMState.get() == FSMState.PLAYING) {
            // Update duration and reschedule completion
            currentAnimationDurationMs = durationMs;
            scheduleAnimationComplete(durationMs);
            Log.d(TAG, "Updated animation duration for " + emotion + ": " + durationMs + "ms");
        }
    }

    /**
     * Notify the FSM that the current animation cycle has completed.
     * Call this from the animation renderer when it finishes one full cycle.
     */
    public synchronized void notifyAnimationCycleComplete() {
        if (currentFSMState.get() != FSMState.PLAYING) {
            return;
        }

        Log.d(TAG, "Animation cycle complete for: " + currentEmotion);
        
        // Cancel any pending auto-complete (we're completing now)
        cancelPendingCallbacks();
        
        // Transition to cooldown
        transitionToCooldown();
    }

    /**
     * Force an immediate transition to a specific emotion.
     * Bypasses the queue and cooldown. Use sparingly (e.g., for alarms).
     * 
     * @param emotion The emotion to force
     * @param durationMs The duration of this animation
     */
    public synchronized void forceEmotion(AnimationRenderer.AnimState emotion, long durationMs) {
        Log.i(TAG, "Forcing emotion: " + emotion);
        
        // Cancel everything
        cancelPendingCallbacks();
        bufferedEmotion = null;
        
        // Start immediately
        startAnimation(emotion, durationMs);
    }

    /**
     * Start an external (TCP/remote) animation.
     * Blocks all sensor-based emotion requests until the external animation ends.
     * 
     * @param emotion The emotion to play
     * @param durationMs Duration in milliseconds (0 = indefinite until manually ended)
     */
    public synchronized void startExternalAnimation(AnimationRenderer.AnimState emotion, long durationMs) {
        Log.i(TAG, "Starting external animation: " + emotion + " (duration: " + durationMs + "ms)");
        
        // Cancel current state
        cancelPendingCallbacks();
        bufferedEmotion = null;
        
        // Set external flag
        isExternalAnimationActive = true;
        
        // Start the animation
        startAnimation(emotion, durationMs);
    }

    /**
     * End the current external animation and return to normal operation.
     */
    public synchronized void endExternalAnimation() {
        if (!isExternalAnimationActive) {
            return;
        }
        
        Log.i(TAG, "Ending external animation");
        isExternalAnimationActive = false;
        
        // Force transition to IDLE
        cancelPendingCallbacks();
        bufferedEmotion = null;
        transitionToIdle();
    }

    /**
     * Check if an external animation is currently active.
     */
    public boolean isExternalAnimationActive() {
        return isExternalAnimationActive;
    }

    /**
     * Force reset to IDLE state, clearing all buffers and pending callbacks.
     */
    public synchronized void forceResetToIdle() {
        Log.w(TAG, "Force reset to IDLE");
        
        cancelPendingCallbacks();
        bufferedEmotion = null;
        isExternalAnimationActive = false;
        transitionToIdle();
    }

    /**
     * Get the current FSM state.
     */
    public FSMState getCurrentFSMState() {
        return currentFSMState.get();
    }

    /**
     * Get the current playing emotion.
     */
    public AnimationRenderer.AnimState getCurrentEmotion() {
        return currentEmotion;
    }

    /**
     * Get the buffered emotion (if any).
     */
    public AnimationRenderer.AnimState getBufferedEmotion() {
        return bufferedEmotion;
    }

    /**
     * Check if the FSM is ready to accept new emotions immediately.
     */
    public boolean isIdle() {
        return currentFSMState.get() == FSMState.IDLE && !isExternalAnimationActive;
    }

    // --- Private State Transition Methods ---

    private void startAnimation(AnimationRenderer.AnimState emotion, long durationMs) {
        FSMState previousState = currentFSMState.get();
        AnimationRenderer.AnimState previousEmotion = currentEmotion;
        
        currentFSMState.set(FSMState.PLAYING);
        currentEmotion = emotion;
        currentAnimationDurationMs = durationMs;
        animationStartTime = System.currentTimeMillis();
        
        Log.i(TAG, "Starting animation: " + emotion + " (FSM: " + previousState + " -> PLAYING, " +
                "prev emotion: " + previousEmotion + ", duration: " + durationMs + "ms)");
        
        // Notify listener to start the animation
        if (listener != null) {
            mainHandler.post(() -> listener.onPlayAnimation(emotion));
        }
        
        // Schedule completion if duration is known
        if (durationMs > 0) {
            scheduleAnimationComplete(durationMs);
        }
    }

    private void scheduleAnimationComplete(long durationMs) {
        // Cancel any existing scheduled completion
        if (animationCompleteRunnable != null) {
            mainHandler.removeCallbacks(animationCompleteRunnable);
        }
        
        animationCompleteRunnable = () -> {
            synchronized (AnimationFSM.this) {
                if (currentFSMState.get() == FSMState.PLAYING) {
                    Log.d(TAG, "Animation auto-completed after " + durationMs + "ms");
                    transitionToCooldown();
                }
            }
        };
        
        mainHandler.postDelayed(animationCompleteRunnable, durationMs);
    }

    private void transitionToCooldown() {
        currentFSMState.set(FSMState.COOLDOWN);
        Log.d(TAG, "FSM -> COOLDOWN (" + cooldownDurationMs + "ms)");
        
        // Schedule end of cooldown
        cooldownCompleteRunnable = () -> {
            synchronized (AnimationFSM.this) {
                if (currentFSMState.get() == FSMState.COOLDOWN) {
                    onCooldownComplete();
                }
            }
        };
        
        mainHandler.postDelayed(cooldownCompleteRunnable, cooldownDurationMs);
    }

    private void onCooldownComplete() {
        Log.d(TAG, "Cooldown complete - checking buffer");
        
        // Check if there's a buffered emotion
        AnimationRenderer.AnimState nextEmotion = bufferedEmotion;
        bufferedEmotion = null;
        
        if (nextEmotion != null) {
            // Skip buffered IDLE if we're already going to return to IDLE anyway
            if (nextEmotion == AnimationRenderer.AnimState.IDLE) {
                Log.d(TAG, "Buffered emotion is IDLE - transitioning directly to IDLE state");
                transitionToIdle();
            } else {
                Log.d(TAG, "Playing buffered emotion: " + nextEmotion);
                // Start the buffered emotion (duration will be notified later)
                startAnimation(nextEmotion, 0);
            }
        } else {
            // No buffered emotion, return to IDLE
            Log.d(TAG, "No buffered emotion - returning to IDLE");
            transitionToIdle();
        }
    }

    private void transitionToIdle() {
        FSMState previousState = currentFSMState.get();
        AnimationRenderer.AnimState previousEmotion = currentEmotion;
        
        currentFSMState.set(FSMState.IDLE);
        currentEmotion = AnimationRenderer.AnimState.IDLE;
        
        Log.i(TAG, "FSM -> IDLE (from state: " + previousState + ", emotion: " + previousEmotion + 
                ", buffer cleared: " + (bufferedEmotion != null) + ")");
        
        // Clear any stale buffer when transitioning to IDLE
        bufferedEmotion = null;
        
        // Notify listener
        if (listener != null) {
            mainHandler.post(() -> listener.onReturnToIdle());
        }
    }

    private void cancelPendingCallbacks() {
        if (animationCompleteRunnable != null) {
            mainHandler.removeCallbacks(animationCompleteRunnable);
            animationCompleteRunnable = null;
        }
        if (cooldownCompleteRunnable != null) {
            mainHandler.removeCallbacks(cooldownCompleteRunnable);
            cooldownCompleteRunnable = null;
        }
    }

    /**
     * Release resources. Call when the FSM is no longer needed.
     */
    public void release() {
        cancelPendingCallbacks();
        listener = null;
        bufferedEmotion = null;
        isExternalAnimationActive = false;
    }
}
