package com.fufelshmertzpakostincorporated.kawaicare.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;

import com.fufelshmertzpakostincorporated.kawaicare.R;
import com.fufelshmertzpakostincorporated.kawaicare.alarm.SignalRegistry;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationRenderer;
import com.fufelshmertzpakostincorporated.kawaicare.animation.AnimationStateRepository;
import com.fufelshmertzpakostincorporated.kawaicare.animation.EmojiRegistry;
import com.fufelshmertzpakostincorporated.kawaicare.auth.SessionManager;
import com.fufelshmertzpakostincorporated.kawaicare.data.AlarmStatusRepository;
import com.fufelshmertzpakostincorporated.kawaicare.event.EventPayload;
import com.fufelshmertzpakostincorporated.kawaicare.event.EventRepository;
import com.fufelshmertzpakostincorporated.kawaicare.event.EventScheduler;
import com.fufelshmertzpakostincorporated.kawaicare.event.Recurrence;
import com.fufelshmertzpakostincorporated.kawaicare.event.ScheduledEvent;
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
    public static final String PAIR_STEP_CANCEL = "cancel";

    // New Event-based Commands (v2.0)
    public static final String CMD_SYNC_EVENTS = "sync_events";
    public static final String CMD_UPDATE_EVENT = "update_event";
    public static final String CMD_GET_EVENTS = "get_events";
    public static final String CMD_DELETE_EVENT = "delete_event";

    // Animation and gesture commands
    public static final String CMD_SET_ANIMATION_STATE = "set_animation_state";
    public static final String CMD_SET_ACTIVE_GESTURE = "set_active_gesture";
    public static final String CMD_START_RECORDING = "start_recording";
    public static final String CMD_STOP_RECORDING = "stop_recording";
    public static final String CMD_REQUEST_EMOTIONS = "request_emotions";
    public static final String CMD_REQUEST_SIGNALS = "request_signals";
    public static final String CMD_SET_EMOJI = "set_emoji";
    public static final String CMD_REQUEST_AVAILABLE_EMOJIS = "request_available_emojis";
    public static final String CMD_PING = "ping";
    public static final String CMD_GET_STATUS = "get_status";
    public static final String CMD_REQUEST_LOGOUT = "request_logout";

    // JSON Response Types
    public static final String RESP_SUCCESS = "success";
    public static final String RESP_ERROR = "error";
    public static final String RESP_EMOTIONS = "emotions";
    public static final String RESP_SIGNALS = "signals";
    public static final String RESP_VALIDATION_ERROR = "validation_error";
    public static final String RESP_AVAILABLE_EMOJIS = "available_emojis";
    public static final String RESP_PONG = "pong";
    public static final String RESP_STATUS = "status";
    public static final String RESP_AUTH_STATUS = "auth_status";
    public static final String RESP_PAIRING_CHALLENGE = "pairing_challenge";
    public static final String RESP_PAIRING_SUCCESS = "pairing_success";
    public static final String RESP_PAIRING_FAILED = "pairing_failed";
    public static final String RESP_PAIRING_CANCELLED = "pairing_cancelled";
    public static final String RESP_LOGOUT_SUCCESS = "logout_success";
    public static final String RESP_UNAUTHORIZED = "unauthorized";
    
    // New Event Response Types (v2.0)
    public static final String RESP_EVENTS_SYNCED = "events_synced";
    public static final String RESP_EVENT_UPDATED = "event_updated";
    public static final String RESP_EVENTS_LIST = "events_list";
    public static final String RESP_EVENT_DELETED = "event_deleted";
    public static final String RESP_EVENT_TRIGGERED = "event_triggered";
    public static final String RESP_EVENT_DISMISSED = "event_dismissed";

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

    /** Broadcast action for connectivity diagnostic results */
    public static final String ACTION_CONNECTIVITY_DIAGNOSTIC = 
            "com.fufelshmertzpakostincorporated.kawaicare.CONNECTIVITY_DIAGNOSTIC";

    /** Extra key for pairing code */
    public static final String EXTRA_PAIRING_CODE = "pairing_code";

    /** Extra key for diagnostic info */
    public static final String EXTRA_DIAGNOSTIC_INFO = "diagnostic_info";

    // Server components
    private ServerSocket serverSocket;
    private ExecutorService connectionPool;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread serverThread;
    private NsdHelper nsdHelper;

    // Power and Wi-Fi management (critical for Wear OS)
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;

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
    
    // Event Scheduler (v2.0 - unified event system)
    private EventScheduler eventScheduler;
    
    // Event Repository (v2.0)
    private EventRepository eventRepository;

    // Session Manager for authentication
    private SessionManager sessionManager;

    // Handler for main thread operations
    private Handler mainHandler;

    // Network callback for detecting IP changes
    private ConnectivityManager.NetworkCallback networkCallback;

    // SecureRandom for generating pairing codes and tokens
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TcpWearService onCreate");

        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(this);

        // Initialize power and Wi-Fi management
        initializePowerManagement();

        // Initialize SignalRegistry
        signalRegistry = new SignalRegistry(this);
        
        // Initialize Event Scheduler and Repository (v2.0)
        eventRepository = EventRepository.getInstance(this);
        eventScheduler = EventScheduler.getInstance(this);
        eventScheduler.initialize();

        // Initialize NSD Helper
        nsdHelper = new NsdHelper(this);

        // Register network callback for IP change detection
        registerNetworkCallback();

        // Create notification channel for foreground service
        createNotificationChannel();
    }

    /**
     * Register a network callback to detect IP address changes.
     * When the IP changes (e.g., DHCP renewal), we refresh the NSD registration
     * to ensure the service remains discoverable with the correct address.
     */
    private void registerNetworkCallback() {
        if (connectivityManager == null) {
            Log.w(TAG, "ConnectivityManager not available, cannot register network callback");
            return;
        }

        // Get current IP to initialize the callback state (prevents false positive on registration)
        final String initialIp = getLocalIpAddress();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            private String lastKnownIp = initialIp;
            private boolean pendingRefresh = false;
            private boolean initialCallbackFired = false;

            @Override
            public void onAvailable(@NonNull Network network) {
                // Skip the initial callback that fires immediately on registration
                if (!initialCallbackFired) {
                    initialCallbackFired = true;
                    Log.d(TAG, "Network callback: initial onAvailable (skipped)");
                    return;
                }
                Log.i(TAG, "Network became available (reconnection)");
                scheduleNsdRefresh();
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, 
                    @NonNull LinkProperties linkProperties) {
                String newIp = getLocalIpAddress();
                if (newIp != null && !newIp.equals(lastKnownIp)) {
                    Log.i(TAG, "IP address changed from " + lastKnownIp + " to " + newIp);
                    lastKnownIp = newIp;
                    scheduleNsdRefresh();
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.w(TAG, "Network lost");
                lastKnownIp = null;
                pendingRefresh = false;
            }

            private void scheduleNsdRefresh() {
                // Debounce: only schedule if no refresh is already pending
                if (pendingRefresh) {
                    Log.d(TAG, "NSD refresh already pending, skipping duplicate");
                    return;
                }
                pendingRefresh = true;
                
                // Delay to let the network stabilize
                mainHandler.postDelayed(() -> {
                    pendingRefresh = false;
                    if (isRunning.get() && isWifiConnected() && isServerSocketValid()) {
                        Log.i(TAG, "Refreshing NSD registration due to network change");
                        refreshNsdRegistration();
                    } else {
                        Log.d(TAG, "Skipping NSD refresh: server not ready");
                    }
                }, 3000); // 3 second delay to let network fully stabilize
            }
        };

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
            Log.i(TAG, "Network callback registered for Wi-Fi changes (initial IP: " + initialIp + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    /**
     * Check if the server socket is valid and bound.
     */
    private boolean isServerSocketValid() {
        return serverSocket != null && !serverSocket.isClosed() && serverSocket.getLocalPort() > 0;
    }

    /** Intent action to run connectivity diagnostic */
    public static final String ACTION_RUN_DIAGNOSTIC = 
            "com.fufelshmertzpakostincorporated.kawaicare.RUN_DIAGNOSTIC";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "TcpWearService onStartCommand");

        // Check for diagnostic request
        if (intent != null && ACTION_RUN_DIAGNOSTIC.equals(intent.getAction())) {
            Log.d(TAG, "Running connectivity diagnostic on request");
            runConnectivityDiagnostic();
            return START_STICKY;
        }

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
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "TcpWearService onTaskRemoved - cleaning up server resources");
        // Clean up server resources when app is swiped away
        // This helps prevent port binding issues on restart
        stopServer();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "TcpWearService onDestroy");
        
        // Unregister network callback
        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                Log.d(TAG, "Network callback unregistered");
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering network callback", e);
            }
            networkCallback = null;
        }
        
        stopServer();
        super.onDestroy();
    }

    // =========================================
    // Server Lifecycle
    // =========================================

    /**
     * Start the TCP server on the specified port.
     * Handles port conflicts gracefully with retry logic.
     */
    private synchronized void startServer(int port) {
        // Use compareAndSet for atomic check-and-set to prevent race conditions
        // when service is started from multiple places (AlarmBootReceiver + MainActivity)
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Server already running or starting, ignoring duplicate start request");
            return;
        }

        Log.i(TAG, "Starting TCP server on port " + port);

        // Check Wi-Fi connectivity before starting
        if (!isWifiConnected()) {
            Log.e(TAG, "Wi-Fi is not connected. Cannot start NSD service.");
            updateNotification("Wi-Fi not connected");
            // Still start the server, but log the warning
        } else {
            Log.i(TAG, "Wi-Fi is connected. Proceeding with server startup.");
        }

        // Acquire power and Wi-Fi locks to prevent sleep
        acquireLocks();

        connectionPool = Executors.newFixedThreadPool(CONNECTION_POOL_SIZE);

        serverThread = new Thread(() -> {
            int maxRetries = 3;
            int retryDelayMs = 1000;
            boolean serverStartedSuccessfully = false;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Bind to all interfaces (0.0.0.0) to ensure accessibility
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
                    serverStartedSuccessfully = true;

                    int actualPort = serverSocket.getLocalPort();
                    String localIp = getLocalIpAddress();
                    Log.i(TAG, "TCP Server started on 0.0.0.0:" + actualPort + " (local IP: " + localIp + ")");

                    // Update notification
                    String statusText = sessionManager.isAuthenticated() 
                            ? "Authenticated - Port " + actualPort
                            : "Not paired - Port " + actualPort;
                    updateNotification(statusText);

                    // Register NSD service with unique name
                    String uniqueServiceName = SERVICE_NAME + "-" + Build.MODEL.replace(" ", "_");
                    nsdHelper.registerService(uniqueServiceName, SERVICE_TYPE, actualPort);

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
                    
                    // If we get here normally (isRunning became false), exit the retry loop
                    break;
                    
                } catch (java.net.BindException e) {
                    // Port is already in use - likely from a previous instance
                    Log.w(TAG, "Port " + port + " already in use (attempt " + attempt + "/" + maxRetries + ")");
                    
                    if (attempt < maxRetries) {
                        Log.i(TAG, "Waiting " + retryDelayMs + "ms before retry...");
                        try {
                            Thread.sleep(retryDelayMs);
                            // Increase delay for next attempt
                            retryDelayMs *= 2;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        Log.e(TAG, "Failed to bind to port " + port + " after " + maxRetries + " attempts");
                        updateNotification("Port unavailable - restart app");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Server error", e);
                    updateNotification("Server error: " + e.getMessage());
                    break; // Don't retry on other IO errors
                }
            }
            
            // Only set isRunning to false if we never started successfully
            // (if we did start, isRunning was already true and this is a normal shutdown)
            if (!serverStartedSuccessfully) {
                Log.w(TAG, "Server failed to start, resetting isRunning flag");
                isRunning.set(false);
            }
        }, "TcpWearServer");

        serverThread.start();
    }

    /**
     * Refresh NSD registration to ensure discoverability.
     * This is useful after logout or when NSD may have become stale.
     * Unregisters and re-registers the service with a fresh mDNS announcement.
     */
    private void refreshNsdRegistration() {
        if (nsdHelper == null) {
            Log.w(TAG, "Cannot refresh NSD: NsdHelper not available");
            return;
        }
        
        if (!isServerSocketValid()) {
            Log.w(TAG, "Cannot refresh NSD: server socket not valid");
            return;
        }

        int port = serverSocket.getLocalPort();
        if (port <= 0) {
            Log.w(TAG, "Cannot refresh NSD: invalid port " + port);
            return;
        }
        
        String uniqueServiceName = SERVICE_NAME + "-" + Build.MODEL.replace(" ", "_");

        Log.i(TAG, "Refreshing NSD registration for service: " + uniqueServiceName + " on port " + port);

        // Unregister first (if registered) - this happens synchronously for the API call
        // but the mDNS propagation is async
        if (nsdHelper.isServiceRegistered()) {
            nsdHelper.unregisterService();
            Log.d(TAG, "NSD service unregistered, waiting before re-registration...");
        }

        // Delay re-registration to allow:
        // 1. The unregister callback to complete
        // 2. mDNS to propagate the unregistration to clients
        // 3. Clients to clear their cached service records
        mainHandler.postDelayed(() -> {
            // Re-register the service
            if (isRunning.get() && serverSocket != null && !serverSocket.isClosed()) {
                Log.i(TAG, "Re-registering NSD service...");
                nsdHelper.registerService(uniqueServiceName, SERVICE_TYPE, port);
            } else {
                Log.w(TAG, "Server stopped, skipping NSD re-registration");
            }
        }, 1000); // Increased delay for better mDNS propagation
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

        // Release power and Wi-Fi locks
        releaseLocks();
    }

    // =========================================
    // Power and Wi-Fi Management
    // =========================================

    /**
     * Initialize power management components.
     * Critical for Wear OS to prevent Wi-Fi from being disabled when screen is off.
     */
    private void initializePowerManagement() {
        // Initialize WifiManager
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        // Initialize ConnectivityManager
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // Create WifiLock to prevent Wi-Fi from being disabled
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    TAG + ":WifiLock"
            );
            wifiLock.setReferenceCounted(false);
            Log.d(TAG, "WifiLock created");
        } else {
            Log.w(TAG, "WifiManager not available");
        }

        // Create WakeLock to prevent CPU from sleeping
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    TAG + ":WakeLock"
            );
            wakeLock.setReferenceCounted(false);
            Log.d(TAG, "WakeLock created");
        } else {
            Log.w(TAG, "PowerManager not available");
        }
    }

    /**
     * Acquire Wi-Fi and Wake locks to ensure connectivity.
     */
    private void acquireLocks() {
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
            Log.i(TAG, "WifiLock acquired - Wi-Fi will remain active");
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.i(TAG, "WakeLock acquired - CPU will remain active");
        }
    }

    /**
     * Release Wi-Fi and Wake locks.
     */
    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
                Log.i(TAG, "WifiLock released");
            } catch (Exception e) {
                Log.w(TAG, "Error releasing WifiLock", e);
            }
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.i(TAG, "WakeLock released");
            } catch (Exception e) {
                Log.w(TAG, "Error releasing WakeLock", e);
            }
        }
    }

    /**
     * Check if Wi-Fi is currently connected.
     * @return true if Wi-Fi is connected and has internet capability
     */
    private boolean isWifiConnected() {
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                Log.d(TAG, "No active network");
                return false;
            }

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null) {
                Log.d(TAG, "No network capabilities");
                return false;
            }

            boolean hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            
            Log.d(TAG, "Network check - Wi-Fi: " + hasWifi + ", Internet: " + hasInternet);
            return hasWifi;
        } else {
            // Legacy API for older devices
            @SuppressWarnings("deprecation")
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            boolean connected = networkInfo != null && 
                    networkInfo.isConnected() && 
                    networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
            Log.d(TAG, "Network check (legacy) - Connected: " + connected);
            return connected;
        }
    }

    /**
     * Get the local IP address of the device on the Wi-Fi network.
     * @return IP address string or "Unknown" if not available
     */
    private String getLocalIpAddress() {
        try {
            // Try to get IP from WifiManager first (most reliable on Wear OS)
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    int ipInt = wifiInfo.getIpAddress();
                    if (ipInt != 0) {
                        String ip = String.format("%d.%d.%d.%d",
                                (ipInt & 0xff),
                                (ipInt >> 8 & 0xff),
                                (ipInt >> 16 & 0xff),
                                (ipInt >> 24 & 0xff));
                        Log.d(TAG, "Local IP (from WifiManager): " + ip);
                        return ip;
                    }
                }
            }

            // Fallback: enumerate network interfaces
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                java.util.Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        Log.d(TAG, "Local IP (from NetworkInterface " + networkInterface.getName() + "): " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting local IP address", e);
        }
        return "Unknown";
    }

    /**
     * Run a connectivity diagnostic and broadcast results.
     * Call this method to debug NSD discovery issues.
     */
    public void runConnectivityDiagnostic() {
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("=== Connectivity Diagnostic ===\n\n");

        // Server status
        diagnostic.append("SERVER STATUS:\n");
        diagnostic.append("  Running: ").append(isRunning.get()).append("\n");
        diagnostic.append("  Port: ").append(getServerPort()).append("\n");
        diagnostic.append("  Clients: ").append(getConnectedClientCount()).append("\n\n");

        // Wi-Fi status
        diagnostic.append("WI-FI STATUS:\n");
        diagnostic.append("  Connected: ").append(isWifiConnected()).append("\n");
        diagnostic.append("  Local IP: ").append(getLocalIpAddress()).append("\n");
        
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                diagnostic.append("  SSID: ").append(wifiInfo.getSSID()).append("\n");
                diagnostic.append("  RSSI: ").append(wifiInfo.getRssi()).append(" dBm\n");
                diagnostic.append("  Link Speed: ").append(wifiInfo.getLinkSpeed()).append(" Mbps\n");
            }
        }
        diagnostic.append("\n");

        // Lock status
        diagnostic.append("POWER MANAGEMENT:\n");
        diagnostic.append("  WifiLock held: ").append(wifiLock != null && wifiLock.isHeld()).append("\n");
        diagnostic.append("  WakeLock held: ").append(wakeLock != null && wakeLock.isHeld()).append("\n\n");

        // NSD status
        diagnostic.append("NSD STATUS:\n");
        if (nsdHelper != null) {
            diagnostic.append("  Registered: ").append(nsdHelper.isServiceRegistered()).append("\n");
            diagnostic.append("  Service Name: ").append(nsdHelper.getRegisteredServiceName()).append("\n");
            diagnostic.append("  Service Type: ").append(SERVICE_TYPE).append("\n");
        } else {
            diagnostic.append("  NsdHelper not initialized\n");
        }
        diagnostic.append("\n");

        // Authentication status
        diagnostic.append("AUTHENTICATION:\n");
        diagnostic.append("  Authenticated: ").append(sessionManager.isAuthenticated()).append("\n");
        diagnostic.append("  Authorized Client: ").append(hasAuthorizedClient()).append("\n");

        String diagnosticText = diagnostic.toString();
        Log.i(TAG, diagnosticText);

        // Broadcast to UI
        Intent intent = new Intent(ACTION_CONNECTIVITY_DIAGNOSTIC);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_DIAGNOSTIC_INFO, diagnosticText);
        sendBroadcast(intent);
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

                case CMD_REQUEST_LOGOUT:
                    // Special handling for logout when already logged out
                    Log.w(TAG, "Logout requested but device is not authenticated");
                    sendError("NOT_AUTHENTICATED", "Device is not currently authenticated");
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
                // New Event Commands (v2.0)
                case CMD_SYNC_EVENTS:
                    handleSyncEvents(message);
                    break;

                case CMD_UPDATE_EVENT:
                    handleUpdateEvent(message);
                    break;

                case CMD_GET_EVENTS:
                    handleGetEvents(message);
                    break;

                case CMD_DELETE_EVENT:
                    handleDeleteEvent(message);
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

                case CMD_SET_EMOJI:
                    handleSetEmoji(message);
                    break;

                case CMD_REQUEST_AVAILABLE_EMOJIS:
                    handleRequestAvailableEmojis();
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

                case PAIR_STEP_CANCEL:
                    handlePairingCancel();
                    break;

                default:
                    sendError("INVALID_PAIRING_STEP", 
                            "Invalid pairing step. Use 'challenge', 'verify', or 'cancel'.");
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

        /**
         * Handle pairing cancellation - client aborts the pairing flow.
         * Clears the active challenge and dismisses the code dialog on the watch.
         */
        private void handlePairingCancel() {
            synchronized (pairingLock) {
                if (pairingClient != this) {
                    sendError("NO_PAIRING_INITIATED",
                            "No pairing challenge initiated by this client.");
                    return;
                }

                Log.i(TAG, "Pairing cancelled by client: " + clientAddress);

                // Clear pairing state
                currentPairingCode = null;
                pairingCodeTimestamp = 0;
                pairingClient = null;

                // Dismiss pairing dialog on the watch
                broadcastToMainActivity(ACTION_DISMISS_PAIRING_CODE);

                // Send confirmation to client
                try {
                    JSONObject response = new JSONObject();
                    response.put("type", RESP_PAIRING_CANCELLED);
                    response.put("message", "Pairing cancelled.");
                    response.put("timestamp", System.currentTimeMillis());
                    sendJson(response);
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating pairing cancelled response", e);
                }
            }
        }

        // =========================================
        // Authorized Command Handlers
        // =========================================

        // =========================================
        // New Event Handlers (v2.0)
        // =========================================

        /**
         * Handle sync_events command - full synchronization of events from client.
         * Replaces all existing events with the provided list.
         * 
         * Request: {"command": "sync_events", "token": "...", "events": [...]}
         * Response: {"type": "events_synced", "event_count": 5, "sync_timestamp": ..., "timestamp": ...}
         */
        private void handleSyncEvents(JSONObject message) {
            JSONArray eventsArray = message.optJSONArray("events");
            
            Log.d(TAG, "Sync events: " + (eventsArray != null ? eventsArray.length() : 0) + " events");

            if (eventsArray == null) {
                sendError("MISSING_EVENTS", "events array is required");
                return;
            }

            try {
                // Parse and validate all events
                java.util.List<ScheduledEvent> events = new java.util.ArrayList<>();
                for (int i = 0; i < eventsArray.length(); i++) {
                    JSONObject eventJson = eventsArray.getJSONObject(i);
                    ScheduledEvent event = ScheduledEvent.fromJson(eventJson);
                    
                    // Validate termination signal
                    SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(event.getTerminationSignal());
                    if (!validation.isValid()) {
                        Log.w(TAG, "Event " + event.getId() + " has invalid signal: " + validation.getErrorCode());
                        // Use default signal instead of failing
                        event = event.withTerminationSignal(SignalRegistry.SIGNAL_SHAKE);
                    }
                    
                    events.add(event);
                }

                // Sync events to repository (this triggers rescheduling)
                int count = eventRepository.syncEvents(events);

                // Send success response
                JSONObject response = new JSONObject();
                response.put("type", RESP_EVENTS_SYNCED);
                response.put("message", "Synchronized " + count + " events");
                response.put("event_count", count);
                response.put("sync_timestamp", eventRepository.getLastSyncTimestamp());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);

            } catch (JSONException e) {
                Log.e(TAG, "Error parsing events for sync", e);
                sendError("INVALID_EVENT_DATA", "Failed to parse events: " + e.getMessage());
            }
        }

        /**
         * Handle update_event command - update a specific event or toggle its state.
         * 
         * Full update: {"command": "update_event", "token": "...", "event": {...}}
         * Toggle only: {"command": "update_event", "token": "...", "event_id": "uuid", "enabled": false}
         */
        private void handleUpdateEvent(JSONObject message) {
            JSONObject eventJson = message.optJSONObject("event");
            String eventId = message.optString("event_id", null);
            
            Log.d(TAG, "Update event: eventId=" + eventId + ", hasFullEvent=" + (eventJson != null));

            try {
                ScheduledEvent updatedEvent;

                if (eventJson != null) {
                    // Full event update
                    updatedEvent = ScheduledEvent.fromJson(eventJson);
                    
                    // Validate termination signal
                    SignalRegistry.ValidationResult validation = signalRegistry.validateSignal(updatedEvent.getTerminationSignal());
                    if (!validation.isValid()) {
                        sendValidationError(updatedEvent.getTerminationSignal(), validation);
                        return;
                    }
                    
                    // Update in repository
                    eventRepository.updateEvent(updatedEvent);
                    
                } else if (eventId != null && !eventId.isEmpty()) {
                    // Toggle enabled state only
                    boolean enabled = message.optBoolean("enabled", true);
                    updatedEvent = eventScheduler.setEventEnabled(eventId, enabled);
                    
                    if (updatedEvent == null) {
                        sendError("EVENT_NOT_FOUND", "Event with ID " + eventId + " not found");
                        return;
                    }
                } else {
                    sendError("MISSING_EVENT_DATA", "Either 'event' object or 'event_id' is required");
                    return;
                }

                // Send success response
                JSONObject response = new JSONObject();
                response.put("type", RESP_EVENT_UPDATED);
                response.put("message", "Event " + (updatedEvent.isEnabled() ? "enabled" : "disabled"));
                response.put("event", updatedEvent.toJson());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);

            } catch (JSONException e) {
                Log.e(TAG, "Error updating event", e);
                sendError("INVALID_EVENT_DATA", "Failed to parse event: " + e.getMessage());
            }
        }

        /**
         * Handle get_events command - returns all events, optionally filtered by type.
         * 
         * Request: {"command": "get_events", "token": "..."}
         * Request: {"command": "get_events", "token": "...", "event_type": "ALARM"}
         */
        private void handleGetEvents(JSONObject message) {
            String eventTypeFilter = message.optString("event_type", null);
            
            Log.d(TAG, "Get events requested, filter=" + eventTypeFilter);

            try {
                java.util.List<ScheduledEvent> events;
                
                if (eventTypeFilter != null && !eventTypeFilter.isEmpty()) {
                    try {
                        ScheduledEvent.EventType type = ScheduledEvent.EventType.valueOf(eventTypeFilter.toUpperCase());
                        events = eventRepository.getEventsByType(type);
                    } catch (IllegalArgumentException e) {
                        sendError("INVALID_EVENT_TYPE", "Unknown event type: " + eventTypeFilter);
                        return;
                    }
                } else {
                    events = eventRepository.getAllEvents();
                }

                JSONArray eventsArray = new JSONArray();
                for (ScheduledEvent event : events) {
                    eventsArray.put(event.toJson());
                }

                JSONObject response = new JSONObject();
                response.put("type", RESP_EVENTS_LIST);
                response.put("events", eventsArray);
                response.put("count", events.size());
                response.put("last_sync_timestamp", eventRepository.getLastSyncTimestamp());
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);

            } catch (JSONException e) {
                Log.e(TAG, "Error creating events list response", e);
                sendError("EVENTS_ERROR", "Failed to get events: " + e.getMessage());
            }
        }

        /**
         * Handle delete_event command - removes an event by ID.
         * 
         * Request: {"command": "delete_event", "token": "...", "event_id": "uuid"}
         */
        private void handleDeleteEvent(JSONObject message) {
            String eventId = message.optString("event_id", "");

            Log.d(TAG, "Delete event: " + eventId);

            if (eventId.isEmpty()) {
                sendError("MISSING_EVENT_ID", "event_id is required");
                return;
            }

            boolean deleted = eventScheduler.cancelEvent(eventId, true);
            if (!deleted) {
                sendError("EVENT_NOT_FOUND", "Event with ID " + eventId + " not found");
                return;
            }

            try {
                JSONObject response = new JSONObject();
                response.put("type", RESP_EVENT_DELETED);
                response.put("message", "Event deleted successfully");
                response.put("event_id", eventId);
                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating event deleted response", e);
            }
        }

        private AnimationRenderer.AnimState resolveAnimationState(String anim) {
            if (anim == null) return null;
            String trimmed = anim.trim();
            if (trimmed.isEmpty()) return null;

            try {
                return AnimationRenderer.AnimState.valueOf(trimmed.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Fall through to alias mapping
            }

            String key = trimmed.toLowerCase();
            switch (key) {
                case "blinks":
                case "wink_1":
                case "wink_2":
                    return AnimationRenderer.AnimState.IDLE;
                case "looks_to_the_left":
                case "turn_left":
                    return AnimationRenderer.AnimState.LOOK_LEFT;
                case "looks_to_the_right":
                case "turn_right":
                    return AnimationRenderer.AnimState.LOOK_RIGHT;
                case "nod":
                case "nods":
                    return AnimationRenderer.AnimState.GESTURE_ACTION;
                case "shake_smile":
                case "shake":
                    return AnimationRenderer.AnimState.SHAKE;
                case "notification":
                case "notice":
                    return AnimationRenderer.AnimState.ALARM;
                case "notification_postpone":
                    return AnimationRenderer.AnimState.NOTIFICATION_POSTPONE;
                case "notification_emoji":
                case "emoji":
                    return AnimationRenderer.AnimState.NOTIFICATION_EMOJI;
                case "fright":
                    return AnimationRenderer.AnimState.FRIGHT;
                default:
                    return null;
            }
        }

        private void handleSetAnimationState(JSONObject message) {
            String anim = message.optString("state", "");
            // Duration: -1 or 0 = auto-calculate from animation length, >0 = explicit duration
            // Default to 0 (auto) if not specified
            long duration = message.optLong("duration", 0);
            Log.d(TAG, "Set animation state: " + anim + ", duration: " + (duration <= 0 ? "auto" : duration + "ms"));

            if (anim.isEmpty()) {
                sendError("MISSING_STATE", "Animation state is required");
                return;
            }

            AnimationRenderer.AnimState state = resolveAnimationState(anim);
            if (state == null) {
                sendError("INVALID_STATE", "Unknown animation state: " + anim);
                return;
            }
                
                // Use setExternalState to properly handle TCP-triggered animations
                // This will:
                // 1. Block sensor-based state changes during the animation
                // 2. Auto-return to IDLE after the animation completes (or specified duration)
                // 3. Allow subsequent TCP commands to override the current animation
                if (state == AnimationRenderer.AnimState.IDLE) {
                    // If explicitly setting to IDLE, end any external animation and reset
                    AnimationStateRepository.getInstance().forceResetToIdle();
                    sendSuccess("Animation state set to: IDLE (immediate)");
                } else if (duration <= 0) {
                    // Auto-calculate duration from animation frame count
                    AnimationStateRepository.getInstance().setExternalState(state);
                    sendSuccess("Animation state set to: " + anim + " (duration: auto-calculated)");
                } else {
                    // Explicit duration specified
                    AnimationStateRepository.getInstance().setExternalState(state, duration);
                    sendSuccess("Animation state set to: " + anim + " (duration: " + duration + "ms)");
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

        /**
         * Handle the set_emoji command.
         * Sets the emoji for the avatar eye-replacement animation.
         * 
         * Payload: {"command":"set_emoji","token":"...","emoji":"😍"}
         * Clear:   {"command":"set_emoji","token":"...","emoji":""}
         * 
         * Optionally trigger the NOTIFICATION_EMOJI animation immediately:
         *   {"command":"set_emoji","token":"...","emoji":"😍","play":true}
         *   {"command":"set_emoji","token":"...","emoji":"😍","play":true,"duration":5000}
         */
        private void handleSetEmoji(JSONObject message) {
            String emoji = message.optString("emoji", "");
            boolean shouldPlay = message.optBoolean("play", false);
            long duration = message.optLong("duration", 0);

            Log.d(TAG, "Set emoji: '" + emoji + "', play: " + shouldPlay);

            if (emoji.isEmpty()) {
                // Clear current emoji
                EmojiRegistry.getInstance().clearEmoji();
                sendSuccess("Emoji cleared");
                return;
            }

            // Set the emoji in the registry
            boolean success = EmojiRegistry.getInstance().setEmoji(emoji);
            if (!success) {
                sendError("EMOJI_RENDER_FAILED",
                        "Failed to render emoji: " + emoji);
                return;
            }

            if (shouldPlay) {
                // Trigger NOTIFICATION_EMOJI animation
                AnimationRenderer.AnimState state = AnimationRenderer.AnimState.NOTIFICATION_EMOJI;
                if (duration <= 0) {
                    AnimationStateRepository.getInstance().setExternalState(state);
                } else {
                    AnimationStateRepository.getInstance().setExternalState(state, duration);
                }
                sendSuccess("Emoji set to: " + emoji + " (animation playing)");
            } else {
                sendSuccess("Emoji set to: " + emoji);
            }
        }

        /**
         * Handle the request_available_emojis command.
         * Returns the curated list of emojis suitable for eye-replacement.
         */
        private void handleRequestAvailableEmojis() {
            Log.d(TAG, "Available emojis requested");
            try {
                EmojiRegistry registry = EmojiRegistry.getInstance();
                JSONObject response = new JSONObject();
                response.put("type", RESP_AVAILABLE_EMOJIS);
                response.put("emojis", new JSONArray(registry.getAvailableEmojis()));

                String current = registry.getCurrentEmoji();
                if (current != null) {
                    response.put("current_emoji", current);
                }

                response.put("timestamp", System.currentTimeMillis());
                sendJson(response);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating available emojis response", e);
                sendError("EMOJI_ERROR", "Failed to get available emojis");
            }
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
                
                // Add event system information
                if (eventRepository != null) {
                    response.put("event_count", eventRepository.getEventCount());
                    response.put("enabled_event_count", eventRepository.getEnabledEventCount());
                    
                    // Include next scheduled event if any
                    ScheduledEvent nextEvent = eventRepository.getNextScheduledEvent();
                    if (nextEvent != null) {
                        response.put("next_event", nextEvent.toJson());
                    }
                }
                
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

            // Re-register NSD service to ensure discoverability after logout
            // This forces a fresh mDNS announcement for new pairing attempts
            refreshNsdRegistration();

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

    /**
     * Broadcast event_triggered notification to all connected clients.
     * Called when a scheduled event (alarm/reminder) fires.
     *
     * @param event The triggered event
     */
    public void broadcastEventTriggered(@NonNull ScheduledEvent event) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", RESP_EVENT_TRIGGERED);
            message.put("event", event.toJson());
            message.put("trigger_timestamp", System.currentTimeMillis());
            broadcastToClients(message);
            Log.d(TAG, "Broadcasted event_triggered for: " + event.getId());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating event_triggered broadcast", e);
        }
    }

    /**
     * Broadcast event_dismissed notification to all connected clients.
     * Called when a scheduled event is dismissed (user performed termination signal).
     *
     * @param event The dismissed event
     * @param dismissedBy How the event was dismissed (e.g., "gesture", "timeout", "manual")
     */
    public void broadcastEventDismissed(@NonNull ScheduledEvent event, @NonNull String dismissedBy) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", RESP_EVENT_DISMISSED);
            message.put("event_id", event.getId());
            message.put("dismissed_by", dismissedBy);
            message.put("timestamp", System.currentTimeMillis());
            broadcastToClients(message);
            Log.d(TAG, "Broadcasted event_dismissed for: " + event.getId() + " by: " + dismissedBy);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating event_dismissed broadcast", e);
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
