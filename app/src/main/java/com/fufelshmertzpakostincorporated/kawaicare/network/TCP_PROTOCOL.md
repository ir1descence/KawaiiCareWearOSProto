# TCP Wear Service Protocol Documentation

## Overview

The `TcpWearService` replaces Google's WearableListenerService (Data Layer/Message API) with a custom TCP Socket Server architecture. This enables communication with non-Google devices on a local Wi-Fi network.

**Key Features:**
- Secure pairing with 6-digit codes displayed on watch
- Token-based authentication for all protected commands
- Remote logout capability (no on-watch logout button)
- Single active device constraint
- Unified Scheduled Event system (Alarms and Reminders)

## Architecture

```
┌─────────────────┐         Wi-Fi Network         ┌─────────────────┐
│   Wear OS       │◄──────────────────────────────│   Client App    │
│   TcpWearService│      TCP Socket (Port 8888)   │   (Phone/Other) │
│   + NSD         │                               │   + NSD Discovery│
│   + Auth        │                               │   + Token Storage│
│   + Events      │                               │   + EventSync    │
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

### Step 3a: Cancel Pairing (optional)
The client can cancel an in-progress pairing at any time. This clears the challenge on the watch and dismisses the pairing code dialog.
```json
→ {"command": "pair", "step": "cancel"}
← {
    "type": "pairing_cancelled",
    "message": "Pairing cancelled.",
    "timestamp": 1234567890
  }
```

### Pairing Errors
```json
← {"type": "pairing_failed", "error_code": "INVALID_CODE", "error_message": "Incorrect pairing code.", "timestamp": 1234567890}
← {"type": "pairing_failed", "error_code": "PAIRING_EXPIRED", "error_message": "Pairing code has expired.", "timestamp": 1234567890}
← {"type": "error", "error_code": "PAIRING_IN_PROGRESS", "error_message": "Another device is currently pairing", "timestamp": 1234567890}
← {"type": "error", "error_code": "ALREADY_PAIRED", "error_message": "Device is already paired.", "timestamp": 1234567890}
← {"type": "error", "error_code": "NO_PAIRING_INITIATED", "error_message": "No pairing challenge initiated by this client.", "timestamp": 1234567890}
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

## Scheduled Event System

The unified event system supports both **Alarms** and **Reminders** with a common data model. Events are scheduled using Android's AlarmManager for precise timing.

### ScheduledEvent Data Model

Every event includes the following properties:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique event identifier (UUID) |
| `event_type` | string | Yes | "ALARM" or "REMINDER" |
| `trigger_time_millis` | long | Yes | Trigger time in UTC milliseconds since epoch |
| `label` | string | No | Human-readable label |
| `recurrence` | object | No | Recurrence configuration (see below) |
| `payload` | object | No | Animation and sound configuration |
| `termination_signal` | string | No | Gesture to dismiss (default: SIGNAL_SHAKE) |
| `enabled` | boolean | No | Whether event is active (default: true) |
| `created_at` | long | No | Creation timestamp |
| `last_triggered_at` | long | No | Last trigger timestamp |

### Recurrence Configuration

```json
{
  "type": "NONE" | "DAILY" | "WEEKLY",
  "day_of_week_mask": 127  // Only for WEEKLY, bitmask (Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64)
}
```

**Recurrence Types:**
- `NONE` - One-time event (default)
- `DAILY` - Repeats every day at the same time
- `WEEKLY` - Repeats on selected days of the week

**Day-of-Week Bitmask Values:**
| Day | Value |
|-----|-------|
| Sunday | 1 |
| Monday | 2 |
| Tuesday | 4 |
| Wednesday | 8 |
| Thursday | 16 |
| Friday | 32 |
| Saturday | 64 |
| Weekdays (Mon-Fri) | 62 |
| Weekends (Sat-Sun) | 65 |
| Every Day | 127 |

### Event Payload

