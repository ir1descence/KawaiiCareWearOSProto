# TCP Wear Service Protocol Documentation

## Overview

The `TcpWearService` replaces Google's WearableListenerService (Data Layer/Message API) with a custom TCP Socket Server architecture. This enables communication with non-Google devices on a local Wi-Fi network.

**Key Features:**
- Secure pairing with 6-digit codes displayed on watch
- Token-based authentication for all protected commands
- Remote logout capability (no on-watch logout button)
- Single active device constraint

## Architecture

```
┌─────────────────┐         Wi-Fi Network         ┌─────────────────┐
│   Wear OS       │◄──────────────────────────────│   Client App    │
│   TcpWearService│      TCP Socket (Port 8888)   │   (Phone/Other) │
│   + NSD         │                               │   + NSD Discovery│
│   + Auth        │                               │   + Token Storage│
└─────────────────┘                               └─────────────────┘
```

## Security Model

### Authentication States

1. **Unauthorized (Not Paired)**: Watch has no stored auth token
   - Only accepts: `ping`, `get_auth_status`, `pair`
   - All other commands return `unauthorized` error

2. **Authenticated (Paired)**: Watch has valid auth token stored
   - Accepts all commands WITH valid token
   - Rejects new pairing requests
   - Single device active at a time

### Token Security
- Tokens stored using `EncryptedSharedPreferences` (AES-256)
- Tokens are UUID-based with timestamp and random components
- Clients must include token in every authorized request

---

## Pairing Workflow

### Step 1: Check Auth Status
```json
→ {"command": "get_auth_status"}
← {"type": "auth_status", "authenticated": false, "timestamp": 1234567890}
```

### Step 2: Initiate Pairing Challenge
```json
→ {"command": "pair", "step": "challenge"}
← {
    "type": "pairing_challenge",
    "message": "Pairing code displayed on watch. Enter the 6-digit code.",
    "code_length": 6,
    "expires_in_seconds": 120,
    "timestamp": 1234567890
  }
```
*A 6-digit code is displayed on the watch screen*

### Step 3: Verify Pairing Code
```json
→ {"command": "pair", "step": "verify", "code": "123456"}
← {
    "type": "pairing_success",
    "message": "Pairing successful! Device is now authorized.",
    "token": "550e8400-e29b-41d4-a716-446655440000-18d1a2b3c4d5-a1b2c3d4e5f6",
    "timestamp": 1234567890
  }
```

### Pairing Errors
```json
← {"type": "pairing_failed", "error_code": "INVALID_CODE", "error_message": "Incorrect pairing code.", "timestamp": 1234567890}
← {"type": "pairing_failed", "error_code": "PAIRING_EXPIRED", "error_message": "Pairing code has expired.", "timestamp": 1234567890}
← {"type": "error", "error_code": "PAIRING_IN_PROGRESS", "error_message": "Another device is currently pairing", "timestamp": 1234567890}
← {"type": "error", "error_code": "ALREADY_PAIRED", "error_message": "Device is already paired.", "timestamp": 1234567890}
```

---

## Command Reference

### Commands Available Without Authentication

#### 1. Get Auth Status
```json
→ {"command": "get_auth_status"}
← {"type": "auth_status", "authenticated": true|false, "timestamp": 1234567890}
```

#### 2. Ping (Keep-Alive)
```json
→ {"command": "ping", "timestamp": 1234567890}
← {"type": "pong", "client_timestamp": 1234567890, "server_timestamp": 1234567891, "authenticated": true|false}
```

#### 3. Pair (when unauthorized)
See Pairing Workflow above.

---

### Commands Requiring Authentication

All authorized commands MUST include the `token` field:
```json
{"command": "<command>", "token": "<auth_token>", ...}
```

---

## Alarm Management Commands

The system supports full alarm management with system-level AlarmManager integration for precise timing.

### 1. Create Alarm (set_alarm)

