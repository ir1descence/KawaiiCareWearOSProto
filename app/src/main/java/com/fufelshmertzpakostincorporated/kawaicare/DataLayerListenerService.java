package com.fufelshmertzpakostincorporated.kawaicare;

import android.util.Log;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "DataLayerListenerService";
    private static final String WEARABLE_DATA_PATH = "/alarm_status";
    private static final String KEY_ALARM_STATUS = "key_alarm_status";

    // Animation control from connected device
    private static final String WEARABLE_ANIM_PATH = "/animation_state";
    private static final String KEY_ANIM_STATE = "key_animation_state";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onDataChanged: " + dataEvents);
        }

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo(WEARABLE_DATA_PATH) == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String status = dataMap.getString(KEY_ALARM_STATUS);
                    
                    Log.d(TAG, "Alarm status received: " + status);
                    
                    if (status != null) {
                        boolean isAlarmOn = "ON".equalsIgnoreCase(status);
                        AlarmStatusRepository.getInstance().setAlarmStatus(isAlarmOn);
                    }
                }
                
                // Handle animation state updates from the connected device
                else if (item.getUri().getPath().compareTo(WEARABLE_ANIM_PATH) == 0) {
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
        }
    }
}
