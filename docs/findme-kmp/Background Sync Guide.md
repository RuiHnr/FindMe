# FindMe Background Sync Guide

A hands-on, step-by-step guide for implementing battery-efficient background location sharing
with adaptive update frequency using silent push notifications for instant wake-up.

---

## 1. Why Background Sync?

Currently, **all** data synchronization is pull-only and manual. The client must be in the
foreground and explicitly call `syncInbox()` / `syncFriends()` to fetch new data. This means:

| Problem                                  | Impact                                                                                                           |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| **No real-time updates**                 | Bob opens the map but can't see Alice moving — he only gets her location when she also opens the app.            |
| **No background sending**               | Alice closes the app and stops sharing entirely. Her location goes stale.                                        |
| **No adaptive frequency**               | There's no mechanism to send high-frequency updates only when needed, leading to either stale data or wasted battery. |
| **Inbox messages queue up on server**    | The backend's `location_inbox` table grows unboundedly until the receiver opens the app.                         |

---

## 2. Target Architecture

### The Two Modes

Alice's device operates in one of two modes based on whether any friend is actively viewing:

| | Nobody watching (LOW mode) | Someone watching (HIGH mode) |
|---|---|---|
| **GPS** | Off. Significant-change service only. | Active. Android: 5-second interval. iOS: 5-meter distance filter (fires every ~3–5s when moving). |
| **Network** | Submit only on significant movement. | Submit on every GPS callback (batched, skip if stationary). |
| **Battery** | ~0.5%/hour | ~5–8%/hour |
| **What Bob sees** | "Last updated 7 min ago" | Real-time dot moving on map |

### How It Works (Push-on-Presence)

```
Bob opens app
  │
  ├── Starts polling GET /inbox every ~5s (receives Alice's locations)
  │
  └── Calls POST /presence  ──────────►  Server looks up Bob's friends (e.g., Alice)
                                                      │
                                                      ▼
Alice's phone receives silent Push Notification ◄─────┘
  │ (FCM / APNs)
  │
  ├── Wakes up app in background
  └── Switches LocationService to HIGH mode (frequent GPS)
          │
          └── POST /inbox (submits location)

Bob closes app
  └── Calls DELETE /presence  ─────────►  Server stops sending presence pushes
```

### Key Design Decisions

1. **Push on Presence (Wake-Up):** The major flaw with polling is that if Alice is stationary in LOW mode, she never sends a location, meaning she never gets the `active_watchers` count back to know Bob is watching! Instead, we use silent push notifications (FCM/APNs) triggered by `POST /presence`. When Bob opens the map, Alice is instantly woken up and switches to HIGH mode.

2. **No push for every location.** Push notifications are ONLY used to wake the app up when a friend starts watching. We do NOT send a push notification for every single location update. That would overwhelm the system and battery. Once Alice is in HIGH mode, she submits to `/inbox` and Bob polls `/inbox`.

3. **Batch inbox submission.** `POST /inbox` accepts an array of messages. Alice encrypts for all friends and sends one HTTP request instead of N separate ones.

4. **Displacement filter.** If Alice hasn't moved more than 5 meters since the last update, skip the submission entirely. Eliminates hundreds of redundant updates per hour when stationary.

---

## 3. Backend Changes (`findme-backend`)

### 3.1 Device Tokens Table

Add to `docs/architecture/schema.dbml`:

```dbml
Table device_tokens {
  user_id uuid [pk, ref: > users.id]
  fcm_token varchar
  apns_token varchar
}
```

### 3.2 Update Models

In `src/models/presence.rs` and `src/models/location.rs`, ensure responses don't carry watcher counts (since the client now relies on pushes):

```rust
#[derive(Debug, Serialize, Deserialize)]
pub struct SubmitLocationResponse {
    pub accepted: usize,
}
```

### 3.3 New Handler: `POST /presence` (Trigger Push)

When Bob posts presence, the server looks up his accepted friends and triggers a push to their `device_tokens`.

```rust
// pseudo-code for src/handlers/presence.rs
pub async fn heartbeat(
    State(state): State<AppState>,
    Extension(user_id): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    // 1. Mark Bob as active (upsert to active_sessions)
    db::upsert_presence(&state.db, user_id).await...
    
    // 2. Get Bob's friends (e.g. Alice)
    let friends = db::get_accepted_friends(&state.db, user_id).await...
    
    // 3. For each friend, fetch device token and send silent push
    for friend in friends {
        if let Some(token) = db::get_device_token(&state.db, friend.id).await {
            push_service::send_silent_wake_up(token).await;
        }
    }
    
    Ok(StatusCode::OK)
}
```

