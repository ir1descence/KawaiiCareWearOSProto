package com.fufelshmertzpakostincorporated.kawaicare.alarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Repository for storing and managing alarms.
 * Persists alarms to SharedPreferences as JSON.
 * Thread-safe singleton implementation.
 */
public class WearAlarmRepository {

    private static final String TAG = "WearAlarmRepository";
    private static final String PREFS_NAME = "kawaicare_alarms";
    private static final String KEY_ALARMS = "alarms_json";

    private static volatile WearAlarmRepository instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, Alarm> alarmsCache = new HashMap<>();
    private final List<AlarmChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean initialized = false;

    /**
     * Listener interface for alarm changes.
     */
    public interface AlarmChangeListener {
        void onAlarmAdded(Alarm alarm);
        void onAlarmUpdated(Alarm alarm);
        void onAlarmRemoved(String alarmId);
        void onAlarmsCleared();
    }

    private WearAlarmRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get singleton instance.
     */
    public static WearAlarmRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (WearAlarmRepository.class) {
                if (instance == null) {
                    instance = new WearAlarmRepository(context);
                }
            }
        }
        return instance;
    }

    /**
     * Initialize repository by loading alarms from storage.
     * Should be called on app startup.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            String json = prefs.getString(KEY_ALARMS, null);
            if (json != null && !json.isEmpty()) {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    Alarm alarm = Alarm.fromJson(obj);
                    alarmsCache.put(alarm.getId(), alarm);
                }
                Log.d(TAG, "Loaded " + alarmsCache.size() + " alarms from storage");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading alarms from storage", e);
            // Clear corrupted data
            prefs.edit().remove(KEY_ALARMS).apply();
        }

        initialized = true;
    }

    /**
     * Save all alarms to persistent storage.
     */
    private synchronized void persistAlarms() {
        try {
            JSONArray array = new JSONArray();
            for (Alarm alarm : alarmsCache.values()) {
                array.put(alarm.toJson());
            }
            prefs.edit().putString(KEY_ALARMS, array.toString()).apply();
            Log.d(TAG, "Persisted " + alarmsCache.size() + " alarms");
        } catch (JSONException e) {
            Log.e(TAG, "Error persisting alarms", e);
        }
    }

    // --- CRUD Operations ---

    /**
     * Add a new alarm.
     *
     * @param alarm The alarm to add
     * @return true if added successfully
     */
    public synchronized boolean addAlarm(@NonNull Alarm alarm) {
        ensureInitialized();

        if (alarmsCache.containsKey(alarm.getId())) {
            Log.w(TAG, "Alarm with ID " + alarm.getId() + " already exists");
            return false;
        }

        alarmsCache.put(alarm.getId(), alarm);
        persistAlarms();
        notifyAlarmAdded(alarm);

        Log.d(TAG, "Added alarm: " + alarm);
        return true;
    }

    /**
     * Get an alarm by ID.
     */
    @Nullable
    public synchronized Alarm getAlarm(@NonNull String alarmId) {
        ensureInitialized();
        return alarmsCache.get(alarmId);
    }

    /**
     * Get all alarms.
     */
    @NonNull
    public synchronized List<Alarm> getAllAlarms() {
        ensureInitialized();
        return new ArrayList<>(alarmsCache.values());
    }

    /**
     * Get all enabled alarms.
     */
    @NonNull
    public synchronized List<Alarm> getEnabledAlarms() {
        ensureInitialized();
        List<Alarm> enabled = new ArrayList<>();
        for (Alarm alarm : alarmsCache.values()) {
            if (alarm.isEnabled()) {
                enabled.add(alarm);
            }
        }
        return enabled;
    }

    /**
     * Get all enabled alarms that are in the future.
     */
    @NonNull
    public synchronized List<Alarm> getFutureEnabledAlarms() {
        ensureInitialized();
        long now = System.currentTimeMillis();
        List<Alarm> future = new ArrayList<>();
        for (Alarm alarm : alarmsCache.values()) {
            if (alarm.isEnabled() && alarm.getTimeMillis() > now) {
                future.add(alarm);
            }
        }
        return future;
    }

    /**
     * Update an existing alarm.
     */
    public synchronized boolean updateAlarm(@NonNull Alarm alarm) {
        ensureInitialized();

        if (!alarmsCache.containsKey(alarm.getId())) {
            Log.w(TAG, "Alarm with ID " + alarm.getId() + " not found for update");
            return false;
        }

        alarmsCache.put(alarm.getId(), alarm);
        persistAlarms();
        notifyAlarmUpdated(alarm);

        Log.d(TAG, "Updated alarm: " + alarm);
        return true;
    }

    /**
     * Set the enabled state of an alarm by ID.
     *
     * @param alarmId The alarm ID
     * @param enabled The new enabled state
     * @return The updated alarm, or null if not found
     */
    @Nullable
    public synchronized Alarm setAlarmEnabled(@NonNull String alarmId, boolean enabled) {
        ensureInitialized();

        Alarm alarm = alarmsCache.get(alarmId);
        if (alarm == null) {
            Log.w(TAG, "Alarm with ID " + alarmId + " not found");
            return null;
        }

        alarm.setEnabled(enabled);
        persistAlarms();
        notifyAlarmUpdated(alarm);

        Log.d(TAG, "Set alarm " + alarmId + " enabled: " + enabled);
        return alarm;
    }

    /**
     * Remove an alarm by ID.
     *
     * @return true if removed
     */
    public synchronized boolean removeAlarm(@NonNull String alarmId) {
        ensureInitialized();

        Alarm removed = alarmsCache.remove(alarmId);
        if (removed != null) {
            persistAlarms();
            notifyAlarmRemoved(alarmId);
            Log.d(TAG, "Removed alarm: " + alarmId);
            return true;
        }

        Log.w(TAG, "Alarm with ID " + alarmId + " not found for removal");
        return false;
    }

    /**
     * Clear all alarms.
     */
    public synchronized void clearAllAlarms() {
        ensureInitialized();

        alarmsCache.clear();
        persistAlarms();
        notifyAlarmsCleared();

        Log.d(TAG, "Cleared all alarms");
    }

    /**
     * Get the number of alarms.
     */
    public synchronized int getAlarmCount() {
        ensureInitialized();
        return alarmsCache.size();
    }

    /**
     * Check if an alarm exists.
     */
    public synchronized boolean hasAlarm(@NonNull String alarmId) {
        ensureInitialized();
        return alarmsCache.containsKey(alarmId);
    }

    /**
     * Export all alarms as JSON array.
     */
    @NonNull
    public synchronized JSONArray exportToJson() throws JSONException {
        ensureInitialized();
        JSONArray array = new JSONArray();
        for (Alarm alarm : alarmsCache.values()) {
            array.put(alarm.toJson());
        }
        return array;
    }

    /**
     * Import alarms from JSON array (replaces existing).
     */
    public synchronized int importFromJson(@NonNull JSONArray array) throws JSONException {
        ensureInitialized();

        alarmsCache.clear();
        int count = 0;

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            Alarm alarm = Alarm.fromJson(obj);
            alarmsCache.put(alarm.getId(), alarm);
            count++;
        }

        persistAlarms();
        notifyAlarmsCleared(); // Signal full refresh

        Log.d(TAG, "Imported " + count + " alarms");
        return count;
    }

    // --- Listener Management ---

    public void addListener(@NonNull AlarmChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NonNull AlarmChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyAlarmAdded(Alarm alarm) {
        for (AlarmChangeListener listener : listeners) {
            try {
                listener.onAlarmAdded(alarm);
            } catch (Exception e) {
                Log.e(TAG, "Error in alarm listener", e);
            }
        }
    }

    private void notifyAlarmUpdated(Alarm alarm) {
        for (AlarmChangeListener listener : listeners) {
            try {
                listener.onAlarmUpdated(alarm);
            } catch (Exception e) {
                Log.e(TAG, "Error in alarm listener", e);
            }
        }
    }

    private void notifyAlarmRemoved(String alarmId) {
        for (AlarmChangeListener listener : listeners) {
            try {
                listener.onAlarmRemoved(alarmId);
            } catch (Exception e) {
                Log.e(TAG, "Error in alarm listener", e);
            }
        }
    }

    private void notifyAlarmsCleared() {
        for (AlarmChangeListener listener : listeners) {
            try {
                listener.onAlarmsCleared();
            } catch (Exception e) {
                Log.e(TAG, "Error in alarm listener", e);
            }
        }
    }

    // --- Helper Methods ---

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }
}
