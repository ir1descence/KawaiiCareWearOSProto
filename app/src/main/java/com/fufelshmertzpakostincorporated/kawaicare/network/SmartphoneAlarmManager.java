package com.fufelshmertzpakostincorporated.kawaicare.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smartphone-side client for managing alarms on the Wear OS device.
 * Communicates with TcpWearService over TCP using JSON protocol.
 * 
 * Features:
 * - Connect/disconnect management
 * - Automatic pairing workflow
 * - Create, update, delete alarms
 * - Sync alarm state with watch
 * - Response callbacks on main thread
 * 
 * Usage:
 * <pre>
 * SmartphoneAlarmManager manager = new SmartphoneAlarmManager();
 * manager.setListener(new SmartphoneAlarmManager.AlarmManagerListener() { ... });
 * manager.connect("192.168.1.100", 8888, savedToken);
 * manager.setAlarm(System.currentTimeMillis() + 3600000, "Wake Up", "SIGNAL_SHAKE");
 * </pre>
 */
public class SmartphoneAlarmManager {

    private static final String TAG = "SmartphoneAlarmManager";
    private static final int DEFAULT_PORT = 8888;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int MAX_PACKET_SIZE = 65536;

    // Commands
    private static final String CMD_PAIR = "pair";
    private static final String CMD_SET_ALARM = "set_alarm";
    private static final String CMD_DELETE_ALARM = "delete_alarm";
    private static final String CMD_GET_ALARMS = "get_alarms";
    private static final String CMD_SET_ALARM_STATUS = "set_alarm_status";
    private static final String CMD_REQUEST_LOGOUT = "request_logout";
    private static final String CMD_GET_AUTH_STATUS = "get_auth_status";
    private static final String CMD_GET_STATUS = "get_status";
    private static final String CMD_PING = "ping";

    // Connection state
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private String authToken;

    // Threading
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread readerThread;

    // Listener
    private AlarmManagerListener listener;

    // Local alarm cache
    private final List<AlarmData> localAlarms = new CopyOnWriteArrayList<>();

    /**
     * Listener interface for alarm manager events.
     */
    public interface AlarmManagerListener {
        /** Called when successfully connected to watch */
        void onConnected(boolean authenticated);

        /** Called when disconnected from watch */
        void onDisconnected(String reason);

        /** Called when pairing is required - show UI to get code from user */
        void onPairingRequired(int codeLength, int expiresInSeconds);

        /** Called when pairing succeeds */
        void onPairingSuccess(String token);

        /** Called when pairing fails */
        void onPairingFailed(String errorCode, String errorMessage);

        /** Called when an alarm is created on the watch */
        void onAlarmCreated(AlarmData alarm);

        /** Called when an alarm is updated on the watch */
        void onAlarmUpdated(AlarmData alarm);

        /** Called when an alarm is deleted from the watch */
        void onAlarmDeleted(String alarmId);

        /** Called when alarm list is received */
        void onAlarmsReceived(List<AlarmData> alarms);

        /** Called on any error */
        void onError(String errorCode, String errorMessage);
    }

    /**
     * Simple alarm data class for smartphone-side storage.
     */
    public static class AlarmData {
        public final String id;
        public final long timeMillis;
        public final String label;
        public final String stopSignal;
        public boolean enabled;
        public final long createdAt;

        public AlarmData(String id, long timeMillis, String label, 
                        String stopSignal, boolean enabled, long createdAt) {
            this.id = id;
            this.timeMillis = timeMillis;
            this.label = label;
            this.stopSignal = stopSignal;
            this.enabled = enabled;
            this.createdAt = createdAt;
        }

        public static AlarmData fromJson(JSONObject json) throws JSONException {
            return new AlarmData(
                    json.getString("id"),
                    json.getLong("time_millis"),
                    json.optString("label", ""),
                    json.optString("stop_signal", "SIGNAL_SHAKE"),
                    json.optBoolean("enabled", true),
                    json.optLong("created_at", System.currentTimeMillis())
            );
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("time_millis", timeMillis);
            json.put("label", label);
            json.put("stop_signal", stopSignal);
            json.put("enabled", enabled);
            json.put("created_at", createdAt);
            return json;
        }
    }

    /**
     * Set the listener for manager events.
     */
    public void setListener(@Nullable AlarmManagerListener listener) {
        this.listener = listener;
    }