Schedule a new alarm on the watch. The alarm will be persisted and survive reboots.

```json
→ {
    "command": "set_alarm",
    "token": "...",
    "time_millis": 1712345678000,
    "label": "Morning Wakeup",
    "stop_signal": "SIGNAL_SHAKE"
  }
← {
    "type": "alarm_created",
    "message": "Alarm scheduled successfully",
    "alarm": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "time_millis": 1712345678000,
      "label": "Morning Wakeup",
      "stop_signal": "SIGNAL_SHAKE",
      "enabled": true,
      "created_at": 1712340000000
    },
    "timestamp": 1712340000000
  }
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `time_millis` | long | Yes | Trigger time in milliseconds since epoch (must be future) |
| `label` | string | No | Human-readable label for the alarm |
| `stop_signal` | string | No | Signal to dismiss alarm (default: SIGNAL_SHAKE) |

**Supported stop_signal values:**
- `SIGNAL_SHAKE` - Shake the watch (default)
- `SIGNAL_INCLINE` - Tilt the watch
- `SIGNAL_CIRCLE` - Circular gesture (requires gyroscope)
- `SIGNAL_LONG_TOUCH` - Long press on screen
- `SIGNAL_CUSTOM` - User-recorded custom gesture

**Errors:**
- `INVALID_TIME` - time_millis is missing, invalid, or in the past
- `validation_error` - stop_signal is not supported by device hardware

### 2. Delete Alarm (delete_alarm)

Remove an existing alarm from the watch.

```json
→ {"command": "delete_alarm", "token": "...", "alarm_id": "550e8400-e29b-41d4-a716-446655440000"}
← {
    "type": "alarm_deleted",
    "message": "Alarm deleted successfully",
    "alarm_id": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": 1712340000000
  }
```

**Errors:**
- `MISSING_ALARM_ID` - alarm_id not provided
- `ALARM_NOT_FOUND` - No alarm with this ID exists

### 3. Get All Alarms (get_alarms)

Retrieve the list of all alarms on the watch.

```json
→ {"command": "get_alarms", "token": "..."}
← {
    "type": "alarms_list",
    "alarms": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "time_millis": 1712345678000,
        "label": "Morning Wakeup",
        "stop_signal": "SIGNAL_SHAKE",
        "enabled": true,
        "created_at": 1712340000000
      },
      {
        "id": "660e8400-e29b-41d4-a716-446655440001",
        "time_millis": 1712389200000,
        "label": "Meeting",
        "stop_signal": "SIGNAL_INCLINE",
        "enabled": false,
        "created_at": 1712340100000
      }
    ],
    "count": 2,
    "timestamp": 1712340000000
  }
```

### 4. Toggle Alarm Status (set_alarm_status)

Enable or disable a specific alarm, or set the global alarm state.

**For a specific alarm:**
```json
→ {"command": "set_alarm_status", "token": "...", "alarm_id": "550e8400-...", "status": "ON"}
← {
    "type": "alarm_updated",
    "message": "Alarm enabled",
    "alarm": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "time_millis": 1712345678000,
      "label": "Morning Wakeup",
      "stop_signal": "SIGNAL_SHAKE",
      "enabled": true,
      "created_at": 1712340000000
    },
    "timestamp": 1712340000000
  }
```

**For global alarm state (legacy):**
```json
→ {"command": "set_alarm_status", "token": "...", "status": "ON"}
← {"type": "success", "message": "Alarm status set to: ON", "timestamp": 1712340000000}
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `status` | string | Yes | "ON" or "OFF" |
| `alarm_id` | string | No | Specific alarm ID (omit for global state) |

---

## Other Authenticated Commands

#### 1. Set Animation State
```json
→ {"command": "set_animation_state", "token": "...", "state": "HAPPY"}
← {"type": "success", "message": "Animation state set to: HAPPY", "timestamp": 1234567890}
```

Supported states: Refer to `AnimationRenderer.AnimState` enum (IDLE, HAPPY, SAD, etc.)

