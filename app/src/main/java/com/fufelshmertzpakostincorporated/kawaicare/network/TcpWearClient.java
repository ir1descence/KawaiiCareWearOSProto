package com.fufelshmertzpakostincorporated.kawaicare.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP Client for connecting to TcpWearService on a Wear OS device.
 * 
 * This class can be used in companion phone apps or other devices
 * to communicate with the watch over local Wi-Fi.
 * 
 * Features:
 * - Automatic reconnection on connection loss
 * - Callback-based async API
 * - Thread-safe message sending
 * - Proper resource cleanup
 * 
 * Usage:
 * 1. Create instance with TcpWearClient(host, port, listener)
 * 2. Call connect() to establish connection
 * 3. Use sendCommand() methods to communicate
 * 4. Call disconnect() when done
 */
public class TcpWearClient {

    private static final String TAG = "TcpWearClient";

    // Connection settings
    private static final int CONNECT_TIMEOUT_MS = 10000; // 10 seconds
    private static final int READ_TIMEOUT_MS = 30000; // 30 seconds

    // Connection state
    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(false);

    // Threading
    private ExecutorService executor;
    private Thread readerThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Listeners
    private final CopyOnWriteArrayList<ConnectionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Listener interface for connection events and responses.
     */
    public interface ConnectionListener {
        /** Called when connection is established */
        void onConnected();
        
        /** Called when connection is lost */
        void onDisconnected(String reason);
        
        /** Called when a message is received from the server */
        void onMessageReceived(JSONObject message);
        
        /** Called when an error occurs */
        void onError(String error);
    }

    /**
     * Create a new TCP client.
     * 
     * @param host The watch's IP address
     * @param port The server port (default 8888)
     */
    public TcpWearClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Add a connection listener.
     */
    public void addListener(ConnectionListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Remove a connection listener.
     */
    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Connect to the watch service.
     * This is an async operation - results are delivered via callbacks.
     */
    public void connect() {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected");
            return;
        }

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newCachedThreadPool();
        }

