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

import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationRenderer;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationStateRepository;
import com.fufelshmertzpakostincorporated.kawaicare.ui.MainActivity;
import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;

import java.util.ArrayList;

/**
 * Single WearableListenerService entry point.
 *
 * Consolidates both Data Layer (DATA_CHANGED) and Message API (MESSAGE_RECEIVED)
 * events and dispatches by path.
 */
public class WearEventsService extends WearableListenerService {

    private static final String TAG = "WearEventsService";

    // Data layer paths/keys
    private static final String PATH_ALARM_STATUS = "/alarm_status";
    private static final String KEY_ALARM_STATUS = "key_alarm_status";

    private static final String PATH_ANIMATION_STATE = "/animation_state";
    private static final String KEY_ANIM_STATE = "key_animation_state";

    // Available emotions list
    private static final String PATH_AVAILABLE_EMOTIONS = "/available_emotions";
    private static final String KEY_EMOTIONS_LIST = "key_emotions_list";

    // Message paths (from phone)
    public static final String PATH_START_RECORDING = "/start_custom_recording";
    public static final String PATH_STOP_RECORDING = "/stop_custom_recording";
    public static final String PATH_REQUEST_EMOTIONS = "/request_emotions";

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

        if (PATH_ALARM_STATUS.equals(path)) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            String status = dataMap.getString(KEY_ALARM_STATUS);

            Log.d(TAG, "Alarm status received: " + status);

            if (status != null) {
                boolean isAlarmOn = "ON".equalsIgnoreCase(status);
                AlarmStatusRepository.getInstance().setAlarmStatus(isAlarmOn);
            }
            return;
        }

        if (PATH_ANIMATION_STATE.equals(path)) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            String anim = dataMap.getString(KEY_ANIM_STATE);

            Log.d(TAG, "Animation state received: " + anim);

            if (anim != null) {
                try {
                    AnimationRenderer.AnimState state = AnimationRenderer.AnimState.valueOf(anim.toUpperCase());
                    AnimationStateRepository.getInstance().setState(state);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Unknown animation state received: " + anim);
                }
            }
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        Log.d(TAG, "Message received: " + path);

        switch (path) {
            case PATH_START_RECORDING:
                Log.d(TAG, "Starting learning mode via broadcast");
                broadcastToMainActivity(MainActivity.ACTION_START_LEARNING);
                break;

            case PATH_STOP_RECORDING:
                Log.d(TAG, "Stopping learning mode via broadcast");
                broadcastToMainActivity(MainActivity.ACTION_STOP_LEARNING);
                break;

            case PATH_REQUEST_EMOTIONS:
                Log.d(TAG, "Emotions list requested");
                sendAvailableEmotions();
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
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_AVAILABLE_EMOTIONS);
            DataMap dataMap = putDataMapReq.getDataMap();
            dataMap.putStringArrayList(KEY_EMOTIONS_LIST, emotions);
            dataMap.putLong("timestamp", System.currentTimeMillis()); // Force update

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

    @Override
    public void onCreate() {
        super.onCreate();
        // Send emotions list when service starts
        sendAvailableEmotions();
    }
}