#### 2. Set Active Gesture (Alarm Dismissal)
```json
→ {"command": "set_active_gesture", "token": "...", "signal": "SIGNAL_SHAKE"}
← {"type": "success", "message": "Active gesture set to: SIGNAL_SHAKE", "timestamp": 1234567890}
```

Supported signals:
- `SIGNAL_INCLINE` - Watch tilt/raise to wake
- `SIGNAL_CIRCLE` - Circular gesture (requires gyroscope)
- `SIGNAL_SHAKE` - Shake to dismiss (requires accelerometer)
- `SIGNAL_LONG_TOUCH` - Long press on screen
- `SIGNAL_CUSTOM` - User-recorded custom gesture

#### 3. Start Recording Custom Gesture
```json
→ {"command": "start_recording", "token": "..."}
← {"type": "success", "message": "Learning mode started", "timestamp": 1234567890}
```

#### 4. Stop Recording Custom Gesture
```json
→ {"command": "stop_recording", "token": "..."}
← {"type": "success", "message": "Learning mode stopped", "timestamp": 1234567890}
```

#### 5. Request Available Emotions
```json
→ {"command": "request_emotions", "token": "..."}
← {"type": "emotions", "emotions": ["happy", "sad", "idle", "sleeping"], "timestamp": 1234567890}
```

#### 6. Request Supported Signals
```json
→ {"command": "request_signals", "token": "..."}
← {
    "type": "signals",
    "signals": ["SIGNAL_INCLINE", "SIGNAL_SHAKE", "SIGNAL_LONG_TOUCH"],
    "details": {"status": "success", "signals": [...], "timestamp": ...},
    "timestamp": 1234567890
  }
```

#### 7. Get Status
```json
→ {"command": "get_status", "token": "..."}
← {
    "type": "status",
    "alarm_on": false,
    "active_signal": "SIGNAL_SHAKE",
    "server_name": "KawaiiCareWear",
    "authenticated": true,
    "timestamp": 1234567890
  }
```

#### 8. Request Logout (Remote Logout)
```json
→ {"command": "request_logout", "token": "..."}
← {
    "type": "logout_success",
    "message": "Device logged out successfully. Connection will be closed.",
    "timestamp": 1234567890
  }
```
*Connection is terminated after this response.*

---

## Error Responses

### Unauthorized Error
Returned when a command requires authentication but token is missing or invalid:
```json
{
  "type": "unauthorized",
  "error_code": "UNAUTHORIZED",
  "error_message": "Invalid or missing authentication token",
  "timestamp": 1234567890
}
```

### Another Device Active
Only one authorized device can control the watch at a time:
```json
{
  "type": "error",
  "error_code": "ANOTHER_DEVICE_ACTIVE",
  "error_message": "Another device is currently controlling the watch",
  "timestamp": 1234567890
}
```

### General Error Response
```json
{
  "type": "error",
  "error_code": "ERROR_CODE",
  "error_message": "Human readable message",
  "timestamp": 1234567890
}
```

### Common Error Codes
| Code | Description |
|------|-------------|
| `UNAUTHORIZED` | Missing or invalid token |
| `UNKNOWN_COMMAND` | Command not recognized |
| `INVALID_JSON` | Failed to parse JSON |
| `PACKET_TOO_LARGE` | Message exceeds 64KB |
| `ALREADY_PAIRED` | Cannot pair when already authenticated |
| `PAIRING_IN_PROGRESS` | Another device is pairing |
| `ANOTHER_DEVICE_ACTIVE` | Another client has active session |

---

## Connection Lifecycle

### Welcome Message (automatic on connect)
```json
{
  "type": "welcome",
  "server": "KawaiiCareWear",
  "version": "1.0",
  "authenticated": true|false,
  "timestamp": 1234567890
}
```

### Recommended Flow

