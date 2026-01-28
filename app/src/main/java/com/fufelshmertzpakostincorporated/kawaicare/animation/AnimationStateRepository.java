package com.fufelshmertzpakostincorporated.kawaicare.animation;

import java.util.ArrayList;
import java.util.List;

public class AnimationStateRepository {
    private static AnimationStateRepository instance;
    private AnimationRenderer.AnimState currentState = AnimationRenderer.AnimState.IDLE;
    private final List<AnimationStateListener> listeners = new ArrayList<>();

    public interface AnimationStateListener {
        void onAnimationStateChanged(AnimationRenderer.AnimState state);
    }

    private AnimationStateRepository() {}

    public static synchronized AnimationStateRepository getInstance() {
        if (instance == null) {
            instance = new AnimationStateRepository();
        }
        return instance;
    }

    public synchronized void setState(AnimationRenderer.AnimState state) {
        if (state == null) return;
        this.currentState = state;
        notifyListeners();
    }

    public synchronized AnimationRenderer.AnimState getState() {
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

    private void notifyListeners() {
        for (AnimationStateListener listener : listeners) {
            listener.onAnimationStateChanged(currentState);
        }
    }
}
