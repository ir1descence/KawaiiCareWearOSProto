package com.fufelshmertzpakostincorporated.kawaicare.data;

import android.util.Log;

import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;

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
    private volatile boolean isAlarmOn = false;
    private volatile String activeStopSignal = SignalRegistry.SIGNAL_SHAKE; // Default to shake
    private volatile String customGestureFilePath = null;
    
    // Thread-safe listener collections
    private final List<AlarmStatusListener> listeners = new CopyOnWriteArrayList<>();
    private final List<StopSignalListener> signalListeners = new CopyOnWriteArrayList<>();

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
        this.isAlarmOn = isAlarmOn;
        Log.d(TAG, "Alarm status set to: " + isAlarmOn);
        notifyAlarmListeners();
    }

    public boolean isAlarmOn() {
        return isAlarmOn;
    }

    // --- Active Stop Signal Methods ---

    /**
     * Set the active stop signal for alarm dismissal.
     * If signal is SIGNAL_CUSTOM, automatically references the saved gesture file.
     * 
     * @param signal The signal constant (from SignalRegistry)
     */
    public void setActiveStopSignal(String signal) {
        setActiveStopSignal(signal, null);
    }

    /**
     * Set the active stop signal with an optional custom gesture path.
     * 
     * @param signal The signal constant (from SignalRegistry)
     * @param customGesturePath Path to custom gesture file (used when signal is SIGNAL_CUSTOM)
     */
    public void setActiveStopSignal(String signal, String customGesturePath) {
        if (signal == null || signal.isEmpty()) {
            Log.w(TAG, "Invalid signal, ignoring update");
            return;
        }

        // Validate signal constant
        if (!SignalRegistry.isValidSignal(signal)) {
            Log.w(TAG, "Unknown signal type: " + signal + ", defaulting to SHAKE");
            signal = SignalRegistry.SIGNAL_SHAKE;
        }

        String previousSignal = this.activeStopSignal;
        this.activeStopSignal = signal;
        this.customGestureFilePath = customGesturePath;

        Log.d(TAG, "Active stop signal changed: " + previousSignal + " -> " + signal);
        
        if (SignalRegistry.SIGNAL_CUSTOM.equals(signal)) {
            Log.d(TAG, "Custom gesture file path: " + customGesturePath);
        }

        notifySignalListeners();
    }

    /**
     * Get the currently active stop signal.
     */
    public String getActiveStopSignal() {
        return activeStopSignal;
    }

    /**
     * Get the custom gesture file path (if SIGNAL_CUSTOM is active).
     */
    public String getCustomGestureFilePath() {
        return customGestureFilePath;
    }

    /**
     * Check if the current signal is the custom gesture.
     */
    public boolean isCustomSignalActive() {
        return SignalRegistry.SIGNAL_CUSTOM.equals(activeStopSignal);
    }

    // --- Alarm Status Listener Methods ---

    public void addListener(AlarmStatusListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(AlarmStatusListener listener) {
        listeners.remove(listener);
    }

    private void notifyAlarmListeners() {
        for (AlarmStatusListener listener : listeners) {
            listener.onAlarmStatusChanged(isAlarmOn);
        }
    }

    // --- Stop Signal Listener Methods ---

    public void addSignalListener(StopSignalListener listener) {
        if (!signalListeners.contains(listener)) {
            signalListeners.add(listener);
        }
    }

    public void removeSignalListener(StopSignalListener listener) {
        signalListeners.remove(listener);
    }

    private void notifySignalListeners() {
        for (StopSignalListener listener : signalListeners) {
            listener.onStopSignalChanged(activeStopSignal, customGestureFilePath);
        }
    }

    // --- Utility Methods ---

    /**
     * Reset to default state.
     */
    public void reset() {
        isAlarmOn = false;
        activeStopSignal = SignalRegistry.SIGNAL_SHAKE;
        customGestureFilePath = null;
        Log.d(TAG, "Repository reset to defaults");
    }
}