1. **Connect** → Receive welcome message
2. **Check `authenticated` field** in welcome
3. **If `authenticated: false`**:
   - Initiate pairing with `pair` + `challenge`
   - Display code prompt to user
   - Verify with `pair` + `verify` + code
   - Store received token securely
4. **If `authenticated: true`**:
   - Use stored token for all commands
5. **Send periodic pings** for connection health
6. **Handle disconnects** gracefully

---

## Service Discovery (NSD)

The service registers via mDNS/DNS-SD:
- **Service Type**: `_kawaicare._tcp.`
- **Service Name**: `KawaiiCareWear`
- **Port**: 8888 (default)

### Discovery Example (Android)
```java
NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
    @Override
    public void onServiceFound(NsdServiceInfo service) {
        if (service.getServiceType().equals("_kawaicare._tcp.")) {
            // Found KawaiiCare watch!
            nsdManager.resolveService(service, resolveListener);
        }
    }
    // ... other callbacks
};

nsdManager.discoverServices("_kawaicare._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
```

---

## Sample Client Code (Python)

```python
import socket
import json
import threading

class KawaiiCareClient:
    def __init__(self, host, port=8888):
        self.host = host
        self.port = port
        self.sock = None
        self.token = None
    
    def connect(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))
        self.sock.settimeout(30)
        
        # Read welcome message
        welcome = self._read_response()
        print(f"Connected: {welcome}")
        return welcome.get('authenticated', False)
    
    def _send(self, data):
        message = json.dumps(data) + '\n'
        self.sock.sendall(message.encode('utf-8'))
    
    def _read_response(self):
        data = self.sock.recv(4096).decode('utf-8')
        return json.loads(data.strip())
    
    def pair(self, code_callback):
        """Pair with the watch. code_callback receives the code to display."""
        # Request challenge
        self._send({"command": "pair", "step": "challenge"})
        response = self._read_response()
        
        if response['type'] != 'pairing_challenge':
            raise Exception(f"Unexpected response: {response}")
        
        # Get code from user
        code = code_callback()
        
        # Verify code
        self._send({"command": "pair", "step": "verify", "code": code})
        response = self._read_response()
        
        if response['type'] == 'pairing_success':
            self.token = response['token']
            return True
        else:
            raise Exception(f"Pairing failed: {response}")
    
    def send_command(self, command, **kwargs):
        """Send an authorized command."""
        if not self.token:
            raise Exception("Not authenticated. Call pair() first.")
        
        data = {"command": command, "token": self.token, **kwargs}
        self._send(data)
        return self._read_response()
    
    def set_alarm(self, on):
        return self.send_command("set_alarm_status", status="ON" if on else "OFF")
    
    def set_animation(self, state):
        return self.send_command("set_animation_state", state=state)
    
    def logout(self):
        response = self.send_command("request_logout")
        self.token = None
        self.sock.close()
        return response

# Usage
client = KawaiiCareClient('192.168.1.100')
is_authenticated = client.connect()

if not is_authenticated:
    # Pair with the watch
    def get_code():
        return input("Enter the 6-digit code shown on watch: ")
    
    client.pair(get_code)
    print("Paired successfully!")

# Use the watch
client.set_animation("HAPPY")
client.set_alarm(True)

# When done
client.logout()
```

---

## Permissions Required

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## Important Notes

1. **No Watch Logout Button**: Logout is ONLY triggered by the connected device via `request_logout`. This is by design.

2. **Single Active Device**: Only one client can have an active authorized session. Other clients with valid tokens will receive `ANOTHER_DEVICE_ACTIVE` error.

3. **Token Storage**: Clients should store tokens securely (e.g., Android Keystore, iOS Keychain).

4. **Pairing Timeout**: Pairing codes expire after 2 minutes (120 seconds).

5. **Connection Keep-Alive**: Send periodic `ping` commands to maintain connection health.

6. **Foreground Service**: The TCP server runs as a foreground service to prevent Android from killing it.