```json
{
  "animation": "ALARM",          // Animation state to play
  "sound_effect": "alarm_tone",  // Optional custom sound
  "vibrate": true,               // Enable vibration
  "vibration_pattern": "0,200,100,200"  // Optional pattern (ms)
}
```

**Supported Animation States:**
- `IDLE` - Default/resting state
- `TILTED` - Device tilted animation
- `GESTURE_ACTION` - Response animation (good for reminders)
- `SHAKE` - Shake animation
- `ALARM` - Alarm active animation (default for alarms)
- `LEARNING` - Gesture recording mode

### Termination Signals (validated by SignalRegistry)
- `SIGNAL_SHAKE` - Shake the watch (default)
- `SIGNAL_INCLINE` - Tilt the watch
- `SIGNAL_CIRCLE` - Circular gesture (requires gyroscope)
- `SIGNAL_LONG_TOUCH` - Long press on screen
- `SIGNAL_CUSTOM` - User-recorded custom gesture

---

## Event Management Commands

### 1. Sync Events (sync_events)

**Full synchronization** of all events from client to watch. Replaces all existing events.

```json
→ {
    "command": "sync_events",
    "token": "...",
    "events": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "event_type": "ALARM",
        "trigger_time_millis": 1712345678000,
        "label": "Morning Wakeup",
        "recurrence": {
          "type": "WEEKLY",
          "day_of_week_mask": 62
        },
        "payload": {
          "animation": "ALARM",
          "vibrate": true
        },
        "termination_signal": "SIGNAL_SHAKE",
        "enabled": true
      },
      {
        "id": "660e8400-e29b-41d4-a716-446655440001",
        "event_type": "REMINDER",
        "trigger_time_millis": 1712389200000,
        "label": "Take Medicine",
        "recurrence": {
          "type": "DAILY"
        },
        "payload": {
          "animation": "GESTURE_ACTION",
          "vibrate": true,
          "vibration_pattern": "0,200,100,200"
        },
        "termination_signal": "SIGNAL_LONG_TOUCH",
        "enabled": true
      }
    ]
  }
← {
    "type": "events_synced",
    "message": "Synchronized 2 events",
    "event_count": 2,
    "sync_timestamp": 1712340000000,
    "timestamp": 1712340000000
  }
```

### 2. Update Event (update_event)

Update a specific event by ID, or toggle its enabled state.

**Full event update:**
```json
→ {
    "command": "update_event",
    "token": "...",
    "event": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "event_type": "ALARM",
      "trigger_time_millis": 1712345678000,
      "label": "Updated Label",
      "recurrence": {"type": "DAILY"},
      "payload": {"animation": "ALARM", "vibrate": true},
      "termination_signal": "SIGNAL_INCLINE",
      "enabled": true
    }
  }
← {
    "type": "event_updated",
    "message": "Event updated successfully",
    "event": { /* full event object */ },
    "timestamp": 1712340000000
  }
```

**Toggle enabled state only:**
```json
→ {
    "command": "update_event",
    "token": "...",
    "event_id": "550e8400-e29b-41d4-a716-446655440000",
    "enabled": false
  }
← {
    "type": "event_updated",
    "message": "Event disabled",
    "event": { /* full event object */ },
    "timestamp": 1712340000000
  }
```

**Errors:**
- `MISSING_EVENT_DATA` - Neither `event` nor `event_id` provided
- `EVENT_NOT_FOUND` - Event with specified ID doesn't exist
- `INVALID_SIGNAL` - termination_signal not supported by device hardware

### 3. Get Events (get_events)

Retrieve all scheduled events from the watch.

