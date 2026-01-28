package com.fufelshmertzpakostincorporated.kawaicare.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
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

    private ProgressBar progressBar;
    private ImageView ivCheck;
    private TextView tvStatus;
    private ConnectivityManager connectivityManager;

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
        // Mocking a slight delay to show the progress bar so user feels it "working"
        new Handler().postDelayed(() -> {
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
        }, 10000);
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

            // Wait 2 seconds then finish
            new Handler().postDelayed(() -> {
                setResult(Activity.RESULT_OK);
                finish();
            }, 1000);
        });
    }

    private void onFailure() {
        runOnUiThread(() -> {
            tvStatus.setText("No Phone Found");
            progressBar.setVisibility(View.GONE);
            
            // Wait 2 seconds then finish canceled
            new Handler().postDelayed(() -> {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }, 2000);
        });
    }
}