    /**
     * Check if connected to the watch.
     */
    public boolean isConnected() {
        return connected.get() && socket != null && socket.isConnected();
    }

    /**
     * Check if authenticated with the watch.
     */
    public boolean isAuthenticated() {
        return authenticated.get() && authToken != null;
    }

    /**
     * Get the current auth token.
     */
    @Nullable
    public String getAuthToken() {
        return authToken;
    }

    /**
     * Get the local alarm cache.
     */
    @NonNull
    public List<AlarmData> getLocalAlarms() {
        return new ArrayList<>(localAlarms);
    }

    // =========================================
    // Connection Management
    // =========================================

    /**
     * Connect to the watch.
     *
     * @param host      Watch IP address
     * @param port      TCP port (default 8888)
     * @param token     Existing auth token (or null to trigger pairing)
     */
    public void connect(@NonNull String host, int port, @Nullable String token) {
        this.authToken = token;
        
        executor.submit(() -> {
            try {
                Log.d(TAG, "Connecting to " + host + ":" + port);

                socket = new Socket(InetAddress.getByName(host), port);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);

                reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                connected.set(true);

                // Start reader thread
                startReaderThread();

                Log.i(TAG, "Connected to watch");

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                notifyError("CONNECTION_FAILED", e.getMessage());
                disconnect("Connection failed: " + e.getMessage());
            }
        });
    }

    /**
     * Connect with default port.
     */
    public void connect(@NonNull String host, @Nullable String token) {
        connect(host, DEFAULT_PORT, token);
    }

    /**
     * Disconnect from the watch.
     */
    public void disconnect(@Nullable String reason) {
        if (!connected.compareAndSet(true, false)) {
            return;
        }

        Log.d(TAG, "Disconnecting: " + reason);
        authenticated.set(false);

        // Stop reader thread
        if (readerThread != null) {
            readerThread.interrupt();
        }

        // Close socket
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {}

        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}

        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}

        final String finalReason = reason != null ? reason : "Unknown";
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onDisconnected(finalReason);
            }
        });
    }

    /**
     * Start the reader thread for incoming messages.
     */
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();

            while (connected.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        disconnect("Server closed connection");
                        break;
                    }

                    processIncomingData(line, buffer);

                } catch (SocketTimeoutException e) {
                    // Normal timeout, continue
                } catch (SocketException e) {
                    if (connected.get()) {
                        disconnect("Socket error: " + e.getMessage());
                    }
                    break;
                } catch (IOException e) {
                    if (connected.get()) {
                        disconnect("Read error: " + e.getMessage());
                    }
                    break;
                }
            }
        }, "SmartphoneAlarmManager-Reader");

        readerThread.start();
    }

    /**
     * Process incoming data and extract JSON messages.
     */
    private void processIncomingData(String data, StringBuilder buffer) {
        if (data == null || data.isEmpty()) {
            return;
        }

        if (buffer.length() + data.length() > MAX_PACKET_SIZE) {
            buffer.setLength(0);
            return;
        }

        buffer.append(data);
        String content = buffer.toString().trim();

        int braceCount = 0;
        int startIndex = -1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (braceCount == 0) startIndex = i;
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0 && startIndex >= 0) {
                    String jsonStr = content.substring(startIndex, i + 1);
                    handleResponse(jsonStr);
                    content = content.substring(i + 1).trim();
                    i = -1;
                    startIndex = -1;
                }
            }
        }

        buffer.setLength(0);
        buffer.append(content);
    }

    /**
     * Handle a JSON response from the watch.
     */
    private void handleResponse(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");

            Log.d(TAG, "Received: " + type);

            switch (type) {
                case "welcome":
                    handleWelcome(json);
                    break;

                case "auth_status":
                    handleAuthStatus(json);
                    break;

                case "pairing_challenge":
                    handlePairingChallenge(json);
                    break;

                case "pairing_success":
                    handlePairingSuccess(json);
                    break;

                case "pairing_failed":
                    handlePairingFailed(json);
                    break;

                case "alarm_created":
                    handleAlarmCreated(json);
                    break;

                case "alarm_updated":
                    handleAlarmUpdated(json);
                    break;

                case "alarm_deleted":
                    handleAlarmDeleted(json);
                    break;

                case "alarms_list":
                    handleAlarmsList(json);
                    break;

                case "success":
                    Log.d(TAG, "Success: " + json.optString("message"));
                    break;

                case "error":
                case "unauthorized":
                    handleError(json);
                    break;

                case "pong":
                    Log.d(TAG, "Pong received");
                    break;

                default:
                    Log.w(TAG, "Unknown response type: " + type);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing response: " + jsonStr, e);
        }
    }

    // =========================================
    // Response Handlers
    // =========================================

    private void handleWelcome(JSONObject json) {
        boolean isAuthenticated = json.optBoolean("authenticated", false);
        
        if (isAuthenticated && authToken != null) {
            authenticated.set(true);
            Log.i(TAG, "Connected and authenticated");
        } else {
            authenticated.set(false);
            Log.i(TAG, "Connected but not authenticated");
        }

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnected(isAuthenticated);
            }
        });
    }

    private void handleAuthStatus(JSONObject json) {
        boolean isAuthenticated = json.optBoolean("authenticated", false);
        authenticated.set(isAuthenticated);
    }

    private void handlePairingChallenge(JSONObject json) {
        int codeLength = json.optInt("code_length", 6);
        int expiresIn = json.optInt("expires_in_seconds", 120);

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPairingRequired(codeLength, expiresIn);
            }
        });
    }

    private void handlePairingSuccess(JSONObject json) {
        String token = json.optString("token", null);
        if (token != null) {
            this.authToken = token;
            authenticated.set(true);
        }

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPairingSuccess(token);
            }
        });
    }

    private void handlePairingFailed(JSONObject json) {
        String errorCode = json.optString("error_code", "UNKNOWN");
        String errorMessage = json.optString("error_message", "Pairing failed");

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPairingFailed(errorCode, errorMessage);
            }
        });
    }

    private void handleAlarmCreated(JSONObject json) throws JSONException {
        JSONObject alarmJson = json.optJSONObject("alarm");
        if (alarmJson == null) return;

        AlarmData alarm = AlarmData.fromJson(alarmJson);
        localAlarms.add(alarm);

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAlarmCreated(alarm);
            }
        });
    }

    private void handleAlarmUpdated(JSONObject json) throws JSONException {
        JSONObject alarmJson = json.optJSONObject("alarm");
        if (alarmJson == null) return;

        AlarmData updated = AlarmData.fromJson(alarmJson);

        // Update local cache
        for (int i = 0; i < localAlarms.size(); i++) {
            if (localAlarms.get(i).id.equals(updated.id)) {
                localAlarms.set(i, updated);
                break;
            }
        }

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAlarmUpdated(updated);
            }
        });
    }

    private void handleAlarmDeleted(JSONObject json) {
        String alarmId = json.optString("alarm_id", null);
        if (alarmId == null) return;

        // Remove from local cache
        localAlarms.removeIf(a -> a.id.equals(alarmId));

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAlarmDeleted(alarmId);
            }
        });
    }

    private void handleAlarmsList(JSONObject json) throws JSONException {
        JSONArray alarmsArray = json.optJSONArray("alarms");
        if (alarmsArray == null) return;

        List<AlarmData> alarms = new ArrayList<>();
        for (int i = 0; i < alarmsArray.length(); i++) {
            alarms.add(AlarmData.fromJson(alarmsArray.getJSONObject(i)));
        }

        // Update local cache
        localAlarms.clear();
        localAlarms.addAll(alarms);

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAlarmsReceived(alarms);
            }
        });
    }

    private void handleError(JSONObject json) {
        String errorCode = json.optString("error_code", "UNKNOWN");
        String errorMessage = json.optString("error_message", "Unknown error");

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(errorCode, errorMessage);
            }
        });
    }

    // =========================================
    // Commands
    // =========================================

    /**
     * Send a JSON command to the watch.
     */
    private void sendCommand(JSONObject command) {
        if (!connected.get()) {
            Log.w(TAG, "Not connected, cannot send command");
            notifyError("NOT_CONNECTED", "Not connected to watch");
            return;
        }

        executor.submit(() -> {
            try {
                String jsonStr = command.toString();
                writer.write(jsonStr);
                writer.newLine();
                writer.flush();
                Log.d(TAG, "Sent: " + command.optString("command"));
            } catch (IOException e) {
                Log.e(TAG, "Error sending command", e);
                disconnect("Send error: " + e.getMessage());
            }
        });
    }

    /**
     * Start the pairing process.
     */
    public void startPairing() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_PAIR);
            cmd.put("step", "challenge");
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating pairing command", e);
        }
    }

    /**
     * Verify a pairing code.
     */
    public void verifyPairingCode(@NonNull String code) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_PAIR);
            cmd.put("step", "verify");
            cmd.put("code", code);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating verify command", e);
        }
    }

    /**
     * Create a new alarm on the watch.
     *
     * @param timeMillis  Trigger time in milliseconds since epoch
     * @param label       Human-readable label
     * @param stopSignal  Signal to dismiss (e.g., "SIGNAL_SHAKE")
     */
    public void setAlarm(long timeMillis, @Nullable String label, @Nullable String stopSignal) {
        if (!isAuthenticated()) {
            notifyError("NOT_AUTHENTICATED", "Must be authenticated to set alarms");
            return;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_SET_ALARM);
            cmd.put("token", authToken);
            cmd.put("time_millis", timeMillis);
            if (label != null) cmd.put("label", label);
            if (stopSignal != null) cmd.put("stop_signal", stopSignal);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating set_alarm command", e);
        }
    }

    /**
     * Delete an alarm from the watch.
     *
     * @param alarmId The alarm ID to delete
     */
    public void deleteAlarm(@NonNull String alarmId) {
        if (!isAuthenticated()) {
            notifyError("NOT_AUTHENTICATED", "Must be authenticated");
            return;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_DELETE_ALARM);
            cmd.put("token", authToken);
            cmd.put("alarm_id", alarmId);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating delete_alarm command", e);
        }
    }

    /**
     * Get all alarms from the watch.
     */
    public void getAlarms() {
        if (!isAuthenticated()) {
            notifyError("NOT_AUTHENTICATED", "Must be authenticated");
            return;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_GET_ALARMS);
            cmd.put("token", authToken);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating get_alarms command", e);
        }
    }

    /**
     * Enable or disable a specific alarm.
     *
     * @param alarmId The alarm ID
     * @param enabled true to enable, false to disable
     */
    public void setAlarmEnabled(@NonNull String alarmId, boolean enabled) {
        if (!isAuthenticated()) {
            notifyError("NOT_AUTHENTICATED", "Must be authenticated");
            return;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_SET_ALARM_STATUS);
            cmd.put("token", authToken);
            cmd.put("alarm_id", alarmId);
            cmd.put("status", enabled ? "ON" : "OFF");
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating set_alarm_status command", e);
        }
    }

    /**
     * Set the global alarm ON/OFF status (legacy).
     *
     * @param on true to turn alarm ON, false for OFF
     */
    public void setGlobalAlarmStatus(boolean on) {
        if (!isAuthenticated()) {
            notifyError("NOT_AUTHENTICATED", "Must be authenticated");
            return;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_SET_ALARM_STATUS);
            cmd.put("token", authToken);
            cmd.put("status", on ? "ON" : "OFF");
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating set_alarm_status command", e);
        }
    }

    /**
     * Get watch status.
     */
    public void getStatus() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_GET_STATUS);
            if (authToken != null) cmd.put("token", authToken);
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating get_status command", e);
        }
    }

    /**
     * Request logout from the watch.
     */
    public void logout() {
        if (!isAuthenticated()) {
            return;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_REQUEST_LOGOUT);
            cmd.put("token", authToken);
            sendCommand(cmd);
            
            // Clear local state
            authToken = null;
            authenticated.set(false);
            localAlarms.clear();
        } catch (JSONException e) {
            Log.e(TAG, "Error creating logout command", e);
        }
    }

    /**
     * Send a ping to keep connection alive.
     */
    public void ping() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", CMD_PING);
            cmd.put("timestamp", System.currentTimeMillis());
            sendCommand(cmd);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating ping command", e);
        }
    }

    /**
     * Shutdown the manager and release resources.
     */
    public void shutdown() {
        disconnect("Shutdown requested");
        executor.shutdown();
    }

    // =========================================
    // Helper Methods
    // =========================================

    private void notifyError(String code, String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(code, message);
            }
        });
    }
}
