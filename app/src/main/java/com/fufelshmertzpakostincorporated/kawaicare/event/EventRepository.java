package com.fufelshmertzpakostincorporated.kawaicare.event;

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
 * Repository for storing and managing scheduled events (Alarms and Reminders).
 * 
 * Provides thread-safe persistence and notification of changes to listeners.
 * Supports full event synchronization from client devices over TCP.
 * 
 * Features:
 * - CRUD operations for ScheduledEvent objects
 * - Full event list synchronization (sync_events)
 * - Listener pattern for change notifications
 * - JSON-based persistence to SharedPreferences
 */
public class EventRepository {

    private static final String TAG = "EventRepository";
    private static final String PREFS_NAME = "kawaicare_events";
    private static final String KEY_EVENTS = "events_json";
    private static final String KEY_SYNC_TIMESTAMP = "sync_timestamp";

    private static volatile EventRepository instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, ScheduledEvent> eventsCache = new HashMap<>();
    private final List<EventChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean initialized = false;
    private volatile long lastSyncTimestamp = 0;

    /**
     * Listener interface for event changes.
     */
    public interface EventChangeListener {
        /** Called when a new event is added */
        void onEventAdded(ScheduledEvent event);
        
        /** Called when an existing event is updated */
        void onEventUpdated(ScheduledEvent event);
        
        /** Called when an event is removed */
        void onEventRemoved(String eventId);
        
        /** Called when all events are replaced (sync operation) */
        void onEventsSynced(List<ScheduledEvent> events);
        
        /** Called when all events are cleared */
        void onEventsCleared();
    }

    /**
     * Adapter class for selective listener implementation.
     */
    public static class EventChangeAdapter implements EventChangeListener {
        @Override public void onEventAdded(ScheduledEvent event) {}
        @Override public void onEventUpdated(ScheduledEvent event) {}
        @Override public void onEventRemoved(String eventId) {}
        @Override public void onEventsSynced(List<ScheduledEvent> events) {}
        @Override public void onEventsCleared() {}
    }