```json
→ {"command": "get_events", "token": "..."}
← {
    "type": "events_list",
    "events": [
      {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "event_type": "ALARM",
        "trigger_time_millis": 1712345678000,
        "label": "Morning Wakeup",
        "recurrence": {"type": "WEEKLY", "day_of_week_mask": 62},
        "payload": {"animation": "ALARM", "vibrate": true},
        "termination_signal": "SIGNAL_SHAKE",
        "enabled": true,
        "created_at": 1712300000000,
        "last_triggered_at": 0
      }
    ],
    "count": 1,
    "last_sync_timestamp": 1712340000000,
    "timestamp": 1712340000000
  }
```

**Optional filter by type:**
```json
→ {"command": "get_events", "token": "...", "event_type": "ALARM"}
← {
    "type": "events_list",
    "events": [ /* only ALARM events */ ],
    "count": 1,
    "timestamp": 1712340000000
  }
```

### 4. Delete Event (delete_event)

Remove a specific event by ID.

```json
→ {"command": "delete_event", "token": "...", "event_id": "550e8400-e29b-41d4-a716-446655440000"}
← {
    "type": "event_deleted",
    "message": "Event deleted successfully",
    "event_id": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": 1712340000000
  }
```

**Errors:**
- `MISSING_EVENT_ID` - event_id not provided
- `EVENT_NOT_FOUND` - No event with this ID exists

### 5. Event Triggered Notification (event_triggered)

**Watch → Client notification** when an event triggers. This is a server-initiated message sent to connected clients.

```json
← {
    "type": "event_triggered",
    "event": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "event_type": "ALARM",
      "trigger_time_millis": 1712345678000,
      "label": "Morning Wakeup",
      "termination_signal": "SIGNAL_SHAKE",
      "enabled": true
    },
    "triggered_at": 1712345678123,
    "next_trigger_time": 1712432078000,
    "timestamp": 1712345678123
  }
```

**Fields:**
- `triggered_at` - Actual time the event fired
- `next_trigger_time` - Next scheduled occurrence for recurring events (-1 for one-time events)

### 6. Event Dismissed Notification (event_dismissed)

**Watch → Client notification** when an event is dismissed via gesture.

```json
← {
    "type": "event_dismissed",
    "event_id": "550e8400-e29b-41d4-a716-446655440000",
    "dismissed_at": 1712345700000,
    "dismissed_by": "SIGNAL_SHAKE",
    "duration_seconds": 22,
    "timestamp": 1712345700000
  }
```

---

## Legacy Alarm Commands (Deprecated)

The following commands are maintained for backwards compatibility but will be removed in a future version. Use the new event-based commands instead.

### set_alarm (Deprecated → use sync_events or update_event)
### delete_alarm (Deprecated → use delete_event)
### get_alarms (Deprecated → use get_events)
### set_alarm_status (Deprecated → use update_event)

---

## Other Authenticated Commands

#### 1. Set Animation State
Triggers an emotion/animation on the watch. The animation will play and then automatically return to IDLE state.

```json
→ {"command": "set_animation_state", "token": "...", "state": "GESTURE_ACTION"}
← {"type": "success", "message": "Animation state set to: GESTURE_ACTION (duration: auto-calculated)", "timestamp": 1234567890}
```