---

## 4. KMP Shared Layer (`commonMain`)

### 4.1 Update `LocationApi` & `LocationRepository`

No more returning watcher counts from `submitLocalLocation`. It just returns `Result<Unit>`.

```kotlin
suspend fun submitLocalLocation(lat: Double, lng: Double): Result<Unit> = runCatching {
    // 1. Encrypt for all friends
    // 2. Submit via locationApi.submitMessages(messages)
    // 3. Save to local DB
}
```

---

## 5. Android Implementation

### 5.1 Push Receiver (`FindMeMessagingService`)

Create a service extending `FirebaseMessagingService` to catch the silent push and wake the location service.

```kotlin
class FindMeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // If it's a silent wake-up push from a friend
        if (message.data["type"] == "wake_up") {
            // Instantly start the LocationSharingService in HIGH mode
            val intent = Intent(this, LocationSharingService::class.java).apply {
                putExtra("force_mode", "HIGH")
            }
            startForegroundService(intent)
        }
    }
}
```

### 5.2 Adaptive `LocationSharingService`

The service no longer changes modes based on the HTTP response of `POST /inbox`. It changes modes based on Intents received from the Push notification.

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val forcedMode = intent?.getStringExtra("force_mode")
    val mode = if (forcedMode == "HIGH") Mode.HIGH else Mode.LOW
    
    startLocationUpdates(mode)
    
    // If in HIGH mode, we need a timeout to revert to LOW if pushes stop coming
    if (mode == Mode.HIGH) scheduleLowModeFallback()
    
    return START_STICKY
}
```

---

## 6. iOS Implementation

### 6.1 Silent Push Configuration

In `Info.plist`, ensure `remote-notification` is added to `UIBackgroundModes`.

### 6.2 `AppDelegate` Push Handler

```swift
func application(
    _ application: UIApplication,
    didReceiveRemoteNotification userInfo: [AnyHashable: Any],
    fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
) {
    if userInfo["type"] as? String == "wake_up" {
        // Friend is watching. Switch to HIGH mode.
        FindMeDI.shared.locationSharingManager.forceHighMode()
        
        // Let OS know we received data
        completionHandler(.newData)
    } else {
        completionHandler(.noData)
    }
}
```

---

## 7. Foreground Polling (Receiver Side)

When Bob opens the app to watch Alice:

```kotlin
class MapViewModel(
    private val syncOrchestrator: SyncOrchestrator,
    private val presenceApi: PresenceApi,
) : ViewModel() {

    fun onMapVisible() {
        // 1. Tell server "I'm watching" -> Triggers push to Alice
        viewModelScope.launch { presenceApi.heartbeat() }

        // 2. Start polling inbox every 5 seconds
        viewModelScope.launch {
            while (isActive) {
                syncOrchestrator.syncInboxOnly()
                delay(5_000)
            }
        }
    }
}
```

---

## 8. Implementation Checklist

```
Backend:
- [x] 1. Create `active_sessions` table
- [ ] 2. Create `device_tokens` table & registration endpoint
- [ ] 3. Integrate FCM / APNs Rust SDK (e.g. `fcm` crate)
- [ ] 4. Update `POST /presence` to trigger silent pushes
- [x] 5. Ensure `POST /inbox` returns `accepted` count (no watchers)

KMP Shared:
- [x] 6. Update `LocationApi` to send batches
- [ ] 7. Add `DeviceApi` to register push tokens
- [ ] 8. Update `LocationRepository` to just return Result<Unit>

Android:
- [ ] 9. Add Firebase Cloud Messaging dependencies
- [ ] 10. Implement `FindMeMessagingService` for silent pushes
- [ ] 11. Implement `LocationSharingService` to switch mode on Push Intent
- [ ] 12. Register token on login

iOS:
- [ ] 13. Setup APNs certificates in Apple Developer Portal
- [ ] 14. Request push permissions and get device token
- [ ] 15. Handle silent push in `AppDelegate`
- [ ] 16. Implement `LocationSharingManager.forceHighMode()`
```
