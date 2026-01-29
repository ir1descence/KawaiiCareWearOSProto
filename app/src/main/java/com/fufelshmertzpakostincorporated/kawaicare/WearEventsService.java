package com.fufelshmertzpakostincorporated.kawaicare;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationRenderer;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationStateRepository;
import com.fufelshmertzpakostincorporated.kawaicare.ui.MainActivity;
import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.wear.WearableConstants;

import java.util.ArrayList;

/**
 * Single WearableListenerService entry point.
 *
 * Consolidates both Data Layer (DATA_CHANGED) and Message API (MESSAGE_RECEIVED)
 * events and dispatches by path.
 */
public class WearEventsService extends WearableListenerService {

    private static final String TAG = "WearEventsService";

    // Signal Registry instance (lazy initialized)
    private SignalRegistry signalRegistry;

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onDataChanged: " + dataEvents);
        }

        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }
            handleDataItemChanged(event.getDataItem());
        }
    }

    private void handleDataItemChanged(DataItem item) {
        String path = item.getUri() != null ? item.getUri().getPath() : null;
        if (path == null) {
            return;
        }

        if (WearableConstants.PATH_ALARM_STATUS.equals(path)) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            String status = dataMap.getString(WearableConstants.KEY_ALARM_STATUS);

            Log.d(TAG, "Alarm status received: " + status);

            if (status != null) {
                boolean isAlarmOn = WearableConstants.ALARM_STATUS_ON.equalsIgnoreCase(status);
                AlarmStatusRepository.getInstance().setAlarmStatus(isAlarmOn);
            }
            return;
        }

        if (WearableConstants.PATH_ANIMATION_STATE.equals(path)) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            String anim = dataMap.getString(WearableConstants.KEY_ANIM_STATE);

            Log.d(TAG, "Animation state received: " + anim);

            if (anim != null) {
                try {
                    AnimationRenderer.AnimState state = AnimationRenderer.AnimState.valueOf(anim.toUpperCase());
                    AnimationStateRepository.getInstance().setState(state);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Unknown animation state received: " + anim);
                }
            }
            return;
        }

        if (WearableConstants.PATH_ACTIVE_ALARM_GESTURE.equals(path)) {
            handleActiveAlarmGestureChange(item);
            return;
        }
    }

    /**
     * Handle changes to the active alarm gesture/signal from the phone.
     * Updates the AlarmStatusRepository with the new active stop signal.
     */
    private void handleActiveAlarmGestureChange(DataItem item) {
        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
        String signal = dataMap.getString(WearableConstants.KEY_ACTIVE_SIGNAL);

        Log.d(TAG, "Active alarm gesture received: " + signal);

        if (signal == null || signal.isEmpty()) {
            Log.w(TAG, "Received empty signal, ignoring");
            return;
        }

        // Initialize signal registry if needed
        if (signalRegistry == null) {
            signalRegistry = new SignalRegistry(this);
        }

        // Validate the signal
        SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(signal);
        
        if (!validation.isValid()) {
            Log.w(TAG, "Signal validation failed: " + validation.getErrorCode() 
                    + " - " + validation.getErrorMessage());
            // Send error response back to phone
            sendSignalValidationError(signal, validation);
            return;
        }

        // Handle custom gesture mapping
        String customGesturePath = null;
        if (SignalRegistry.SIGNAL_CUSTOM.equals(signal)) {
            customGesturePath = signalRegistry.getCustomGestureFilePath();
            Log.d(TAG, "Custom signal selected, gesture file: " + customGesturePath);
        }

        // Update the repository with the new active signal
        AlarmStatusRepository.getInstance().setActiveStopSignal(signal, customGesturePath);
        
        Log.d(TAG, "Active stop signal updated to: " + signal);
    }

    /**
     * Send a validation error response back to the phone when a signal is not supported.
     */
    private void sendSignalValidationError(String signal, SignalRegistry.ValidationResult validation) {
        try {
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/signal_validation_error");
            DataMap dataMap = putDataMapReq.getDataMap();
            dataMap.putString("requested_signal", signal);
            dataMap.putString("error_code", validation.getErrorCode());
            dataMap.putString("error_message", validation.getErrorMessage());
            dataMap.putLong("timestamp", System.currentTimeMillis());

            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            putDataReq.setUrgent();

            Task<DataItem> putDataTask = Wearable.getDataClient(this).putDataItem(putDataReq);
            putDataTask.addOnSuccessListener(dataItem -> 
                Log.d(TAG, "Signal validation error sent successfully")
            ).addOnFailureListener(e -> 
                Log.e(TAG, "Failed to send signal validation error", e)
            );
        } catch (Exception e) {
            Log.e(TAG, "Error sending signal validation error", e);
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        Log.d(TAG, "Message received: " + path);

        switch (path) {
            case WearableConstants.PATH_START_RECORDING:
                Log.d(TAG, "Starting learning mode via broadcast");
                broadcastToMainActivity(MainActivity.ACTION_START_LEARNING);
                break;

            case WearableConstants.PATH_STOP_RECORDING:
                Log.d(TAG, "Stopping learning mode via broadcast");
                broadcastToMainActivity(MainActivity.ACTION_STOP_LEARNING);
                break;

            case WearableConstants.PATH_REQUEST_EMOTIONS:
                Log.d(TAG, "Emotions list requested");
                sendAvailableEmotions();
                break;

            case WearableConstants.PATH_REQUEST_SUPPORTED_SIGNALS:
                Log.d(TAG, "Supported signals requested");
                sendSupportedSignals();
                break;

            default:
                Log.d(TAG, "Unknown message path: " + path);
                break;
        }
    }

    private void broadcastToMainActivity(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * Sends the list of available emotions/animations to connected devices.
     * This reads the available folders from assets and sends them.
     */
    private void sendAvailableEmotions() {
        try {
            // Get available animation folders from assets
            String[] folders = getAssets().list("");
            ArrayList<String> emotions = new ArrayList<>();
            
            if (folders != null) {
                // Filter to only include known emotion folders
                for (String folder : folders) {
                    // Check if folder contains images
                    String[] files = getAssets().list(folder);
                    if (files != null && files.length > 0) {
                        boolean hasImages = false;
                        for (String file : files) {
                            String lower = file.toLowerCase();
                            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                                hasImages = true;
                                break;
                            }
                        }
                        if (hasImages) {
                            emotions.add(folder);
                        }
                    }
                }
            }

            Log.d(TAG, "Sending emotions list: " + emotions);

            // Create DataMap with emotions list
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearableConstants.PATH_AVAILABLE_EMOTIONS);
            DataMap dataMap = putDataMapReq.getDataMap();
            dataMap.putStringArrayList(WearableConstants.KEY_EMOTIONS_LIST, emotions);
            dataMap.putLong(WearableConstants.KEY_TIMESTAMP, System.currentTimeMillis()); // Force update

            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            putDataReq.setUrgent();

            Task<DataItem> putDataTask = Wearable.getDataClient(this).putDataItem(putDataReq);
            putDataTask.addOnSuccessListener(dataItem -> 
                Log.d(TAG, "Emotions list sent successfully")
            ).addOnFailureListener(e -> 
                Log.e(TAG, "Failed to send emotions list", e)
            );

        } catch (Exception e) {
            Log.e(TAG, "Error reading emotions from assets", e);
        }
    }

    /**
     * Sends the list of supported alarm-stop signals to connected devices.
     * Performs hardware validation and only sends signals that the device can support.
     * If hardware is missing, sends an error response instead.
     */
    private void sendSupportedSignals() {
        try {
            // Initialize signal registry if needed
            if (signalRegistry == null) {
                signalRegistry = new SignalRegistry(this);
            }

            // Get supported signals (with hardware validation)
            java.util.List<String> supportedSignals = signalRegistry.getSupportedSignals();
            
            Log.d(TAG, "Sending supported signals: " + supportedSignals);

            // Create DataMap with signals list
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearableConstants.PATH_SUPPORTED_SIGNALS);
            DataMap dataMap = putDataMapReq.getDataMap();
            
            // Send as ArrayList for DataLayer compatibility
            ArrayList<String> signalsList = new ArrayList<>(supportedSignals);
            dataMap.putStringArrayList(WearableConstants.KEY_SIGNALS_LIST, signalsList);
            
            // Also send as JSON for more detailed response
            String jsonResponse = signalRegistry.getSupportedSignalsAsJson();
            dataMap.putString(WearableConstants.KEY_SIGNALS_JSON, jsonResponse);
            
            dataMap.putLong(WearableConstants.KEY_TIMESTAMP, System.currentTimeMillis()); // Force update

            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            putDataReq.setUrgent();

            Task<DataItem> putDataTask = Wearable.getDataClient(this).putDataItem(putDataReq);
            putDataTask.addOnSuccessListener(dataItem -> 
                Log.d(TAG, "Supported signals sent successfully")
            ).addOnFailureListener(e -> 
                Log.e(TAG, "Failed to send supported signals", e)
            );

        } catch (Exception e) {
            Log.e(TAG, "Error sending supported signals", e);
            sendSignalRegistryError(e.getMessage());
        }
    }

    /**
     * Send an error response when the signal registry encounters an error.
     */
    private void sendSignalRegistryError(String errorMessage) {
        try {
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearableConstants.PATH_SUPPORTED_SIGNALS);
            DataMap dataMap = putDataMapReq.getDataMap();
            dataMap.putString("status", "error");
            dataMap.putString("error_message", errorMessage != null ? errorMessage : "Unknown error");
            dataMap.putLong(WearableConstants.KEY_TIMESTAMP, System.currentTimeMillis());

            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            putDataReq.setUrgent();

            Wearable.getDataClient(this).putDataItem(putDataReq);
        } catch (Exception e) {
            Log.e(TAG, "Error sending signal registry error", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize signal registry
        signalRegistry = new SignalRegistry(this);
        // Send emotions list when service starts
        sendAvailableEmotions();
    }
}
