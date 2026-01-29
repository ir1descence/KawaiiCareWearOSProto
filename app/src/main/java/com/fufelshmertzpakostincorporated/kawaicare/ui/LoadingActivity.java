package com.fufelshmertzpakostincorporated.kawaicare.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fufelshmertzpakostincorporated.kawaicare.wear.ConnectivityManager;
import com.fufelshmertzpakostincorporated.kawaicare.R;

/**
 * Loading Activity.
 * Handles Connectivity Checks and Animations.
 */
public class LoadingActivity extends Activity {

    // Timing constants
    private static final long DISCOVERY_DELAY_MS = 10_000;
    private static final long SUCCESS_DISPLAY_MS = 1_000;
    private static final long FAILURE_DISPLAY_MS = 2_000;

    private ProgressBar progressBar;
    private ImageView ivCheck;
    private TextView tvStatus;
    private ConnectivityManager connectivityManager;

    // Handler for scheduled tasks - prevents memory leaks
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable discoveryRunnable;
    private Runnable finishRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        progressBar = findViewById(R.id.progressBar);
        ivCheck = findViewById(R.id.ivCheck);
        tvStatus = findViewById(R.id.tvStatus);

        connectivityManager = new ConnectivityManager(this);

        startDiscovery();
    }

    private void startDiscovery() {
        // Using member handler to prevent memory leaks
        discoveryRunnable = () -> {
            connectivityManager.checkConnectivity(new ConnectivityManager.ConnectionCallback() {
                @Override
                public void onConnectionResult(boolean isConnected, String nodeName) {
                    if (isConnected) {
                        onSuccess(nodeName);
                    } else {
                        onFailure();
                    }
                }
            });
        };
        handler.postDelayed(discoveryRunnable, DISCOVERY_DELAY_MS);
    }

    private void onSuccess(String nodeName) {
        // UI Updates on Main Thread
        runOnUiThread(() -> {
            tvStatus.setText("Connected to\n" + nodeName);
            progressBar.setVisibility(View.GONE);
            ivCheck.setVisibility(View.VISIBLE);

            Drawable drawable = ivCheck.getDrawable();
            if (drawable instanceof Animatable) {
                ((Animatable) drawable).start();
            }

            // Schedule finish with delay
            finishRunnable = () -> {
                setResult(Activity.RESULT_OK);
                finish();
            };
            handler.postDelayed(finishRunnable, SUCCESS_DISPLAY_MS);
        });
    }

    private void onFailure() {
        runOnUiThread(() -> {
            tvStatus.setText("No Phone Found");
            progressBar.setVisibility(View.GONE);
            
            // Schedule finish with delay
            finishRunnable = () -> {
                setResult(Activity.RESULT_CANCELED);
                finish();
            };
            handler.postDelayed(finishRunnable, FAILURE_DISPLAY_MS);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up scheduled callbacks to prevent memory leaks
        if (discoveryRunnable != null) {
            handler.removeCallbacks(discoveryRunnable);
        }
        if (finishRunnable != null) {
            handler.removeCallbacks(finishRunnable);
        }
    }
}
