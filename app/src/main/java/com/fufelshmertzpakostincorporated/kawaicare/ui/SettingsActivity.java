package com.fufelshmertzpakostincorporated.kawaicare.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.R;

/**
 * Settings Activity.
 * Handles Pairing and Alarm status visualization.
 */
public class SettingsActivity extends Activity implements AlarmStatusRepository.AlarmStatusListener {

    private static final int REQUEST_CODE_PAIRING = 1001;

    private ImageButton btnPairing;
    private ImageButton btnAlarm;

    // Mock State for demonstration
    private boolean isConnected = false; 
    private boolean isAlarmSet = true; // Example: Alarm is set

    // State Management Interface
    public interface UIState {
        boolean isPairingConnected();
        boolean isAlarmActive();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        btnPairing = findViewById(R.id.btnPairing);
        btnAlarm = findViewById(R.id.btnAlarm);

        // Initial State
        refreshUI();

        // 1. Pairing Button Logic
        btnPairing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Launch Loading Activity to check connection
                Intent intent = new Intent(SettingsActivity.this, LoadingActivity.class);
                startActivityForResult(intent, REQUEST_CODE_PAIRING);
            }
        });
        
        // 2. Alarm Button Logic (Read Only per requirements)
        // It is already android:enabled="false" in XML.
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PAIRING) {
            if (resultCode == Activity.RESULT_OK) {
                isConnected = true;
                Toast.makeText(this, "Device Connected!", Toast.LENGTH_SHORT).show();
            } else {
                isConnected = false;
                Toast.makeText(this, "Connection Failed", Toast.LENGTH_SHORT).show();
            }
            refreshUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmStatusRepository.getInstance().addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        AlarmStatusRepository.getInstance().removeListener(this);
    }

    @Override
    public void onAlarmStatusChanged(boolean isAlarmOn) {
        this.isAlarmSet = isAlarmOn;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshUI();
            }
        });
    }

    /**
     * Updates the UI based on the current state.
     * Toggles Pairing button and Tints Alarm button.
     */
    private void refreshUI() {
        UIState currentState = new UIState() {
            @Override
            public boolean isPairingConnected() {
                return isConnected;
            }

            @Override
            public boolean isAlarmActive() {
                return isAlarmSet;
            }
        };

        applyState(currentState);
    }

    private void applyState(UIState state) {
        // Pairing Button Visuals
        if (state.isPairingConnected()) {
            btnPairing.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN);
            btnPairing.setContentDescription("Connected");
        } else {
            btnPairing.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN); // Or Red/Gray? Defaulting to White for "Disconnected/Ready"
            btnPairing.setContentDescription("Disconnected");
        }

        // Alarm Button Visuals
        if (state.isAlarmActive()) {
            btnAlarm.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        } else {
            btnAlarm.setColorFilter(Color.DKGRAY, PorterDuff.Mode.SRC_IN);
        }
    }
}