With explicit duration:
```json
→ {"command": "set_animation_state", "token": "...", "state": "SHAKE", "duration": 3000}
← {"type": "success", "message": "Animation state set to: SHAKE (duration: 3000ms)", "timestamp": 1234567890}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `state` | string | Yes | Animation state (see supported states below) |
| `duration` | number | No | Duration in milliseconds. Default: 0 (auto-calculate from frame count). Set >0 for explicit duration. |

**Duration Behavior:**
- `duration: 0` or omitted → Auto-calculated based on number of PNG frames × 33ms per frame
- `duration: 5000` → Plays for exactly 5 seconds then returns to IDLE
- `state: "IDLE"` → Immediately stops any playing animation and returns to IDLE

**Supported States:**
- `IDLE` - Default/resting state
- `TILTED` - Device tilted animation
- `GESTURE_ACTION` - Double-tap response animation
- `SHAKE` - Shake animation
- `ALARM` - Alarm active animation
- `LEARNING` - Gesture recording mode animation
- `NOTIFICATION_EMOJI` - Emoji eye-replacement animation (requires emoji to be set via `set_emoji`)

**Important:** During an external animation:
- Sensor-based state changes (tilt/shake) are blocked
- Subsequent TCP commands will interrupt the current animation
- Animation automatically returns to IDLE when duration expires

#### 2. Set Active Gesture (Global Alarm Dismissal)
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

#### 6. Set Emoji (Eye-Replacement Animation)

Set the emoji that replaces the avatar's eyes during the `NOTIFICATION_EMOJI` animation.

**Set emoji:**
```json
→ {"command": "set_emoji", "token": "...", "emoji": "😍"}
← {"type": "success", "message": "Emoji set to: 😍", "timestamp": 1234567890}
```

**Set emoji and play animation immediately:**
```json
→ {"command": "set_emoji", "token": "...", "emoji": "❤️", "play": true}
← {"type": "success", "message": "Emoji set to: ❤️ (animation playing)", "timestamp": 1234567890}
```

**Set emoji, play with explicit duration:**
```json
→ {"command": "set_emoji", "token": "...", "emoji": "🌟", "play": true, "duration": 5000}
← {"type": "success", "message": "Emoji set to: 🌟 (animation playing)", "timestamp": 1234567890}
```

**Clear emoji:**
```json
→ {"command": "set_emoji", "token": "...", "emoji": ""}
← {"type": "success", "message": "Emoji cleared", "timestamp": 1234567890}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `emoji` | string | Yes | Unicode emoji string (e.g. "😍"), or empty string to clear |
| `play` | boolean | No | If true, immediately trigger the NOTIFICATION_EMOJI animation. Default: false |
| `duration` | number | No | Duration in ms when `play` is true. 0 = auto-calculate from frames. Default: 0 |

**Animation Flow:**
1. Avatar blinks (eyes close) — `notification_emoji_before_blink` frames
2. Emoji appears where eyes were — composited overlay during configurable frame range
3. Emoji disappears — end of overlay zone
4. Avatar opens eyes — `notification_emoji_after_blink` frames
5. Cooldown period (1500ms default) before next emoji animation

**Errors:**
- `EMOJI_RENDER_FAILED` — Failed to render the emoji character to bitmap

#### 7. Request Available Emojis

Get the curated list of emojis suitable for the eye-replacement animation.

```json
→ {"command": "request_available_emojis", "token": "..."}
← {
    "type": "available_emojis",
    "emojis": ["❤️", "😍", "🥰", "😊", "🌟", "⭐", "✨", "💖", "💕", "💗", ...],
    "current_emoji": "😍",
    "timestamp": 1234567890
  }
```

The `current_emoji` field is only present if an emoji is currently selected.

#### 8. Request Supported Signals
```json
→ {"command": "request_signals", "token": "..."}
← {
    "type": "signals",
    "signals": ["SIGNAL_INCLINE", "SIGNAL_SHAKE", "SIGNAL_LONG_TOUCH"],
    "details": {"status": "success", "signals": [...], "timestamp": ...},
    "timestamp": 1234567890
  }
```

#### 9. Get Status
```json
→ {"command": "get_status", "token": "..."}
← {
    "type": "status",
    "active_event": null | { /* currently triggered event */ },
    "active_signal": "SIGNAL_SHAKE",
    "event_count": 5,
    "server_name": "KawaiiCareWear",
    "authenticated": true,
    "timestamp": 1234567890
  }
```

#### 10. Request Logout (Remote Logout)
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

## Troubleshooting NSD Discovery

### Connectivity Diagnostic (On-Watch)

The watch provides a built-in connectivity diagnostic that can be triggered programmatically:

1. **From the watch app**: Long-press (implementation-specific) or trigger `triggerConnectivityDiagnostic()` method
2. **Via Intent**: Send an intent with action `com.fufelshmertzpakostincorporated.kawaicare.RUN_DIAGNOSTIC`