        executor.submit(this::doConnect);
    }

    /**
     * Connect with auto-reconnect enabled.
     */
    public void connectWithReconnect() {
        shouldReconnect.set(true);
        connect();
    }

    private void doConnect() {
        try {
            Log.d(TAG, "Connecting to " + host + ":" + port);

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            isConnected.set(true);
            Log.i(TAG, "Connected to watch service");

            // Notify listeners
            notifyConnected();

            // Start reader thread
            startReaderThread();

        } catch (SocketTimeoutException e) {
            Log.e(TAG, "Connection timeout");
            notifyError("Connection timeout");
            handleDisconnect("Connection timeout");
        } catch (IOException e) {
            Log.e(TAG, "Connection failed", e);
            notifyError("Connection failed: " + e.getMessage());
            handleDisconnect("Connection failed");
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();

            while (isConnected.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        Log.d(TAG, "Server closed connection");
                        handleDisconnect("Server closed connection");
                        break;
                    }

                    // Process the line
                    processIncomingData(line, buffer);

                } catch (SocketTimeoutException e) {
                    // Timeout is normal, continue
                } catch (SocketException e) {
                    if (isConnected.get()) {
                        Log.w(TAG, "Socket exception: " + e.getMessage());
                        handleDisconnect("Socket error");
                    }
                    break;
                } catch (IOException e) {
                    if (isConnected.get()) {
                        Log.e(TAG, "Read error", e);
                        handleDisconnect("Read error");
                    }
                    break;
                }
            }
        }, "TcpWearClient-Reader");

        readerThread.start();
    }

    private void processIncomingData(String data, StringBuilder buffer) {
        if (data == null || data.isEmpty()) {
            return;
        }

        buffer.append(data);

        // Extract complete JSON objects
        String content = buffer.toString().trim();
        int braceCount = 0;
        int startIndex = -1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (braceCount == 0) {
                    startIndex = i;
                }
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0 && startIndex >= 0) {
                    String jsonStr = content.substring(startIndex, i + 1);
                    handleJsonMessage(jsonStr);

                    content = content.substring(i + 1).trim();
                    i = -1;
                    startIndex = -1;
                }
            }
        }

        buffer.setLength(0);
        buffer.append(content);
    }

    private void handleJsonMessage(String jsonStr) {
        try {
            JSONObject message = new JSONObject(jsonStr);
            Log.d(TAG, "Received: " + message.optString("type", "unknown"));
            notifyMessageReceived(message);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse JSON: " + jsonStr);
        }
    }

    private void handleDisconnect(String reason) {
        boolean wasConnected = isConnected.getAndSet(false);
        
        closeResources();

        if (wasConnected) {
            notifyDisconnected(reason);

            // Auto-reconnect if enabled
            if (shouldReconnect.get()) {
                Log.d(TAG, "Will attempt reconnect in 5 seconds...");
                mainHandler.postDelayed(this::connect, 5000);
            }
        }
    }

    /**
     * Disconnect from the watch service.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        isConnected.set(false);
        closeResources();
        
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private void closeResources() {
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {}

        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}

        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    /**
     * Check if currently connected.
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    // =========================================
    // Command Methods
    // =========================================

    /**
     * Send a raw JSON command.
     */
    public void sendCommand(JSONObject command) {
        if (!isConnected.get()) {
            notifyError("Not connected");
            return;
        }

        executor.submit(() -> {
            try {
                synchronized (writer) {
                    writer.write(command.toString());
                    writer.newLine();
                    writer.flush();
                }
                Log.d(TAG, "Sent: " + command.optString("command", "unknown"));
            } catch (IOException e) {
                Log.e(TAG, "Send failed", e);
                handleDisconnect("Send failed");
            }
        });
    }

    /**
     * Set the alarm status.
     * @param isOn true for ON, false for OFF
     */
    public void setAlarmStatus(boolean isOn) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_SET_ALARM_STATUS);
            cmd.put("status", isOn ? "ON" : "OFF");
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Set the animation state.
     * @param state The animation state name (e.g., "IDLE", "HAPPY")
     */
    public void setAnimationState(String state) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_SET_ANIMATION_STATE);
            cmd.put("state", state);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Set the active gesture for alarm dismissal.
     * @param signal The signal constant (e.g., "SIGNAL_SHAKE")
     */
    public void setActiveGesture(String signal) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_SET_ACTIVE_GESTURE);
            cmd.put("signal", signal);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Start recording a custom gesture on the watch.
     */
    public void startRecording() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_START_RECORDING);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Stop recording a custom gesture on the watch.
     */
    public void stopRecording() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_STOP_RECORDING);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Request the list of available emotions/animations.
     */
    public void requestEmotions() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_REQUEST_EMOTIONS);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Request the list of supported signals.
     */
    public void requestSignals() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_REQUEST_SIGNALS);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Send a ping to check connection health.
     */
    public void ping() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_PING);
            cmd.put("timestamp", System.currentTimeMillis());
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    /**
     * Request the current server status.
     */
    public void getStatus() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", TcpProtocolConstants.CMD_GET_STATUS);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create command", e);
        }
    }

    // =========================================
    // Notification Helpers
    // =========================================

    private void notifyConnected() {
        mainHandler.post(() -> {
            for (ConnectionListener listener : listeners) {
                listener.onConnected();
            }
        });
    }

    private void notifyDisconnected(String reason) {
        mainHandler.post(() -> {
            for (ConnectionListener listener : listeners) {
                listener.onDisconnected(reason);
            }
        });
    }

    private void notifyMessageReceived(JSONObject message) {
        mainHandler.post(() -> {
            for (ConnectionListener listener : listeners) {
                listener.onMessageReceived(message);
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            for (ConnectionListener listener : listeners) {
                listener.onError(error);
            }
        });
    }
}
