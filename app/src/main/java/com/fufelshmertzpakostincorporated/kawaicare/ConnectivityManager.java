package com.fufelshmertzpakostincorporated.kawaicare;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.List;

/**
 * Modular Connectivity Manager.
 * Uses Google Wearable Data Layer API.
 */
public class ConnectivityManager {

    private final Context context;

    public interface ConnectionCallback {
        void onConnectionResult(boolean isConnected, String nodeName);
    }

    public ConnectivityManager(Context context) {
        this.context = context;
    }

    /**
     * Checks if a companion node is connected.
     * @param callback Result listener
     */
    public void checkConnectivity(final ConnectionCallback callback) {
        Task<List<Node>> nodeListTask = Wearable.getNodeClient(context).getConnectedNodes();

        nodeListTask.addOnCompleteListener(new OnCompleteListener<List<Node>>() {
            @Override
            public void onComplete(Task<List<Node>> task) {
                if (task.isSuccessful()) {
                    List<Node> nodes = task.getResult();
                    if (nodes != null && !nodes.isEmpty()) {
                        // In a real scenario, you might filter for a specific node type or capability
                        // Here, any connected node (usually the phone) counts as success.
                        Node node = nodes.get(0);
                        callback.onConnectionResult(true, node.getDisplayName());
                    } else {
                        callback.onConnectionResult(false, null);
                    }
                } else {
                    Log.e("ConnectivityManager", "Failed to get connected nodes", task.getException());
                    callback.onConnectionResult(false, null);
                }
            }
        });
    }
}
