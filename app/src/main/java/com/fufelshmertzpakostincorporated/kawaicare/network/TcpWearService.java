package com.fufelshmertzpakostincorporated.kawaicare.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fufelshmertzpakostincorporated.kawaicare.R;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.Alarm;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.WearAlarmScheduler;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationRenderer;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationStateRepository;
import com.fufelshmertzpakostincorporated.kawaicare.auth.SessionManager;
import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP Socket Server Service for Wear OS device communication.
 * 
 * Replaces Google's WearableListenerService with a custom TCP-based architecture
 * to support non-Google devices on local Wi-Fi networks.
 * 
 * Features:
 * - Persistent background service with foreground notification
 * - TCP Server on configurable port (default 8888)
 * - JSON-based protocol for all commands
 * - Thread pool for handling multiple concurrent client connections
 * - Network Service Discovery (NSD) for automatic device discovery
 * - Robust error handling for broken pipes, timeouts, and partial packets
 * - Secure pairing and authorization system with 6-digit codes
 * - Token-based authentication for authorized commands
 */
public class TcpWearService extends Service {

    private static final String TAG = "TcpWearService";

    // Service Configuration
    public static final int DEFAULT_PORT = 8888;
    public static final String SERVICE_NAME = "KawaiiCareWear";
    public static final String SERVICE_TYPE = "_kawaicare._tcp.";

    // Notification
    private static final String CHANNEL_ID = "tcp_wear_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Timeouts and limits
    private static final int SOCKET_TIMEOUT_MS = 30000; // 30 seconds read timeout
    private static final int MAX_PACKET_SIZE = 65536; // 64KB max packet
    private static final int CONNECTION_POOL_SIZE = 4; // Max concurrent clients
    private static final int PAIRING_CODE_LENGTH = 6;
    private static final long PAIRING_TIMEOUT_MS = 120000; // 2 minutes pairing window

    // =========================================
    // JSON Protocol Commands
    // =========================================

    // Pairing Commands (Unauthorized State)
    public static final String CMD_PAIR = "pair";
    public static final String CMD_GET_AUTH_STATUS = "get_auth_status";

    // Pairing Steps
    public static final String PAIR_STEP_CHALLENGE = "challenge";
    public static final String PAIR_STEP_VERIFY = "verify";

    // Authorized Commands (require valid token)
    public static final String CMD_SET_ALARM = "set_alarm";
    public static final String CMD_DELETE_ALARM = "delete_alarm";
    public static final String CMD_GET_ALARMS = "get_alarms";
    public static final String CMD_SET_ALARM_STATUS = "set_alarm_status";
    public static final String CMD_SET_ANIMATION_STATE = "set_animation_state";
    public static final String CMD_SET_ACTIVE_GESTURE = "set_active_gesture";
    public static final String CMD_START_RECORDING = "start_recording";
    public static final String CMD_STOP_RECORDING = "stop_recording";
    public static final String CMD_REQUEST_EMOTIONS = "request_emotions";
    public static final String CMD_REQUEST_SIGNALS = "request_signals";
    public static final String CMD_PING = "ping";
    public static final String CMD_GET_STATUS = "get_status";
    public static final String CMD_REQUEST_LOGOUT = "request_logout";

    // JSON Response Types
    public static final String RESP_SUCCESS = "success";
    public static final String RESP_ERROR = "error";
    public static final String RESP_EMOTIONS = "emotions";
    public static final String RESP_SIGNALS = "signals";
    public static final String RESP_VALIDATION_ERROR = "validation_error";
    public static final String RESP_PONG = "pong";
    public static final String RESP_STATUS = "status";
    public static final String RESP_ALARM_CREATED = "alarm_created";
    public static final String RESP_ALARM_UPDATED = "alarm_updated";
    public static final String RESP_ALARM_DELETED = "alarm_deleted";
    public static final String RESP_ALARMS_LIST = "alarms_list";
    public static final String RESP_AUTH_STATUS = "auth_status";
    public static final String RESP_PAIRING_CHALLENGE = "pairing_challenge";
    public static final String RESP_PAIRING_SUCCESS = "pairing_success";
    public static final String RESP_PAIRING_FAILED = "pairing_failed";
    public static final String RESP_LOGOUT_SUCCESS = "logout_success";
    public static final String RESP_UNAUTHORIZED = "unauthorized";

    // =========================================
    // Broadcast Actions for MainActivity
    // =========================================

    /** Broadcast action to show pairing code dialog */
    public static final String ACTION_SHOW_PAIRING_CODE = 
            "com.fufelshmertzpakostincorporated.kawaicare.SHOW_PAIRING_CODE";

