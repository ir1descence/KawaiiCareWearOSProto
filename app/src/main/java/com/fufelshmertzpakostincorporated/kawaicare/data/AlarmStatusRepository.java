package com.fufelshmertzpakostincorporated.kawaicare.data;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Repository for alarm status and active stop signal management.
 * Singleton pattern ensures consistent state across the app.
 * Uses CopyOnWriteArrayList for thread-safe listener management.
 */
public class AlarmStatusRepository {

    private static final String TAG = "AlarmStatusRepository";

    private static volatile AlarmStatusRepository instance;
    
    // Lock object for thread-safe field modifications
    private final Object lock = new Object();
    
    private boolean isAlarmOn = false;
    private String activeStopSignal = SignalRegistry.SIGNAL_SHAKE; // Default to shake
    private String customGestureFilePath = null;
    
    // Thread-safe listener collections using WeakReferences to prevent memory leaks
    private final List<WeakReference<AlarmStatusListener>> listeners = new CopyOnWriteArrayList<>();
    private final List<WeakReference<StopSignalListener>> signalListeners = new CopyOnWriteArrayList<>();

    /**
     * Listener interface for alarm status changes.
     */
    public interface AlarmStatusListener {
        void onAlarmStatusChanged(boolean isAlarmOn);
    }

    /**
     * Listener interface for active stop signal changes.
     */
    public interface StopSignalListener {
        void onStopSignalChanged(String signal, String customGesturePath);
    }

    private AlarmStatusRepository() {}

    /**
     * Get singleton instance using double-checked locking with volatile.
     */
    public static AlarmStatusRepository getInstance() {
        if (instance == null) {
            synchronized (AlarmStatusRepository.class) {
                if (instance == null) {
                    instance = new AlarmStatusRepository();
                }
            }
        }
        return instance;
    }

    // --- Alarm Status Methods ---

    public void setAlarmStatus(boolean isAlarmOn) {
        synchronized (lock) {
            this.isAlarmOn = isAlarmOn;
        }
        Log.d(TAG, "Alarm status set to: " + isAlarmOn);
        notifyAlarmListeners();
    }

    public boolean isAlarmOn() {
        synchronized (lock) {
            return isAlarmOn;
        }
    }

    // --- Active Stop Signal Methods ---

    /**
     * Set the active stop signal for alarm dismissal.
     * If signal is SIGNAL_CUSTOM, automatically references the saved gesture file.
     * 
     * @param signal The signal constant (from SignalRegistry)
     */
    public void setActiveStopSignal(@Nullable String signal) {
        setActiveStopSignal(signal, null);
    }

    /**
     * Set the active stop signal with an optional custom gesture path.
     * 
     * @param signal The signal constant (from SignalRegistry)
     * @param customGesturePath Path to custom gesture file (used when signal is SIGNAL_CUSTOM)
     */
    public void setActiveStopSignal(@Nullable String signal, @Nullable String customGesturePath) {
        if (signal == null || signal.isEmpty()) {
            Log.w(TAG, "Invalid signal, ignoring update");
            return;
        }

        // Validate signal constant
        if (!SignalRegistry.isValidSignal(signal)) {
            Log.w(TAG, "Unknown signal type: " + signal + ", defaulting to SHAKE");
            signal = SignalRegistry.SIGNAL_SHAKE;
        }

        String previousSignal;
        synchronized (lock) {
            previousSignal = this.activeStopSignal;
            this.activeStopSignal = signal;
            this.customGestureFilePath = customGesturePath;
        }

        Log.d(TAG, "Active stop signal changed: " + previousSignal + " -> " + signal);
        
        if (SignalRegistry.SIGNAL_CUSTOM.equals(signal)) {
            Log.d(TAG, "Custom gesture file path: " + customGesturePath);
        }

        notifySignalListeners();
    }

    /**
     * Get the currently active stop signal.
     */
    @NonNull
    public String getActiveStopSignal() {
        synchronized (lock) {
            return activeStopSignal;
        }
    }

    /**
     * Get the custom gesture file path (if SIGNAL_CUSTOM is active).
     */
    @Nullable
    public String getCustomGestureFilePath() {
        synchronized (lock) {
            return customGestureFilePath;
        }
    }

    /**
     * Check if the current signal is the custom gesture.
     */
    public boolean isCustomSignalActive() {
        synchronized (lock) {
            return SignalRegistry.SIGNAL_CUSTOM.equals(activeStopSignal);
        }
    }

    // --- Alarm Status Listener Methods ---

    public void addListener(@NonNull AlarmStatusListener listener) {
        // Check if listener already exists
        for (WeakReference<AlarmStatusListener> ref : listeners) {
            if (ref.get() == listener) {
                return;
            }
        }
        listeners.add(new WeakReference<>(listener));
    }

    public void removeListener(@NonNull AlarmStatusListener listener) {
        for (WeakReference<AlarmStatusListener> ref : new CopyOnWriteArrayList<>(listeners)) {
            AlarmStatusListener l = ref.get();
            if (l == null || l == listener) {
                listeners.remove(ref);
            }
        }
    }

    private void notifyAlarmListeners() {
        boolean alarmState;
        synchronized (lock) {
            alarmState = isAlarmOn;
        }
        
        for (WeakReference<AlarmStatusListener> ref : new CopyOnWriteArrayList<>(listeners)) {
            AlarmStatusListener listener = ref.get();
            if (listener != null) {
                listener.onAlarmStatusChanged(alarmState);
            } else {
                listeners.remove(ref); // Clean up dead references
            }
        }
    }

    // --- Stop Signal Listener Methods ---

    public void addSignalListener(@NonNull StopSignalListener listener) {
        // Check if listener already exists
        for (WeakReference<StopSignalListener> ref : signalListeners) {
            if (ref.get() == listener) {
                return;
            }
        }
        signalListeners.add(new WeakReference<>(listener));
    }

    public void removeSignalListener(@NonNull StopSignalListener listener) {
        for (WeakReference<StopSignalListener> ref : new CopyOnWriteArrayList<>(signalListeners)) {
            StopSignalListener l = ref.get();
            if (l == null || l == listener) {
                signalListeners.remove(ref);
            }
        }
    }

    private void notifySignalListeners() {
        String signal;
        String gesturePath;
        synchronized (lock) {
            signal = activeStopSignal;
            gesturePath = customGestureFilePath;
        }
        
        for (WeakReference<StopSignalListener> ref : new CopyOnWriteArrayList<>(signalListeners)) {
            StopSignalListener listener = ref.get();
            if (listener != null) {
                listener.onStopSignalChanged(signal, gesturePath);
            } else {
                signalListeners.remove(ref); // Clean up dead references
            }
        }
    }

    // --- Utility Methods ---

    /**
     * Reset to default state.
     */
    public void reset() {
        synchronized (lock) {
            isAlarmOn = false;
            activeStopSignal = SignalRegistry.SIGNAL_SHAKE;
            customGestureFilePath = null;
        }
        Log.d(TAG, "Repository reset to defaults");
    }
}