The diagnostic will display:
- Server running status and port
- Wi-Fi connection status and IP address
- Wi-Fi SSID and signal strength
- WifiLock/WakeLock status
- NSD registration status and service name

### Common Issues and Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| Watch not found | Wi-Fi off during screen sleep | WifiLock now acquired automatically |
| NSD registration fails | Port conflict | Service uses dynamic unique naming |
| Intermittent discovery | Multicast blocked | Ensure `CHANGE_WIFI_MULTICAST_STATE` permission |
| Connection refused | Server not bound to all interfaces | Fixed: now binds to 0.0.0.0 |

### Manual Connection Fallback

If NSD discovery fails, use the diagnostic to get the watch's IP address, then connect directly:
```python
# Python example
client = KawaiiCareClient('192.168.1.XXX', port=8888)  # Use IP from diagnostic
client.connect()
```

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
| `EVENT_NOT_FOUND` | Event ID doesn't exist |
| `MISSING_EVENT_DATA` | Required event data not provided |
| `INVALID_SIGNAL` | Signal not supported by hardware |
| `INVALID_EVENT_TYPE` | Unknown event type |
| `INVALID_RECURRENCE` | Invalid recurrence configuration |

---

## Connection Lifecycle

### Welcome Message (automatic on connect)
```json
{
  "type": "welcome",
  "server": "KawaiiCareWear",
  "version": "2.0",
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
5. **Sync events** with `sync_events` command
6. **Send periodic pings** for connection health
7. **Handle disconnects** gracefully
8. **Listen for `event_triggered`** notifications

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
    
    def sync_events(self, events):
        """Synchronize all events with the watch."""
        return self.send_command("sync_events", events=events)
    
    def update_event(self, event_id=None, event=None, enabled=None):
        """Update a specific event."""
        kwargs = {}
        if event:
            kwargs['event'] = event
        elif event_id:
            kwargs['event_id'] = event_id
            if enabled is not None:
                kwargs['enabled'] = enabled
        return self.send_command("update_event", **kwargs)
    
    def get_events(self, event_type=None):
        """Get all events from the watch."""
        kwargs = {}
        if event_type:
            kwargs['event_type'] = event_type
        return self.send_command("get_events", **kwargs)
    
    def delete_event(self, event_id):
        """Delete an event by ID."""
        return self.send_command("delete_event", event_id=event_id)
    
    def set_animation(self, state, duration=0):
        """Trigger an animation on the watch."""
        return self.send_command("set_animation_state", state=state, duration=duration)
    
    def logout(self):
        """Logout and disconnect."""
        response = self.send_command("request_logout")
        self.token = None
        self.sock.close()
        return response


# Usage Example
client = KawaiiCareClient('192.168.1.100')
is_authenticated = client.connect()

if not is_authenticated:
    def get_code():
        return input("Enter the 6-digit code shown on watch: ")
    client.pair(get_code)
    print("Paired successfully!")

# Sync events
events = [
    {
        "id": "alarm-1",
        "event_type": "ALARM",
        "trigger_time_millis": 1712345678000,
        "label": "Wake Up",
        "recurrence": {"type": "WEEKLY", "day_of_week_mask": 62},
        "payload": {"animation": "ALARM", "vibrate": True},
        "termination_signal": "SIGNAL_SHAKE",
        "enabled": True
    },
    {
        "id": "reminder-1",
        "event_type": "REMINDER",
        "trigger_time_millis": 1712389200000,
        "label": "Take Medicine",
        "recurrence": {"type": "DAILY"},
        "payload": {"animation": "GESTURE_ACTION", "vibrate": True},
        "termination_signal": "SIGNAL_LONG_TOUCH",
        "enabled": True
    }
]
client.sync_events(events)

# Toggle an event
client.update_event(event_id="alarm-1", enabled=False)

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