    /** Broadcast action to dismiss pairing code dialog */
    public static final String ACTION_DISMISS_PAIRING_CODE = 
            "com.fufelshmertzpakostincorporated.kawaicare.DISMISS_PAIRING_CODE";

    /** Broadcast action when pairing completes successfully */
    public static final String ACTION_PAIRING_COMPLETE = 
            "com.fufelshmertzpakostincorporated.kawaicare.PAIRING_COMPLETE";

    /** Broadcast action when device is logged out remotely */
    public static final String ACTION_REMOTE_LOGOUT = 
            "com.fufelshmertzpakostincorporated.kawaicare.REMOTE_LOGOUT";

    /** Extra key for pairing code */
    public static final String EXTRA_PAIRING_CODE = "pairing_code";

    // Server components
    private ServerSocket serverSocket;
    private ExecutorService connectionPool;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread serverThread;
    private NsdHelper nsdHelper;

    // Connected clients tracking
    private final CopyOnWriteArrayList<ClientConnection> connectedClients = new CopyOnWriteArrayList<>();

    // Active authorized connection (only one at a time)
    private final AtomicReference<ClientConnection> authorizedClient = new AtomicReference<>(null);

    // Pairing state
    private final Object pairingLock = new Object();
    private String currentPairingCode = null;
    private long pairingCodeTimestamp = 0;
    private ClientConnection pairingClient = null;

    // Signal Registry (lazy initialized)
    private SignalRegistry signalRegistry;

    // Alarm Scheduler
    private WearAlarmScheduler alarmScheduler;

    // Session Manager for authentication
    private SessionManager sessionManager;

    // Handler for main thread operations
    private Handler mainHandler;

    // SecureRandom for generating pairing codes and tokens
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TcpWearService onCreate");

        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(this);

        // Initialize SignalRegistry
        signalRegistry = new SignalRegistry(this);

        // Initialize Alarm Scheduler
        alarmScheduler = WearAlarmScheduler.getInstance(this);
        alarmScheduler.initialize();

        // Initialize NSD Helper
        nsdHelper = new NsdHelper(this);

