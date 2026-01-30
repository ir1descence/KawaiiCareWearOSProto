package com.fufelshmertzpakostincorporated.kawaicare.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.Configuration;

import com.fufelshmertzpakostincorporated.kawaicare.R;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.AlarmManagerUtils;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.GestureMatcher;
import com.fufelshmertzpakostincorporated.kawaicare.auth.SessionManager;
import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.network.TcpWearService;
import com.fufelshmertzpakostincorporated.kawaicare.sensor.SensorController;
import com.fufelshmertzpakostincorporated.kawaicare.recording.GestureRecordingController;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationRenderer;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationStateRepository;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

/**
 * Main Activity / Watch Face Home Launcher.
 * Coordinates Sensor Input, User Gestures, Learning Mode, Alarm Handling, and the Animation State Engine.
 * Implements authentication checking and guest mode support.
 */
public class MainActivity extends Activity implements
        SensorController.SensorStateListener,
        AnimationStateRepository.AnimationStateListener,
        AnimationStateRepository.AnimationDurationProvider,
        GestureRecordingController.RecordingListener,
        AlarmStatusRepository.AlarmStatusListener,
        AlarmManagerUtils.AlarmListener,
        GestureMatcher.GestureMatchListener,
        MessageClient.OnMessageReceivedListener {

    private static final String TAG = "MainActivity";

    // Broadcast actions for Learning Mode
    public static final String ACTION_START_LEARNING = "com.fufelshmertzpakostincorporated.kawaicare.START_LEARNING";
    public static final String ACTION_STOP_LEARNING = "com.fufelshmertzpakostincorporated.kawaicare.STOP_LEARNING";

    // Broadcast actions for Event System
    public static final String ACTION_EVENT_TRIGGERED = "com.fufelshmertzpakostincorporated.kawaicare.EVENT_TRIGGERED";
    public static final String ACTION_EVENT_DISMISSED = "com.fufelshmertzpakostincorporated.kawaicare.EVENT_DISMISSED";

    // Wearable Data Layer paths
    private static final String MESSAGE_PATH_GESTURE_SYNC = "/gesture_sync";
    private static final String MESSAGE_PATH_AUTH_UPDATE = "/auth_update";

    // Controllers
    private SensorController sensorController;
    private GestureRecordingController gestureRecordingController;
    private AnimationRenderer animationRenderer;
    private GestureDetector gestureDetector;
    private SessionManager sessionManager;

    // Alarm components
    private AlarmManagerUtils alarmManagerUtils;
    private GestureMatcher gestureMatcher;
    private boolean isAlarmActive = false;

    // Views
    private ImageView imageView;

    // Auth State
    private boolean isGuestMode = true;
    
    // Animation Control Flags
    private boolean isShakeAnimating = false;
    private String currentAnimationFolder = null; // Track current folder to prevent redundant loads
    private volatile boolean isExternalAnimationActive = false; // Blocks sensor input during TCP animations

    // Learning Mode state
    private AnimationRenderer.AnimState stateBeforeLearning;

    // Pairing dialog
    private AlertDialog pairingDialog = null;

    // Broadcast receiver for learning mode commands and event system
    private final BroadcastReceiver learningModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            switch (intent.getAction()) {
                case ACTION_START_LEARNING:
                    startLearningMode();
                    break;
                case ACTION_STOP_LEARNING:
                    stopLearningMode();
                    break;
                case ACTION_EVENT_TRIGGERED:
                    String triggeredEventId = intent.getStringExtra("event_id");
                    String eventType = intent.getStringExtra("event_type");
                    Log.d(TAG, "Event triggered received: id=" + triggeredEventId + ", type=" + eventType);
                    handleAlarmActivation();
                    break;
                case ACTION_EVENT_DISMISSED:
                    String dismissedEventId = intent.getStringExtra("event_id");
                    String dismissedBy = intent.getStringExtra("dismissed_by");
                    Log.d(TAG, "Event dismissed received: id=" + dismissedEventId + ", by=" + dismissedBy);
                    handleAlarmDeactivation();
                    break;
            }
        }
    };

    // Broadcast receiver for TCP pairing events
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            switch (intent.getAction()) {
                case TcpWearService.ACTION_SHOW_PAIRING_CODE:
                    String code = intent.getStringExtra(TcpWearService.EXTRA_PAIRING_CODE);
                    if (code != null) {
                        showPairingCodeDialog(code);
                    }
                    break;
                case TcpWearService.ACTION_DISMISS_PAIRING_CODE:
                    dismissPairingCodeDialog();
                    break;
                case TcpWearService.ACTION_PAIRING_COMPLETE:
                    onPairingComplete();
                    break;
                case TcpWearService.ACTION_REMOTE_LOGOUT:
                    onRemoteLogout();
                    break;
                case TcpWearService.ACTION_CONNECTIVITY_DIAGNOSTIC:
                    String diagnosticInfo = intent.getStringExtra(TcpWearService.EXTRA_DIAGNOSTIC_INFO);
                    if (diagnosticInfo != null) {
                        showConnectivityDiagnostic(diagnosticInfo);
                    }
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 0. Initialize Session Manager and check auth state
        sessionManager = SessionManager.getInstance(this);
        checkAuthState();

        setContentView(R.layout.activity_main);

        // 1. Setup Full Screen View
        imageView = findViewById(R.id.animation_image_view);

        // 2. Initialize Animation Subsystem
        animationRenderer = new AnimationRenderer(this, imageView);
        loadAssets();

        // 3. Initialize Sensor Controller (ALWAYS active - works in guest mode)
        sensorController = new SensorController(this);
        sensorController.setListener(this);

        // 4. Initialize Gesture Recording Controller
        gestureRecordingController = new GestureRecordingController(this);
        gestureRecordingController.setListener(this);

        // 5. Initialize Gesture Detector
        gestureDetector = new GestureDetector(this, new GestureListener());

        // 6. Initialize Alarm Components
        alarmManagerUtils = new AlarmManagerUtils(this);
        alarmManagerUtils.setListener(this);
        
        gestureMatcher = new GestureMatcher(this);
        gestureMatcher.setListener(this);

        // 7. Set screen dimensions once layout is complete
        imageView.post(() -> {
            gestureRecordingController.setScreenDimensions(imageView.getWidth(), imageView.getHeight());
            gestureMatcher.setScreenDimensions(imageView.getWidth(), imageView.getHeight());
        });

        // 8. Show guest mode indicator if not authenticated
        if (isGuestMode) {
            showGuestModeNotification();
        }

        // 9. Start TCP Wear Service for network communication
        startTcpWearService();
    }

    /**
     * Start the TCP Wear Service for smartphone communication.
     * The service runs as a foreground service to ensure it stays alive.
     */
    private void startTcpWearService() {
        Intent serviceIntent = new Intent(this, TcpWearService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.i(TAG, "TcpWearService started");
    }

    /**
     * Check authentication state and configure app accordingly.
     */
    private void checkAuthState() {
        boolean isAuthenticated = sessionManager.isAuthenticated();
        isGuestMode = !isAuthenticated;

        if (isAuthenticated) {
            Log.i(TAG, "User authenticated - enabling Wearable Data Layer");
            // Wearable listeners will be enabled in onResume
        } else {
            Log.i(TAG, "Guest mode - Wearable Data Layer disabled");
        }
    }

    /**
     * Show a subtle notification that the app is in guest mode.
     */
    private void showGuestModeNotification() {
        Toast.makeText(this, "Guest Mode - Pair with phone for full features", Toast.LENGTH_SHORT).show();
    }

    // =========================================
    // TCP Pairing Dialog Methods
    // =========================================

    /**
     * Show a dialog displaying the 6-digit pairing code.
     * The user must read this code and enter it on the connecting device.
     */
    private void showPairingCodeDialog(String code) {
        // Dismiss any existing dialog first
        dismissPairingCodeDialog();

        // Create a custom layout for the pairing code display
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 48, 32, 48);
        layout.setBackgroundColor(Color.parseColor("#1A1A2E"));

        // Title
        TextView titleView = new TextView(this);
        titleView.setText("Pairing Code");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(Color.WHITE);
        titleView.setGravity(Gravity.CENTER);
        layout.addView(titleView);

        // Code display with spacing between digits
        TextView codeView = new TextView(this);
        StringBuilder formattedCode = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            formattedCode.append(code.charAt(i));
            if (i < code.length() - 1) {
                formattedCode.append("  "); // Add spacing between digits
            }
        }
        codeView.setText(formattedCode.toString());
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        codeView.setTextColor(Color.parseColor("#00FF88"));
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 24, 0, 24);
        layout.addView(codeView);

        // Instructions
        TextView instructionView = new TextView(this);
        instructionView.setText("Enter this code on your phone");
        instructionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        instructionView.setTextColor(Color.LTGRAY);
        instructionView.setGravity(Gravity.CENTER);
        layout.addView(instructionView);

        // Build and show dialog
        pairingDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(layout)
                .setCancelable(false)
                .create();

        // Make dialog non-dismissable
        pairingDialog.setCanceledOnTouchOutside(false);

        // Show the dialog
        if (!isFinishing()) {
            pairingDialog.show();
        }

        Log.d(TAG, "Pairing code dialog shown: " + code);
    }

    /**
     * Dismiss the pairing code dialog if it's showing.
     */
    private void dismissPairingCodeDialog() {
        if (pairingDialog != null && pairingDialog.isShowing()) {
            pairingDialog.dismiss();
            pairingDialog = null;
            Log.d(TAG, "Pairing code dialog dismissed");
        }
    }

    /**
     * Called when pairing completes successfully.
     * Updates the auth state and enables full features.
     */
    private void onPairingComplete() {
        Log.i(TAG, "Pairing completed successfully");
        
        // Update auth state
        isGuestMode = false;
        
        // Show success message
        Toast.makeText(this, "Paired successfully!", Toast.LENGTH_SHORT).show();
        
        // Enable Wearable Data Layer now that we're authenticated
        enableWearableListeners();
    }

    /**
     * Called when the device is logged out remotely.
     * Reverts to guest mode.
     */
    private void onRemoteLogout() {
        Log.i(TAG, "Remote logout received");
        
        // Update auth state
        isGuestMode = true;
        
        // Disable Wearable Data Layer
        disableWearableListeners();
        
        // Show notification
        Toast.makeText(this, "Logged out by remote device", Toast.LENGTH_SHORT).show();
        
        // Show guest mode notification
        showGuestModeNotification();
    }

    /**
     * Show connectivity diagnostic information in a dialog.
     * Used for debugging NSD discovery issues.
     */
    private void showConnectivityDiagnostic(String diagnosticInfo) {
        Log.i(TAG, "Showing connectivity diagnostic");
        
        // Create a scrollable text view for the diagnostic
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setPadding(16, 16, 16, 16);
        
        TextView textView = new TextView(this);
        textView.setText(diagnosticInfo);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        textView.setTextColor(Color.WHITE);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setBackgroundColor(Color.parseColor("#1A1A2E"));
        textView.setPadding(16, 16, 16, 16);
        
        scrollView.addView(textView);
        
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Network Diagnostic")
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .create();
        
        if (!isFinishing()) {
            dialog.show();
        }
    }

    /**
     * Trigger a connectivity diagnostic.
     * Call this method to debug NSD discovery issues.
     */
    public void triggerConnectivityDiagnostic() {
        Intent intent = new Intent(this, TcpWearService.class);
        intent.setAction("com.fufelshmertzpakostincorporated.kawaicare.RUN_DIAGNOSTIC");
        startService(intent);
    }

    private void loadAssets() {
        // Initialize with default animation
        animationRenderer.setFolderAnimation("wink_1", true);
        animationRenderer.setState(AnimationRenderer.AnimState.IDLE);
    }

    // Add this helper method to switch animations based on state
    private void setAnimationForState(AnimationRenderer.AnimState state) {
        String folderPath = getFolderPathForState(state);
        
        // Optimization: Only reload animation if folder actually changes
        // This prevents redundant bitmap decoding which causes CPU spikes
        if (!folderPath.equals(currentAnimationFolder)) {
            currentAnimationFolder = folderPath;
            animationRenderer.setFolderAnimation(folderPath, true);
        }
        animationRenderer.setState(state);
    }

    // --- Lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        
        // Sensor controller ALWAYS active (works in guest mode for local interactions)
        sensorController.start();
        animationRenderer.start();
        AnimationStateRepository.getInstance().addListener(this);
        AnimationStateRepository.getInstance().setDurationProvider(this);

        // Subscribe to Alarm Status Repository
        AlarmStatusRepository.getInstance().addListener(this);

        // Register learning mode receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_LEARNING);
        filter.addAction(ACTION_STOP_LEARNING);
        filter.addAction(ACTION_EVENT_TRIGGERED);
        filter.addAction(ACTION_EVENT_DISMISSED);
        registerReceiver(learningModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // Register TCP pairing receiver
        IntentFilter pairingFilter = new IntentFilter();
        pairingFilter.addAction(TcpWearService.ACTION_SHOW_PAIRING_CODE);
        pairingFilter.addAction(TcpWearService.ACTION_DISMISS_PAIRING_CODE);
        pairingFilter.addAction(TcpWearService.ACTION_PAIRING_COMPLETE);
        pairingFilter.addAction(TcpWearService.ACTION_REMOTE_LOGOUT);
        pairingFilter.addAction(TcpWearService.ACTION_CONNECTIVITY_DIAGNOSTIC);
        registerReceiver(pairingReceiver, pairingFilter, Context.RECEIVER_NOT_EXPORTED);

        // Enable Wearable Data Layer ONLY if authenticated
        if (!isGuestMode) {
            enableWearableListeners();
        }

        // Check if alarm was already on when activity resumed
        if (AlarmStatusRepository.getInstance().isAlarmOn() && !isAlarmActive) {
            handleAlarmActivation();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "Configuration changed: orientation=" + newConfig.orientation + " uiMode=" + newConfig.uiMode);

        // Update layout-dependent controllers with latest view measurements
        if (imageView != null) {
            imageView.post(() -> {
                // Ensure the avatar remains visually upright — counter any system rotation
                imageView.setRotation(0f);

                gestureRecordingController.setScreenDimensions(imageView.getWidth(), imageView.getHeight());
                gestureMatcher.setScreenDimensions(imageView.getWidth(), imageView.getHeight());
                // Force animation engine to refresh frames for new size without restarting the Activity
                animationRenderer.setState(animationRenderer.getCurrentState());
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorController.stop();
        animationRenderer.stop();
        AnimationStateRepository.getInstance().removeListener(this);
        AlarmStatusRepository.getInstance().removeListener(this);

        // Disable Wearable listeners if they were enabled
        if (!isGuestMode) {
            disableWearableListeners();
        }

        // Unregister receiver
        try {
            unregisterReceiver(learningModeReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        // Unregister pairing receiver
        try {
            unregisterReceiver(pairingReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        // Dismiss pairing dialog if showing
        dismissPairingCodeDialog();

        // Stop recording if active
        if (gestureRecordingController.isRecording()) {
            gestureRecordingController.stopRecording(false);
        }

        // Note: Don't stop alarm on pause - it should continue in background
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gestureRecordingController.release();
        gestureMatcher.release();
        alarmManagerUtils.release();
        animationRenderer.release();
    }

    // --- Touch Handling ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Alarm mode: delegate to gesture matcher
        if (isAlarmActive && gestureMatcher.isMatching()) {
            boolean gestureMatched = gestureMatcher.onTouchEvent(event);
            // Gesture matching will call onGestureMatched() if successful
            return true;
        }

        // Learning mode: delegate to gesture recording controller
        if (gestureRecordingController.isRecording()) {
            boolean shouldStop = gestureRecordingController.onTouchEvent(event);
            if (shouldStop) {
                gestureRecordingController.stopRecording(true);
            }
            return true;
        }

        // Normal mode: use gesture detector
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // --- Gesture Listener ---

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Block if external animation is playing
            if (isExternalAnimationActive) return true;
            
            AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.GESTURE_ACTION);

            // Revert to IDLE after 2 seconds
            imageView.postDelayed(() -> {
                // Only revert if still in GESTURE_ACTION (not overridden by TCP or sensor)
                if (!isExternalAnimationActive && 
                    animationRenderer.getCurrentState() == AnimationRenderer.AnimState.GESTURE_ACTION) {
                    AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.IDLE);
                }
            }, 2000);

            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        }
    }

    // --- SensorController.SensorStateListener ---

    @Override
    public void onTiltDetected(float zAxisValue) {
        if (gestureRecordingController.isRecording()) return;
        if (isShakeAnimating) return; // Ignore tilt during shake animation
        if (isExternalAnimationActive) return; // Ignore during TCP animation

        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.IDLE) {
            // Use setState directly on AnimationRenderer for local changes
            // AnimationStateRepository.setState() will be blocked during external animations anyway
            AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.TILTED);
        }
    }

    @Override
    public void onStable() {
        if (gestureRecordingController.isRecording()) return;
        if (isShakeAnimating) return; // Ignore stable during shake animation
        if (isExternalAnimationActive) return; // Ignore during TCP animation

        if (animationRenderer.getCurrentState() == AnimationRenderer.AnimState.TILTED) {
            AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.IDLE);
        }
    }

    @Override
    public void onShakeStarted() {
        if (gestureRecordingController.isRecording()) return;
        if (isExternalAnimationActive) return; // Ignore during TCP animation
        
        Log.d(TAG, "Shake STARTED - triggering shake animation");

        // If alarm is active and no custom gesture, shake stops the alarm
        if (isAlarmActive && !gestureMatcher.hasCustomGesture()) {
            Log.d(TAG, "Shake detected during alarm - stopping alarm");
            dismissAlarm();
            return;
        }

        // Start shake animation
        isShakeAnimating = true;
        AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.SHAKE);
    }
    
    @Override
    public void onShakeContinuing() {
        // Shake is still ongoing - animation continues
        // No action needed, just keep the shake animation playing
        Log.v(TAG, "Shake continuing...");
    }
    
    @Override
    public void onShakeEnded() {
        if (!isShakeAnimating) return;
        if (isExternalAnimationActive) return; // Don't interfere with TCP animation
        
        Log.d(TAG, "Shake ENDED - returning to appropriate state");
        isShakeAnimating = false;
        
        // Return to state based on current sensor reading
        if (sensorController.isTilted()) {
            AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.TILTED);
        } else {
            AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.IDLE);
        }
    }

    // --- AnimationStateRepository.AnimationStateListener ---

    @Override
    public void onAnimationStateChanged(AnimationRenderer.AnimState state) {
        Log.d(TAG, "Animation state changed to: " + state);
        if (imageView != null) {
            imageView.post(() -> setAnimationForState(state));
        }
    }

    @Override
    public void onExternalAnimationStarted() {
        Log.i(TAG, "External (TCP) animation started - blocking sensor input");
        isExternalAnimationActive = true;
        // Clear shake state if we were mid-shake
        isShakeAnimating = false;
    }

    @Override
    public void onExternalAnimationEnded() {
        Log.i(TAG, "External (TCP) animation ended - re-enabling sensor input");
        isExternalAnimationActive = false;
        
        // Sync with current sensor state after external animation ends
        // This ensures we don't get stuck in wrong state
        if (sensorController != null && !gestureRecordingController.isRecording()) {
            if (sensorController.isShaking()) {
                // Unlikely but handle edge case
                isShakeAnimating = true;
            } else if (sensorController.isTilted()) {
                // Sensor reports tilt, but we just returned to IDLE
                // Let the next sensor event handle it naturally
            }
        }
    }

    // --- AnimationStateRepository.AnimationDurationProvider ---

    @Override
    public long getDurationForState(AnimationRenderer.AnimState state) {
        // Map state to folder path (same mapping as setAnimationForState)
        String folderPath = getFolderPathForState(state);
        
        // Calculate duration based on frame count
        if (animationRenderer != null) {
            long duration = animationRenderer.calculateFolderDuration(folderPath);
            Log.d(TAG, "Calculated duration for " + state + " (" + folderPath + "): " + duration + "ms");
            return duration;
        }
        
        return 0; // Will use default fallback
    }
    
    /**
     * Get the asset folder path for a given animation state.
     * Centralizes the state-to-folder mapping.
     */
    private String getFolderPathForState(AnimationRenderer.AnimState state) {
        switch (state) {
            case IDLE:
                return "wink_2";
            case TILTED:
                return "turn_left";
            case GESTURE_ACTION:
                return "nods";
            case SHAKE:
                return "shake";
            case ALARM:
                return "notice";
            case LEARNING:
                return "turn_right";
            default:
                return "wink_1";
        }
    }

    // --- GestureRecordingController.RecordingListener ---

    @Override
    public void onRecordingStarted() {
        // Recording started - animation already set to LEARNING
    }

    @Override
    public void onRecordingStopped(boolean success) {
        // Restore previous animation state
        AnimationRenderer.AnimState restoreState = (stateBeforeLearning != null)
                ? stateBeforeLearning
                : AnimationRenderer.AnimState.IDLE;
        AnimationStateRepository.getInstance().setState(restoreState);
        stateBeforeLearning = null;

        // If recording was successful, reload stored gesture for matching
        if (success) {
            gestureMatcher.reloadStoredGesture();
            Toast.makeText(this, "Gesture saved! Use it to dismiss alarms.", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Learning Mode Control ---

    private void startLearningMode() {
        // Check if this is a member-only feature
        if (isGuestMode) {
            showMemberOnlyDialog("Gesture Learning");
            return;
        }

        if (gestureRecordingController.isRecording()) return;
        
        // End any external animation first
        if (isExternalAnimationActive) {
            AnimationStateRepository.getInstance().endExternalAnimation();
        }

        // Save current state to restore later
        stateBeforeLearning = animationRenderer.getCurrentState();

        // Switch to LEARNING emotion
        AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.LEARNING);

        // Start recording
        gestureRecordingController.startRecording();
    }

    private void stopLearningMode() {
        gestureRecordingController.stopRecording(true);
    }

    // --- Alarm Trigger (for external use) ---

    public void onAlarmTriggered() {
        // End any external animation and force alarm state
        if (isExternalAnimationActive) {
            AnimationStateRepository.getInstance().endExternalAnimation();
        }
        AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.ALARM);
        imageView.postDelayed(() -> AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.IDLE), 5000);
    }

    // --- AlarmStatusRepository.AlarmStatusListener ---

    @Override
    public void onAlarmStatusChanged(boolean isAlarmOn) {
        Log.d(TAG, "Alarm status changed: " + isAlarmOn);
        
        runOnUiThread(() -> {
            if (isAlarmOn) {
                handleAlarmActivation();
            } else {
                handleAlarmDeactivation();
            }
        });
    }

    /**
     * Handle alarm activation: wake screen, play sound, start gesture matching.
     */
    private void handleAlarmActivation() {
        if (isAlarmActive) {
            Log.w(TAG, "Alarm already active, ignoring activation");
            return;
        }

        Log.d(TAG, "Activating alarm");
        isAlarmActive = true;

        // 1. Enable show when locked and keep screen on
        enableAlarmScreenFlags();

        // 2. Start alarm (sound + vibration)
        alarmManagerUtils.startAlarm();

        // 3. Set alarm animation state - clear any external animation lock without
        //    triggering an intermediate IDLE state change to prevent bitmap race condition
        AnimationStateRepository.getInstance().clearExternalAnimationLock();
        AnimationStateRepository.getInstance().setState(AnimationRenderer.AnimState.ALARM);

        // 4. Start gesture matching for dismissal
        gestureMatcher.startMatching();

        // Show toast about how to dismiss
        if (gestureMatcher.hasCustomGesture()) {
            Toast.makeText(this, "Perform your gesture to dismiss", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Shake to dismiss alarm", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handle alarm deactivation: stop sound, release wakelock, restore UI.
     */
    private void handleAlarmDeactivation() {
        if (!isAlarmActive) {
            Log.w(TAG, "Alarm not active, ignoring deactivation");
            return;
        }

        Log.d(TAG, "Deactivating alarm");
        isAlarmActive = false;

        // 1. Stop gesture matching
        gestureMatcher.stopMatching();

        // 2. Stop alarm (sound + vibration)
        alarmManagerUtils.stopAlarm();

        // 3. Clear screen flags
        disableAlarmScreenFlags();

        // 4. Return to happy/idle state - use forceResetToIdle to clear any locks
        AnimationStateRepository.getInstance().forceResetToIdle();

        Toast.makeText(this, "Alarm dismissed", Toast.LENGTH_SHORT).show();
    }

    /**
     * Dismiss the alarm by updating the repository.
     * This triggers handleAlarmDeactivation() via the listener.
     */
    private void dismissAlarm() {
        Log.d(TAG, "Dismissing alarm");
        AlarmStatusRepository.getInstance().setAlarmStatus(false);
    }

    /**
     * Enable window flags to show alarm screen when locked/dimmed.
     */
    private void enableAlarmScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Android 8.1+
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            // Legacy approach
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
        
        Log.d(TAG, "Alarm screen flags enabled");
    }

    /**
     * Disable alarm screen flags when alarm is dismissed.
     */
    private void disableAlarmScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false);
            setTurnScreenOn(false);
        } else {
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
        
        Log.d(TAG, "Alarm screen flags disabled");
    }

    // --- AlarmManagerUtils.AlarmListener ---

    @Override
    public void onAlarmStarted() {
        Log.d(TAG, "Alarm sound/vibration started");
    }

    @Override
    public void onAlarmStopped() {
        Log.d(TAG, "Alarm sound/vibration stopped");
    }

    @Override
    public void onAlarmError(String message) {
        Log.e(TAG, "Alarm error: " + message);
        Toast.makeText(this, "Alarm error: " + message, Toast.LENGTH_SHORT).show();
    }

    // --- GestureMatcher.GestureMatchListener ---

    @Override
    public void onGestureMatched() {
        Log.d(TAG, "Custom gesture matched - dismissing alarm");
        dismissAlarm();
    }

    @Override
    public void onShakeDetected() {
        // This is called by GestureMatcher when shake is detected as fallback
        Log.d(TAG, "Shake detected by GestureMatcher - dismissing alarm");
        dismissAlarm();
    }

    @Override
    public void onMatchingStarted() {
        Log.d(TAG, "Gesture matching started");
    }

    @Override
    public void onMatchingStopped() {
        Log.d(TAG, "Gesture matching stopped");
    }

    // --- Wearable Data Layer Management ---

    /**
     * Enable Wearable Data Layer listeners for receiving updates from phone.
     * Only called when user is authenticated.
     */
    private void enableWearableListeners() {
        Wearable.getMessageClient(this).addListener(this);
        Log.i(TAG, "Wearable Data Layer listeners enabled");
    }

    /**
     * Disable Wearable Data Layer listeners.
     */
    private void disableWearableListeners() {
        Wearable.getMessageClient(this).removeListener(this);
        Log.i(TAG, "Wearable Data Layer listeners disabled");
    }

    /**
     * Handle messages received from the phone via Wearable Data Layer.
     * Only active when authenticated (not in guest mode).
     */
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        byte[] data = messageEvent.getData();

        Log.i(TAG, "Message received from phone: " + path);

        switch (path) {
            case MESSAGE_PATH_GESTURE_SYNC:
                handleGestureSync(data);
                break;
            case MESSAGE_PATH_AUTH_UPDATE:
                handleAuthUpdate(data);
                break;
            default:
                Log.w(TAG, "Unknown message path: " + path);
                break;
        }
    }

    /**
     * Handle gesture sync from phone (member-only feature).
     */
    private void handleGestureSync(byte[] data) {
        // Parse and apply gesture data from phone
        String gestureData = new String(data);
        Log.i(TAG, "Gesture sync received: " + gestureData);
        // TODO: Implement gesture parsing and application
        Toast.makeText(this, "New gesture synced!", Toast.LENGTH_SHORT).show();
    }

    /**
     * Handle authentication updates from phone.
     */
    private void handleAuthUpdate(byte[] data) {
        String authToken = new String(data);
        
        if (authToken != null && !authToken.isEmpty()) {
            // Save token and update auth state
            sessionManager.saveAuthToken(authToken);
            isGuestMode = false;
            
            // Enable Wearable listeners now that we're authenticated
            enableWearableListeners();
            
            Toast.makeText(this, "Successfully paired with phone!", Toast.LENGTH_LONG).show();
            Log.i(TAG, "Auth token received, guest mode disabled");
        }
    }

    // --- Member-Only Feature Dialog ---

    /**
     * Show dialog when guest user tries to access member-only features.
     * @param featureName The name of the feature being accessed
     */
    private void showMemberOnlyDialog(String featureName) {
        new AlertDialog.Builder(this)
                .setTitle("Pair with Phone Required")
                .setMessage(featureName + " requires pairing with your phone. " +
                        "Would you like to open settings to pair?")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    // Navigate to SettingsActivity
                    Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Public method to update auth state (can be called from SettingsActivity).
     */
    public void refreshAuthState() {
        checkAuthState();
        
        // Re-enable or disable Wearable listeners based on new state
        if (!isGuestMode) {
            enableWearableListeners();
        } else {
            disableWearableListeners();
        }
    }
}
