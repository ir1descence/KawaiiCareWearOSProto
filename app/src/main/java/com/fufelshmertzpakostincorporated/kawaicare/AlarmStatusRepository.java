package com.fufelshmertzpakostincorporated.kawaicare;

import java.util.ArrayList;
import java.util.List;

public class AlarmStatusRepository {
    private static AlarmStatusRepository instance;
    private boolean isAlarmOn = false;
    private final List<AlarmStatusListener> listeners = new ArrayList<>();

    public interface AlarmStatusListener {
        void onAlarmStatusChanged(boolean isAlarmOn);
    }

    private AlarmStatusRepository() {}

    public static synchronized AlarmStatusRepository getInstance() {
        if (instance == null) {
            instance = new AlarmStatusRepository();
        }
        return instance;
    }

    public void setAlarmStatus(boolean isAlarmOn) {
        this.isAlarmOn = isAlarmOn;
        notifyListeners();
    }

    public boolean isAlarmOn() {
        return isAlarmOn;
    }

    public void addListener(AlarmStatusListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(AlarmStatusListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (AlarmStatusListener listener : listeners) {
            listener.onAlarmStatusChanged(isAlarmOn);
        }
    }
}