        // Create notification channel for foreground service
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "TcpWearService onStartCommand");

        // Start as foreground service
        String statusText = sessionManager.isAuthenticated() 
                ? "Authenticated - Starting server..." 
                : "Not paired - Starting server...";
        startForeground(NOTIFICATION_ID, createNotification(statusText));

        // Start the TCP server
        startServer(DEFAULT_PORT);

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Not a bound service
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "TcpWearService onDestroy");
        stopServer();
        super.onDestroy();
    }

    // =========================================
    // Server Lifecycle
    // =========================================

    /**
     * Start the TCP server on the specified port.
     */
    private void startServer(int port) {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running");
            return;
        }

        connectionPool = Executors.newFixedThreadPool(CONNECTION_POOL_SIZE);

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);
                isRunning.set(true);

                int actualPort = serverSocket.getLocalPort();
                Log.i(TAG, "TCP Server started on port " + actualPort);

                // Update notification
                String statusText = sessionManager.isAuthenticated() 
                        ? "Authenticated - Port " + actualPort
                        : "Not paired - Port " + actualPort;
                updateNotification(statusText);

                // Register NSD service
                nsdHelper.registerService(SERVICE_NAME, SERVICE_TYPE, actualPort);

                // Accept connections loop
                while (isRunning.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Log.d(TAG, "New client connection from: " + 
                                clientSocket.getInetAddress().getHostAddress());
                        
                        // Configure socket
                        clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                        clientSocket.setKeepAlive(true);
                        clientSocket.setTcpNoDelay(true);

                        // Handle in thread pool
                        ClientConnection connection = new ClientConnection(clientSocket);
                        connectedClients.add(connection);
                        connectionPool.submit(connection);

                    } catch (SocketException e) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Socket accept error", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error", e);
                updateNotification("Server error: " + e.getMessage());
            } finally {
                isRunning.set(false);
            }
        }, "TcpWearServer");

        serverThread.start();
    }

    /**
     * Stop the TCP server and clean up resources.
     */
    private void stopServer() {
        Log.d(TAG, "Stopping TCP server");
        isRunning.set(false);

        // Unregister NSD
        if (nsdHelper != null) {
            nsdHelper.unregisterService();
        }

        // Close all client connections
        for (ClientConnection client : connectedClients) {
            client.close();
        }
        connectedClients.clear();
        authorizedClient.set(null);

        // Clear pairing state
        synchronized (pairingLock) {
            currentPairingCode = null;
            pairingClient = null;
        }

        // Close server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }

        // Shutdown thread pool
        if (connectionPool != null) {
            connectionPool.shutdown();
            try {
                if (!connectionPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    connectionPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                connectionPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Wait for server thread
        if (serverThread != null && serverThread.isAlive()) {
            try {
                serverThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =========================================
    // Pairing Code Generation
    // =========================================

    /**
     * Generate a random 6-digit pairing code.
     */
    private String generatePairingCode() {
        StringBuilder code = new StringBuilder(PAIRING_CODE_LENGTH);
        for (int i = 0; i < PAIRING_CODE_LENGTH; i++) {
            code.append(secureRandom.nextInt(10));
        }
        return code.toString();
    }

    /**
     * Generate a secure authentication token.
     */
    private String generateAuthToken() {
        return UUID.randomUUID().toString() + "-" + 
               Long.toHexString(System.currentTimeMillis()) + "-" +
               Long.toHexString(secureRandom.nextLong());
    }

    /**
     * Validate a provided token against the stored token.
     */
    private boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String storedToken = sessionManager.getAuthToken();
        return token.equals(storedToken);
    }

    // =========================================
    // Client Connection Handler
    // =========================================

    /**
     * Handles a single client connection in a separate thread.
     * Implements the JSON protocol for command processing with authentication.
     */
    private class ClientConnection implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final StringBuilder packetBuffer = new StringBuilder();
        
        // Per-connection authorization state
        private final AtomicBoolean isAuthorized = new AtomicBoolean(false);
        private String clientAddress;

        ClientConnection(Socket socket) {
            this.socket = socket;
            this.clientAddress = socket.getInetAddress().getHostAddress();
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                Log.d(TAG, "Client handler started for: " + clientAddress);

                // Send welcome message with auth status
                sendWelcomeMessage();

                // Read and process messages
                while (connected.get() && isRunning.get()) {
                    try {
                        String line = reader.readLine();
                        if (line == null) {
                            // Client disconnected
                            Log.d(TAG, "Client disconnected (EOF)");
                            break;
                        }

                        // Handle partial JSON packets
                        processIncomingData(line);

                    } catch (SocketTimeoutException e) {
                        // Timeout is normal, just continue
                        Log.v(TAG, "Read timeout, continuing...");
                    }
                }

            } catch (SocketException e) {
                if (connected.get()) {
                    Log.w(TAG, "Socket exception (broken pipe?): " + e.getMessage());
                }
            } catch (IOException e) {
                Log.e(TAG, "Client connection error", e);
            } finally {
                // Clean up authorization state if this was the authorized client
                if (isAuthorized.get()) {
                    authorizedClient.compareAndSet(this, null);
                }
                
                // Clean up pairing state if this was the pairing client
                synchronized (pairingLock) {
                    if (pairingClient == this) {
                        pairingClient = null;
                        currentPairingCode = null;
                        // Dismiss pairing dialog
                        broadcastToMainActivity(ACTION_DISMISS_PAIRING_CODE);
                    }
                }

                close();
                connectedClients.remove(this);
                Log.d(TAG, "Client handler ended. Active clients: " + connectedClients.size());
            }
        }

        /**
         * Process incoming data, handling partial JSON packets.
         */
        private void processIncomingData(String data) {
            if (data == null || data.isEmpty()) {
                return;
            }

            // Check packet size limit
            if (packetBuffer.length() + data.length() > MAX_PACKET_SIZE) {
                Log.w(TAG, "Packet buffer overflow, clearing");
                packetBuffer.setLength(0);
                sendError("PACKET_TOO_LARGE", "Maximum packet size exceeded");
                return;
            }

            packetBuffer.append(data);

            // Try to extract complete JSON objects
            String bufferContent = packetBuffer.toString().trim();
            
            // Simple JSON extraction - look for complete objects
            int braceCount = 0;
            int startIndex = -1;

            for (int i = 0; i < bufferContent.length(); i++) {
                char c = bufferContent.charAt(i);
                if (c == '{') {
                    if (braceCount == 0) {
                        startIndex = i;
                    }
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0 && startIndex >= 0) {
                        // Found complete JSON object
                        String jsonStr = bufferContent.substring(startIndex, i + 1);
                        handleJsonMessage(jsonStr);
                        
                        // Remove processed content from buffer
                        bufferContent = bufferContent.substring(i + 1).trim();
                        i = -1; // Reset for next iteration
                        startIndex = -1;
                    }
                }
            }

            // Keep any remaining partial data
            packetBuffer.setLength(0);
            packetBuffer.append(bufferContent);
        }

        /**
         * Handle a complete JSON message with authentication checks.
         */
        private void handleJsonMessage(String jsonStr) {
            try {
                JSONObject message = new JSONObject(jsonStr);
                String command = message.optString("command", "");
                
                Log.d(TAG, "Received command: " + command + " from " + clientAddress);

                // Check authentication status
                boolean deviceIsAuthenticated = sessionManager.isAuthenticated();
                String providedToken = message.optString("token", null);

                // Route based on authentication state
                if (!deviceIsAuthenticated) {
                    // UNAUTHORIZED STATE: Only accept pairing commands
                    handleUnauthorizedCommand(message, command);
                } else {
                    // AUTHENTICATED STATE: Require valid token for most commands
                    handleAuthorizedCommand(message, command, providedToken);
                }

            } catch (JSONException e) {
                Log.e(TAG, "JSON parse error: " + e.getMessage());
                sendError("INVALID_JSON", "Failed to parse JSON: " + e.getMessage());
            }
        }

        /**
         * Handle commands when device is in unauthorized (unpaired) state.
         * Only pairing and status commands are allowed.
         */
        private void handleUnauthorizedCommand(JSONObject message, String command) {
            switch (command) {
                case CMD_GET_AUTH_STATUS:
                    sendAuthStatus(false);
                    break;

                case CMD_PAIR:
                    handlePairingCommand(message);
                    break;

                case CMD_PING:
                    handlePing(message);
                    break;

                default:
                    Log.w(TAG, "Unauthorized command rejected: " + command);
                    sendUnauthorized("Device not paired. Use 'pair' command to initiate pairing.");
                    break;
            }
        }

        /**
         * Handle commands when device is authenticated.
         * Requires valid token for protected commands.
         */
        private void handleAuthorizedCommand(JSONObject message, String command, String token) {
            // Auth status is always available
            if (CMD_GET_AUTH_STATUS.equals(command)) {
                sendAuthStatus(true);
                return;
            }

            // Ping is always available
            if (CMD_PING.equals(command)) {
                handlePing(message);
                return;
            }

            // Reject pairing requests when already authenticated
            if (CMD_PAIR.equals(command)) {
                sendError("ALREADY_PAIRED", "Device is already paired. Use 'request_logout' to unpair.");
                return;
            }

            // All other commands require valid token
            if (!validateToken(token)) {
                Log.w(TAG, "Invalid token from " + clientAddress);
                sendUnauthorized("Invalid or missing authentication token");
                return;
            }

            // Mark this connection as authorized
            if (!isAuthorized.get()) {
                // Check if another client is already authorized
                ClientConnection currentAuthorized = authorizedClient.get();
                if (currentAuthorized != null && currentAuthorized != this) {
                    // Another device is active - reject this one
                    sendError("ANOTHER_DEVICE_ACTIVE", 
                            "Another device is currently controlling the watch");
                    return;
                }
                isAuthorized.set(true);
                authorizedClient.set(this);
                Log.i(TAG, "Client authorized: " + clientAddress);
            }

            // Process authorized command
            switch (command) {
                case CMD_SET_ALARM:
                    handleSetAlarm(message);
                    break;

                case CMD_DELETE_ALARM:
                    handleDeleteAlarm(message);
                    break;

                case CMD_GET_ALARMS:
                    handleGetAlarms();
                    break;

                case CMD_SET_ALARM_STATUS:
                    handleSetAlarmStatus(message);
                    break;

                case CMD_SET_ANIMATION_STATE:
                    handleSetAnimationState(message);
                    break;

                case CMD_SET_ACTIVE_GESTURE:
                    handleSetActiveGesture(message);
                    break;

                case CMD_START_RECORDING:
                    handleStartRecording();
                    break;

                case CMD_STOP_RECORDING:
                    handleStopRecording();
                    break;

                case CMD_REQUEST_EMOTIONS:
                    handleRequestEmotions();
                    break;

                case CMD_REQUEST_SIGNALS:
                    handleRequestSignals();
                    break;

                case CMD_GET_STATUS:
                    handleGetStatus();
                    break;

                case CMD_REQUEST_LOGOUT:
                    handleRequestLogout();
                    break;

                default:
                    Log.w(TAG, "Unknown command: " + command);
                    sendError("UNKNOWN_COMMAND", "Unknown command: " + command);
                    break;
            }
        }

        // =========================================
        // Pairing Command Handlers
        // =========================================

        /**
         * Handle the pairing command workflow.
         */
        private void handlePairingCommand(JSONObject message) {
            String step = message.optString("step", "");
            
            switch (step) {
                case PAIR_STEP_CHALLENGE:
                    handlePairingChallenge();
                    break;

                case PAIR_STEP_VERIFY:
                    String code = message.optString("code", "");
                    handlePairingVerify(code);
                    break;

                default:
                    sendError("INVALID_PAIRING_STEP", 
                            "Invalid pairing step. Use 'challenge' or 'verify'.");
                    break;
            }
        }

        /**
         * Handle pairing challenge request - generates a 6-digit code.
         */
        private void handlePairingChallenge() {
            synchronized (pairingLock) {
                // Check if another client is already pairing
                if (pairingClient != null && pairingClient != this) {
                    sendError("PAIRING_IN_PROGRESS", 
                            "Another device is currently pairing");
                    return;
                }

                // Generate new pairing code
                currentPairingCode = generatePairingCode();
                pairingCodeTimestamp = System.currentTimeMillis();
                pairingClient = this;

                Log.i(TAG, "Pairing challenge initiated, code: " + currentPairingCode);

                // Broadcast to MainActivity to show the pairing code
                Intent intent = new Intent(ACTION_SHOW_PAIRING_CODE);
                intent.setPackage(getPackageName());
                intent.putExtra(EXTRA_PAIRING_CODE, currentPairingCode);
                sendBroadcast(intent);

                // Send response to client
                try {
                    JSONObject response = new JSONObject();
                    response.put("type", RESP_PAIRING_CHALLENGE);
                    response.put("message", "Pairing code displayed on watch. Enter the 6-digit code.");
                    response.put("code_length", PAIRING_CODE_LENGTH);
                    response.put("expires_in_seconds", PAIRING_TIMEOUT_MS / 1000);
                    response.put("timestamp", System.currentTimeMillis());
                    sendJson(response);
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating pairing challenge response", e);
                }
            }
        }

        /**
         * Handle pairing verification - checks the provided code.
         */
        private void handlePairingVerify(String providedCode) {
            synchronized (pairingLock) {
                // Check if this client initiated the pairing
                if (pairingClient != this) {
                    sendError("NO_PAIRING_INITIATED", 
                            "No pairing challenge initiated. Call 'pair' with step 'challenge' first.");
                    return;
                }

                // Check if code has expired
                if (System.currentTimeMillis() - pairingCodeTimestamp > PAIRING_TIMEOUT_MS) {
                    currentPairingCode = null;
                    pairingClient = null;
                    broadcastToMainActivity(ACTION_DISMISS_PAIRING_CODE);
                    sendPairingFailed("PAIRING_EXPIRED", "Pairing code has expired. Please try again.");
                    return;
                }

                // Verify the code
                if (currentPairingCode != null && currentPairingCode.equals(providedCode)) {
                    // SUCCESS - Generate and save auth token
                    String newToken = generateAuthToken();
                    boolean saved = sessionManager.saveAuthToken(newToken);

                    if (saved) {
                        Log.i(TAG, "Pairing successful for client: " + clientAddress);

                        // Clear pairing state
                        currentPairingCode = null;
                        pairingClient = null;

                        // Mark this connection as authorized
                        isAuthorized.set(true);
                        authorizedClient.set(this);

                        // Dismiss pairing dialog
                        broadcastToMainActivity(ACTION_DISMISS_PAIRING_CODE);
                        
                        // Broadcast pairing complete
                        broadcastToMainActivity(ACTION_PAIRING_COMPLETE);

                        // Update notification
                        updateNotification("Paired - Port " + serverSocket.getLocalPort());

                        // Send success response with token
                        try {
                            JSONObject response = new JSONObject();
                            response.put("type", RESP_PAIRING_SUCCESS);
                            response.put("message", "Pairing successful! Device is now authorized.");
                            response.put("token", newToken);
                            response.put("timestamp", System.currentTimeMillis());
                            sendJson(response);
                        } catch (JSONException e) {
                            Log.e(TAG, "Error creating pairing success response", e);
                        }
                    } else {
                        sendPairingFailed("TOKEN_SAVE_ERROR", "Failed to save authentication token.");
                    }
                } else {
                    Log.w(TAG, "Invalid pairing code provided by " + clientAddress);
                    sendPairingFailed("INVALID_CODE", "Incorrect pairing code. Please try again.");
                }
            }
        }

        // =========================================
        // Authorized Command Handlers
        // =========================================

        /**
         * Handle set_alarm command - creates a new alarm.
         * 
         * Request: {"command": "set_alarm", "token": "...", "time_millis": 1712345678000, 
         *           "label": "Morning Wakeup", "stop_signal": "SIGNAL_SHAKE"}
         * Response: {"type": "alarm_created", "alarm": {...}, "timestamp": ...}
         */
        private void handleSetAlarm(JSONObject message) {
            long timeMillis = message.optLong("time_millis", 0);
            String label = message.optString("label", "");
            String stopSignal = message.optString("stop_signal", SignalRegistry.SIGNAL_SHAKE);

            Log.d(TAG, "Set alarm: time=" + timeMillis + ", label=" + label + ", signal=" + stopSignal);

            // Validate time
            if (timeMillis <= 0) {
                sendError("INVALID_TIME", "time_millis is required and must be positive");
                return;
            }

            if (timeMillis <= System.currentTimeMillis()) {
                sendError("INVALID_TIME", "Alarm time must be in the future");
                return;
            }

            // Validate stop signal
            SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(stopSignal);
            if (!validation.isValid()) {
                Log.w(TAG, "Signal validation failed: " + validation.getErrorCode());
                sendValidationError(stopSignal, validation);
                return;
            }

            // Schedule the alarm
            Alarm alarm = alarmScheduler.scheduleAlarm(timeMillis, label, stopSignal);
            if (alarm == null) {
                sendError("ALARM_SCHEDULE_FAILED", "Failed to schedule alarm");
                return;
            }

            // Send success response with alarm details
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_ALARM_CREATED);
                response.put("message", "Alarm scheduled successfully");
                response.put("alarm", alarm.toJson());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating alarm created response", e);
            }
        }

        /**
         * Handle delete_alarm command - removes an alarm.
         * 
         * Request: {"command": "delete_alarm", "token": "...", "alarm_id": "uuid"}
         * Response: {"type": "alarm_deleted", "alarm_id": "uuid", "timestamp": ...}
         */
        private void handleDeleteAlarm(JSONObject message) {
            String alarmId = message.optString("alarm_id", "");

            Log.d(TAG, "Delete alarm: " + alarmId);

            if (alarmId.isEmpty()) {
                sendError("MISSING_ALARM_ID", "alarm_id is required");
                return;
            }

            boolean deleted = alarmScheduler.cancelAlarm(alarmId, true);
            if (!deleted) {
                sendError("ALARM_NOT_FOUND", "Alarm with ID " + alarmId + " not found");
                return;
            }

            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_ALARM_DELETED);
                response.put("message", "Alarm deleted successfully");
                response.put("alarm_id", alarmId);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating alarm deleted response", e);
            }
        }

        /**
         * Handle get_alarms command - returns all alarms.
         * 
         * Request: {"command": "get_alarms", "token": "..."}
         * Response: {"type": "alarms_list", "alarms": [...], "count": 5, "timestamp": ...}
         */
        private void handleGetAlarms() {
            Log.d(TAG, "Get alarms requested");

            try {
                java.util.List<Alarm> alarms = alarmScheduler.getAllAlarms();
                JSONArray alarmsArray = new JSONArray();
                
                for (Alarm alarm : alarms) {
                    alarmsArray.put(alarm.toJson());
                }

                JSONObject response = new JSONObject();
                response.put("type", RESP_ALARMS_LIST);
                response.put("alarms", alarmsArray);
                response.put("count", alarms.size());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating alarms list response", e);
                sendError("ALARMS_ERROR", "Failed to get alarms: " + e.getMessage());
            }
        }

        /**
         * Handle set_alarm_status command - enables/disables an alarm.
         * 
         * Legacy behavior (no alarm_id): Sets the global alarm ON/OFF state
         * New behavior (with alarm_id): Enables/disables specific alarm
         * 
         * Request: {"command": "set_alarm_status", "token": "...", "status": "ON/OFF"}
         * Request: {"command": "set_alarm_status", "token": "...", "alarm_id": "uuid", "status": "ON/OFF"}
         */
        private void handleSetAlarmStatus(JSONObject message) {
            String status = message.optString("status", "");
            String alarmId = message.optString("alarm_id", null);
            
            Log.d(TAG, "Set alarm status: " + status + ", alarmId=" + alarmId);

            if (status.isEmpty()) {
                sendError("MISSING_STATUS", "Alarm status is required");
                return;
            }

            boolean enabled = "ON".equalsIgnoreCase(status);

            // Check if this is for a specific alarm or global status
            if (alarmId != null && !alarmId.isEmpty()) {
                // Toggle specific alarm
                Alarm alarm = alarmScheduler.setAlarmEnabled(alarmId, enabled);
                if (alarm == null) {
                    sendError("ALARM_NOT_FOUND", "Alarm with ID " + alarmId + " not found");
                    return;
                }

                try {
                    JSONObject response = new JSONObject();
                    response.put("type", RESP_ALARM_UPDATED);
                    response.put("message", "Alarm " + (enabled ? "enabled" : "disabled"));
                    response.put("alarm", alarm.toJson());
                    response.put("timestamp", System.currentTimeMillis());
                    sendJson(response);
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating alarm updated response", e);
                }
            } else {
                // Legacy: Set global alarm state
                AlarmStatusRepository.getInstance().setAlarmStatus(enabled);
                sendSuccess("Alarm status set to: " + status);
            }
        }

        private void handleSetAnimationState(JSONObject message) {
            String anim = message.optString("state", "");
            Log.d(TAG, "Set animation state: " + anim);

            if (anim.isEmpty()) {
                sendError("MISSING_STATE", "Animation state is required");
                return;
            }

            try {
                AnimationRenderer.AnimState state = AnimationRenderer.AnimState.valueOf(anim.toUpperCase());
                AnimationStateRepository.getInstance().setState(state);
                sendSuccess("Animation state set to: " + anim);
            } catch (IllegalArgumentException e) {
                sendError("INVALID_STATE", "Unknown animation state: " + anim);
            }
        }

        private void handleSetActiveGesture(JSONObject message) {
            String signal = message.optString("signal", "");
            Log.d(TAG, "Set active gesture: " + signal);

            if (signal.isEmpty()) {
                sendError("MISSING_SIGNAL", "Signal is required");
                return;
            }

            // Validate the signal
            SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(signal);

            if (!validation.isValid()) {
                Log.w(TAG, "Signal validation failed: " + validation.getErrorCode());
                sendValidationError(signal, validation);
                return;
            }

            // Handle custom gesture mapping
            String customGesturePath = null;
            if (SignalRegistry.SIGNAL_CUSTOM.equals(signal)) {
                customGesturePath = signalRegistry.getCustomGestureFilePath();
                Log.d(TAG, "Custom signal selected, gesture file: " + customGesturePath);
            }

            // Update the repository
            AlarmStatusRepository.getInstance().setActiveStopSignal(signal, customGesturePath);
            sendSuccess("Active gesture set to: " + signal);
        }

        private void handleStartRecording() {
            Log.d(TAG, "Starting learning mode");
            broadcastToMainActivity(MainActivity.ACTION_START_LEARNING);
            sendSuccess("Learning mode started");
        }

        private void handleStopRecording() {
            Log.d(TAG, "Stopping learning mode");
            broadcastToMainActivity(MainActivity.ACTION_STOP_LEARNING);
            sendSuccess("Learning mode stopped");
        }

        private void handleRequestEmotions() {
            Log.d(TAG, "Emotions list requested");
            sendAvailableEmotions();
        }

        private void handleRequestSignals() {
            Log.d(TAG, "Supported signals requested");
            sendSupportedSignals();
        }

        private void handlePing(JSONObject message) {
            long clientTime = message.optLong("timestamp", 0);
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_PONG);
                response.put("client_timestamp", clientTime);
                response.put("server_timestamp", System.currentTimeMillis());
                response.put("authenticated", sessionManager.isAuthenticated());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating pong response", e);
            }
        }

        private void handleGetStatus() {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_STATUS);
                response.put("alarm_on", AlarmStatusRepository.getInstance().isAlarmOn());
                response.put("active_signal", AlarmStatusRepository.getInstance().getActiveStopSignal());
                response.put("server_name", SERVICE_NAME);
                response.put("authenticated", sessionManager.isAuthenticated());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating status response", e);
            }
        }

        /**
         * Handle remote logout request.
         * Clears session and terminates the connection.
         */
        private void handleRequestLogout() {
            Log.i(TAG, "Remote logout requested by " + clientAddress);

            // Clear the session
            sessionManager.clearSession();

            // Clear authorization state
            isAuthorized.set(false);
            authorizedClient.set(null);

            // Update notification
            updateNotification("Not paired - Port " + serverSocket.getLocalPort());

            // Broadcast logout to MainActivity
            broadcastToMainActivity(ACTION_REMOTE_LOGOUT);

            // Send success response
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_LOGOUT_SUCCESS);
                response.put("message", "Device logged out successfully. Connection will be closed.");
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating logout response", e);
            }

            // Schedule connection close
            mainHandler.postDelayed(this::close, 500);
        }

        // =========================================
        // Response Methods
        // =========================================

        private void sendWelcomeMessage() {
            try {
                JSONObject welcome = new JSONObject();
                welcome.put("type", "welcome");
                welcome.put("server", SERVICE_NAME);
                welcome.put("version", "1.0");
                welcome.put("authenticated", sessionManager.isAuthenticated());
                welcome.put("timestamp", System.currentTimeMillis());
                sendJson(welcome);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating welcome message", e);
            }
        }

        private void sendAuthStatus(boolean isAuthenticated) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_AUTH_STATUS);
                response.put("authenticated", isAuthenticated);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating auth status response", e);
            }
        }

        private void sendSuccess(String message) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_SUCCESS);
                response.put("message", message);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating success response", e);
            }
        }

        private void sendError(String code, String message) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_ERROR);
                response.put("error_code", code);
                response.put("error_message", message);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating error response", e);
            }
        }

        private void sendUnauthorized(String message) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_UNAUTHORIZED);
                response.put("error_code", "UNAUTHORIZED");
                response.put("error_message", message);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating unauthorized response", e);
            }
        }

        private void sendPairingFailed(String code, String message) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_PAIRING_FAILED);
                response.put("error_code", code);
                response.put("error_message", message);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating pairing failed response", e);
            }
        }

        private void sendValidationError(String signal, SignalRegistry.ValidationResult validation) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_VALIDATION_ERROR);
                response.put("requested_signal", signal);
                response.put("error_code", validation.getErrorCode());
                response.put("error_message", validation.getErrorMessage());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating validation error response", e);
            }
        }

        private void sendAvailableEmotions() {
            try {
                // Get available animation folders from assets
                String[] folders = getAssets().list("");
                ArrayList<String> emotions = new ArrayList<>();

                if (folders != null) {
                    for (String folder : folders) {
                        String[] files = getAssets().list(folder);
                        if (files != null && files.length > 0) {
                            boolean hasImages = false;
                            for (String file : files) {
                                String lower = file.toLowerCase();
                                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
                                        lower.endsWith(".png")) {
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

                JSONObject response = new JSONObject();
                response.put("type", RESP_EMOTIONS);
                response.put("emotions", new JSONArray(emotions));
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);

            } catch (Exception e) {
                Log.e(TAG, "Error reading emotions from assets", e);
                sendError("EMOTIONS_ERROR", "Failed to read emotions: " + e.getMessage());
            }
        }

        private void sendSupportedSignals() {
            try {
                List<String> supportedSignals = signalRegistry.getSupportedSignals();
                String jsonDetails = signalRegistry.getSupportedSignalsAsJson();

                Log.d(TAG, "Sending supported signals: " + supportedSignals);

                JSONObject response = new JSONObject();
                response.put("type", RESP_SIGNALS);
                response.put("signals", new JSONArray(supportedSignals));
                response.put("details", new JSONObject(jsonDetails));
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);

            } catch (Exception e) {
                Log.e(TAG, "Error sending supported signals", e);
                sendError("SIGNALS_ERROR", "Failed to get signals: " + e.getMessage());
            }
        }

        /**
         * Send a JSON object to the client with proper framing.
         */
        synchronized void sendJson(JSONObject json) {
            if (!connected.get() || writer == null) {
                return;
            }

            try {
                String jsonStr = json.toString();
                writer.write(jsonStr);
                writer.newLine();
                writer.flush();
                Log.v(TAG, "Sent: " + jsonStr);
            } catch (SocketException e) {
                Log.w(TAG, "Send failed (broken pipe?): " + e.getMessage());
                close();
            } catch (IOException e) {
                Log.e(TAG, "Send error", e);
                close();
            }
        }

        /**
         * Close this client connection.
         */
        void close() {
            if (!connected.compareAndSet(true, false)) {
                return; // Already closed
            }

            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {}

            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {}

            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}

            Log.d(TAG, "Client connection closed: " + clientAddress);
        }
    }

    // =========================================
    // Broadcast Methods
    // =========================================

    private void broadcastToMainActivity(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * Broadcast a message to all connected clients.
     */
    public void broadcastToClients(JSONObject message) {
        for (ClientConnection client : connectedClients) {
            client.sendJson(message);
        }
    }

    // =========================================
    // Notification Methods
    // =========================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TCP Wear Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Background service for network communication");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KawaiiCare Network Service")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(content));
        }
    }

    // =========================================
    // Public API
    // =========================================

    /**
     * Get the current server port, or -1 if not running.
     */
    public int getServerPort() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return serverSocket.getLocalPort();
        }
        return -1;
    }

    /**
     * Check if the server is currently running.
     */
    public boolean isServerRunning() {
        return isRunning.get();
    }

    /**
     * Get the number of connected clients.
     */
    public int getConnectedClientCount() {
        return connectedClients.size();
    }

    /**
     * Check if there is an authorized client connected.
     */
    public boolean hasAuthorizedClient() {
        return authorizedClient.get() != null;
    }

    /**
     * Get an unmodifiable list of connected client addresses.
     */
    public List<String> getConnectedClientAddresses() {
        List<String> addresses = new ArrayList<>();
        for (ClientConnection client : connectedClients) {
            if (client.socket != null && client.socket.isConnected()) {
                addresses.add(client.socket.getInetAddress().getHostAddress());
            }
        }
        return Collections.unmodifiableList(addresses);
    }
}