    private EventRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get singleton instance.
     */
    public static EventRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (EventRepository.class) {
                if (instance == null) {
                    instance = new EventRepository(context);
                }
            }
        }
        return instance;
    }

    /**
     * Initialize repository by loading events from storage.
     * Should be called on app startup.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            String json = prefs.getString(KEY_EVENTS, null);
            lastSyncTimestamp = prefs.getLong(KEY_SYNC_TIMESTAMP, 0);
            
            if (json != null && !json.isEmpty()) {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    ScheduledEvent event = ScheduledEvent.fromJson(obj);
                    eventsCache.put(event.getId(), event);
                }
                Log.d(TAG, "Loaded " + eventsCache.size() + " events from storage");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading events from storage", e);
            // Clear corrupted data
            prefs.edit().remove(KEY_EVENTS).apply();
        }

        initialized = true;
    }

    /**
     * Save all events to persistent storage.
     */
    private synchronized void persistEvents() {
        try {
            JSONArray array = new JSONArray();
            for (ScheduledEvent event : eventsCache.values()) {
                array.put(event.toJson());
            }
            prefs.edit()
                    .putString(KEY_EVENTS, array.toString())
                    .putLong(KEY_SYNC_TIMESTAMP, lastSyncTimestamp)
                    .apply();
            Log.d(TAG, "Persisted " + eventsCache.size() + " events");
        } catch (JSONException e) {
            Log.e(TAG, "Error persisting events", e);
        }
    }

    // =========================================
    // CRUD Operations
    // =========================================

    /**
     * Add a new event.
     *
     * @param event The event to add
     * @return true if added successfully
     */
    public synchronized boolean addEvent(@NonNull ScheduledEvent event) {
        ensureInitialized();

        if (eventsCache.containsKey(event.getId())) {
            Log.w(TAG, "Event with ID " + event.getId() + " already exists, updating instead");
            return updateEvent(event);
        }

        eventsCache.put(event.getId(), event);
        persistEvents();
        notifyEventAdded(event);

        Log.d(TAG, "Added event: " + event);
        return true;
    }

    /**
     * Get an event by ID.
     */
    @Nullable
    public synchronized ScheduledEvent getEvent(@NonNull String eventId) {
        ensureInitialized();
        return eventsCache.get(eventId);
    }

    /**
     * Get all events.
     */
    @NonNull
    public synchronized List<ScheduledEvent> getAllEvents() {
        ensureInitialized();
        return new ArrayList<>(eventsCache.values());
    }

    /**
     * Get all enabled events.
     */
    @NonNull
    public synchronized List<ScheduledEvent> getEnabledEvents() {
        ensureInitialized();
        List<ScheduledEvent> enabled = new ArrayList<>();
        for (ScheduledEvent event : eventsCache.values()) {
            if (event.isEnabled()) {
                enabled.add(event);
            }
        }
        return enabled;
    }

    /**
     * Get all enabled events that will trigger in the future.
     */
    @NonNull
    public synchronized List<ScheduledEvent> getFutureEnabledEvents() {
        ensureInitialized();
        List<ScheduledEvent> future = new ArrayList<>();
        for (ScheduledEvent event : eventsCache.values()) {
            if (event.isEnabled()) {
                // For recurring events, check if they have a next trigger time
                long nextTrigger = event.getNextTriggerTime();
                if (nextTrigger > 0) {
                    future.add(event);
                }
            }
        }
        return future;
    }

    /**
     * Get events by type (ALARM or REMINDER).
     */
    @NonNull
    public synchronized List<ScheduledEvent> getEventsByType(ScheduledEvent.EventType type) {
        ensureInitialized();
        List<ScheduledEvent> filtered = new ArrayList<>();
        for (ScheduledEvent event : eventsCache.values()) {
            if (event.getEventType() == type) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    /**
     * Get all alarms.
     */
    @NonNull
    public synchronized List<ScheduledEvent> getAlarms() {
        return getEventsByType(ScheduledEvent.EventType.ALARM);
    }

    /**
     * Get all reminders.
     */
    @NonNull
    public synchronized List<ScheduledEvent> getReminders() {
        return getEventsByType(ScheduledEvent.EventType.REMINDER);
    }

    /**
     * Get count of enabled events.
     */
    public synchronized int getEnabledEventCount() {
        ensureInitialized();
        int count = 0;
        for (ScheduledEvent event : eventsCache.values()) {
            if (event.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the next scheduled event (earliest trigger time).
     * Only considers enabled events with future trigger times.
     *
     * @return The next event to trigger, or null if none
     */
    @Nullable
    public synchronized ScheduledEvent getNextScheduledEvent() {
        ensureInitialized();
        ScheduledEvent next = null;
        long nextTime = Long.MAX_VALUE;
        
        for (ScheduledEvent event : eventsCache.values()) {
            if (event.isEnabled()) {
                long triggerTime = event.getNextTriggerTime();
                if (triggerTime > 0 && triggerTime < nextTime) {
                    nextTime = triggerTime;
                    next = event;
                }
            }
        }
        return next;
    }

    /**
     * Update an existing event.
     */
    public synchronized boolean updateEvent(@NonNull ScheduledEvent event) {
        ensureInitialized();

        if (!eventsCache.containsKey(event.getId())) {
            Log.w(TAG, "Event with ID " + event.getId() + " not found, adding instead");
            return addEvent(event);
        }

        eventsCache.put(event.getId(), event);
        persistEvents();
        notifyEventUpdated(event);

        Log.d(TAG, "Updated event: " + event);
        return true;
    }

    /**
     * Set the enabled state of an event by ID.
     *
     * @param eventId The event ID
     * @param enabled The new enabled state
     * @return The updated event, or null if not found
     */
    @Nullable
    public synchronized ScheduledEvent setEventEnabled(@NonNull String eventId, boolean enabled) {
        ensureInitialized();

        ScheduledEvent event = eventsCache.get(eventId);
        if (event == null) {
            Log.w(TAG, "Event with ID " + eventId + " not found");
            return null;
        }

        event.setEnabled(enabled);
        persistEvents();
        notifyEventUpdated(event);

        Log.d(TAG, "Set event " + eventId + " enabled: " + enabled);
        return event;
    }

    /**
     * Remove an event by ID.
     *
     * @return true if removed
     */
    public synchronized boolean removeEvent(@NonNull String eventId) {
        ensureInitialized();

        ScheduledEvent removed = eventsCache.remove(eventId);
        if (removed != null) {
            persistEvents();
            notifyEventRemoved(eventId);
            Log.d(TAG, "Removed event: " + eventId);
            return true;
        }

        Log.w(TAG, "Event with ID " + eventId + " not found for removal");
        return false;
    }

    /**
     * Clear all events.
     */
    public synchronized void clearAllEvents() {
        ensureInitialized();

        eventsCache.clear();
        persistEvents();
        notifyEventsCleared();

        Log.d(TAG, "Cleared all events");
    }

    // =========================================
    // Synchronization Operations
    // =========================================

    /**
     * Synchronize events from a client device.
     * Replaces all existing events with the provided list.
     * 
     * @param events The complete list of events from the client
     * @return Number of events synchronized
     */
    public synchronized int syncEvents(@NonNull List<ScheduledEvent> events) {
        ensureInitialized();

        // Clear existing events
        eventsCache.clear();

        // Add all new events
        for (ScheduledEvent event : events) {
            eventsCache.put(event.getId(), event);
        }

        // Update sync timestamp
        lastSyncTimestamp = System.currentTimeMillis();
        persistEvents();
        
        // Notify listeners
        notifyEventsSynced(events);

        Log.i(TAG, "Synced " + events.size() + " events from client");
        return events.size();
    }

    /**
     * Import events from a JSON array (for TCP sync).
     * 
     * @param eventsArray JSON array of event objects
     * @return Number of events imported
     */
    public synchronized int importFromJson(@NonNull JSONArray eventsArray) throws JSONException {
        List<ScheduledEvent> events = new ArrayList<>();
        
        for (int i = 0; i < eventsArray.length(); i++) {
            JSONObject obj = eventsArray.getJSONObject(i);
            ScheduledEvent event = ScheduledEvent.fromJson(obj);
            events.add(event);
        }
        
        return syncEvents(events);
    }

    /**
     * Export all events as JSON array for transmission.
     */
    @NonNull
    public synchronized JSONArray exportToJson() throws JSONException {
        ensureInitialized();
        JSONArray array = new JSONArray();
        for (ScheduledEvent event : eventsCache.values()) {
            array.put(event.toJson());
        }
        return array;
    }

    /**
     * Get the timestamp of the last sync operation.
     */
    public long getLastSyncTimestamp() {
        return lastSyncTimestamp;
    }

    // =========================================
    // Query Methods
    // =========================================

    /**
     * Get the number of events.
     */
    public synchronized int getEventCount() {
        ensureInitialized();
        return eventsCache.size();
    }

    /**
     * Check if an event exists.
     */
    public synchronized boolean hasEvent(@NonNull String eventId) {
        ensureInitialized();
        return eventsCache.containsKey(eventId);
    }

    /**
     * Get the next event to trigger.
     * 
     * @return The next event, or null if none
     */
    @Nullable
    public synchronized ScheduledEvent getNextEvent() {
        ensureInitialized();
        
        ScheduledEvent next = null;
        long nextTriggerTime = Long.MAX_VALUE;
        
        for (ScheduledEvent event : eventsCache.values()) {
            if (!event.isEnabled()) continue;
            
            long triggerTime = event.getNextTriggerTime();
            if (triggerTime > 0 && triggerTime < nextTriggerTime) {
                nextTriggerTime = triggerTime;
                next = event;
            }
        }
        
        return next;
    }

    // =========================================
    // Listener Management
    // =========================================

    public void addListener(@NonNull EventChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@NonNull EventChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyEventAdded(ScheduledEvent event) {
        for (EventChangeListener listener : listeners) {
            try {
                listener.onEventAdded(event);
            } catch (Exception e) {
                Log.e(TAG, "Error in event listener", e);
            }
        }
    }

    private void notifyEventUpdated(ScheduledEvent event) {
        for (EventChangeListener listener : listeners) {
            try {
                listener.onEventUpdated(event);
            } catch (Exception e) {
                Log.e(TAG, "Error in event listener", e);
            }
        }
    }

    private void notifyEventRemoved(String eventId) {
        for (EventChangeListener listener : listeners) {
            try {
                listener.onEventRemoved(eventId);
            } catch (Exception e) {
                Log.e(TAG, "Error in event listener", e);
            }
        }
    }

    private void notifyEventsSynced(List<ScheduledEvent> events) {
        for (EventChangeListener listener : listeners) {
            try {
                listener.onEventsSynced(events);
            } catch (Exception e) {
                Log.e(TAG, "Error in event listener", e);
            }
        }
    }

    private void notifyEventsCleared() {
        for (EventChangeListener listener : listeners) {
            try {
                listener.onEventsCleared();
            } catch (Exception e) {
                Log.e(TAG, "Error in event listener", e);
            }
        }
    }

    // =========================================
    // Helper Methods
    // =========================================

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    /**
     * Reset the repository (for testing).
     */
    public synchronized void reset() {
        eventsCache.clear();
        lastSyncTimestamp = 0;
        initialized = false;
        prefs.edit().clear().apply();
        Log.d(TAG, "Repository reset");
    }
}
