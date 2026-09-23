# FindMe Background Sync Guide

A hands-on, step-by-step guide for implementing battery-efficient background location sharing
with adaptive update frequency based on watcher presence.

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

### How It Works

```text
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

1. **Push on Presence (Wake-Up):** The major flaw with polling is that if Alice is stationary in LOW mode, she never sends a location, meaning she never gets the watcher count back to know Bob is watching! Instead, we use silent push notifications (FCM/APNs) triggered by `POST /presence`. When Bob opens the map, Alice is instantly woken up and switches to HIGH mode.

2. **No push for every location.** Push notifications are ONLY used to wake the app up when a friend starts watching. We do NOT send a push notification for every single location update. That would overwhelm the system and battery. Once Alice is in HIGH mode, she submits to `/inbox` and Bob polls `/inbox`.

3. **Batch inbox submission.** `POST /inbox` accepts an array of messages. Alice encrypts for all friends and sends one HTTP request instead of N separate ones, reducing radio wake-ups.

4. **Displacement filter.** If Alice hasn't moved more than 5 meters since the last update, skip the submission entirely. Eliminates hundreds of redundant updates per hour when stationary.

---

## 3. Backend Changes (`findme-backend`)

### 3.1 New Database Table: `active_sessions`

This tracks which users have the app open and are actively viewing the map.

Add to `docs/findme-backend/schema.dbml`:

```dbml
Table active_sessions {
  user_id uuid [pk, ref: > users.id]
  last_heartbeat timestamptz [not null, default: `now()`]
}
```

Create `src/db/presence.rs`:

```rust
use sqlx::PgPool;
use uuid::Uuid;

pub async fn create_active_sessions_table(pool: &PgPool) -> Result<(), sqlx::Error> {
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS active_sessions (
            user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );"
    )
    .execute(pool)
    .await?;
    Ok(())
}

/// Upserts a heartbeat for the given user, marking them as actively watching.
pub async fn upsert_presence(pool: &PgPool, user_id: Uuid) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO active_sessions (user_id, last_heartbeat)
         VALUES ($1, NOW())
         ON CONFLICT (user_id) DO UPDATE SET last_heartbeat = NOW();"
    )
    .bind(user_id)
    .execute(pool)
    .await?;
    Ok(())
}

/// Removes a user's active session (e.g., on app background or logout).
pub async fn remove_presence(pool: &PgPool, user_id: Uuid) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM active_sessions WHERE user_id = $1;")
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}
```

### 3.2 New Database Table: `device_tokens`

Add to `docs/findme-backend/schema.dbml`:

```dbml
Table device_tokens {
  user_id uuid [pk, ref: > users.id]
  fcm_token varchar
  apns_token varchar
}
```

### 3.3 New Model: Presence

Add to `src/models/presence.rs`:

```rust
use serde::{Deserialize, Serialize};

/// Response when submitting location(s) to the inbox.
#[derive(Debug, Serialize, Deserialize)]
pub struct SubmitLocationResponse {
    pub accepted: usize,
}
```

### 3.4 New Handler: `src/handlers/presence.rs`

```rust
use crate::{AppState, db};
use axum::{Extension, extract::State, http::StatusCode};
use uuid::Uuid;

/// Marks the authenticated user as actively watching.
/// Triggers a silent push notification to all of their accepted friends.
pub(crate) async fn heartbeat(
    State(state): State<AppState>,
    Extension(user_id): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    db::upsert_presence(&state.db, user_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
        
    // Trigger wake-up pushes
    if let Ok(friends) = db::get_accepted_friends(&state.db, user_id).await {
        for friend in friends {
            if let Ok(Some(tokens)) = db::get_device_token(&state.db, friend.id).await {
                if let Some(fcm) = tokens.fcm_token {
                    crate::push::send_fcm_silent_push(&fcm).await;
                }
                if let Some(apns) = tokens.apns_token {
                    crate::push::send_apns_silent_push(&apns).await;
                }
            }
        }
    }
        
    Ok(StatusCode::OK)
}

/// Removes the authenticated user's active session.
pub(crate) async fn remove(
    State(state): State<AppState>,
    Extension(user_id): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    db::remove_presence(&state.db, user_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(StatusCode::OK)
}
```

### 3.5 Sending Silent Pushes (`src/push.rs`)

To wake up the receiver's app in the background, we must send a **silent data notification**. If you include a UI payload (like `title` or `body`), the OS displays it and defeats the purpose of a silent wake-up.

#### FCM (Android & iOS via Firebase)

Google mandates the **FCM HTTP v1 API**. A silent push is a **data-only** message.
Dependencies: `reqwest`, `yup-oauth2` (or `gcp_auth`) to get the Google Bearer token.

```rust
use reqwest::Client;
use serde_json::json;

pub async fn send_fcm_silent_push(fcm_token: &str) {
    // Note: In production, reuse the Client and cache the OAuth2 token!
    let http_client = Client::new();
    let access_token = "YOUR_GCP_OAUTH2_TOKEN";
    let project_id = "YOUR_FIREBASE_PROJECT_ID";
    let url = format!("https://fcm.googleapis.com/v1/projects/{}/messages:send", project_id);
    
    let payload = json!({
        "message": {
            "token": fcm_token,
            "data": { "type": "wake_up" },
            "android": { "priority": "high" }, // Wakes Doze mode
            "apns": {
                "headers": { "apns-push-type": "background", "apns-priority": "5" },
                "payload": { "aps": { "content-available": 1 } }
            }
        }
    });

    let _ = http_client.post(&url)
        .bearer_auth(access_token)
        .json(&payload)
        .send()
        .await;
}
```

#### APNs (iOS natively without Firebase)

If you send directly to Apple, you must strictly follow their background push rules:
1. Header `apns-push-type: background`
2. Header `apns-priority: 5` (Priority 10 for background pushes will get you throttled).
3. Payload `content-available: 1`.

Dependency: `a2 = "0.10"`

```rust
use a2::{Client, Endpoint, request::notification::SilentNotificationBuilder};

pub async fn send_apns_silent_push(apns_token: &str) {
    // Note: In production, instantiate this Client once at startup and share it.
    let client = Client::token(
        std::fs::File::open("AuthKey_XXXXXX.p8").unwrap(),
        "YOUR_TEAM_ID",
        "YOUR_KEY_ID",
        Endpoint::Production,
    ).expect("Failed to create APNs client");

    let mut builder = SilentNotificationBuilder::new();
    // content-available: 1 is set automatically by the builder
    builder.add_custom_data("type", &"wake_up").unwrap();

    let payload = builder.build(apns_token, Default::default());
    
    if let Err(e) = client.send(payload).await {
        eprintln!("Failed to send APNs: {:?}", e);
    }
}
```

### 3.6 Modify `POST /inbox` — Accept Array

Update `src/handlers/location.rs`:

```rust
use crate::models::location::SubmitLocationRequest;
use crate::models::presence::SubmitLocationResponse;
use crate::{AppState, db};
use axum::{Extension, Json, extract::State, http::StatusCode, response::IntoResponse};
use uuid::Uuid;

/// Receives one or more encrypted location payloads and queues them in receivers' inboxes.
/// Returns the number of accepted messages.
pub(crate) async fn receive_location(
    State(state): State<AppState>,
    Extension(sender_id): Extension<Uuid>,
    Json(payloads): Json<Vec<SubmitLocationRequest>>,
) -> Result<impl IntoResponse, StatusCode> {
    let mut accepted = 0;

    for payload in &payloads {
        // Only insert if sender and receiver are confirmed friends
        let are_friends = db::are_friends(&state.db, sender_id, payload.receiver_id)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        if are_friends {
            db::insert_location(
                &state.db,
                &sender_id,
                &payload.receiver_id,
                &payload.encrypted_blob,
            )
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            accepted += 1;
        }
    }

    Ok(Json(SubmitLocationResponse { accepted }))
}
```

### 3.7 Register the New Routes

In `src/lib.rs`, add within the authenticated router:

```rust
.route("/presence", post(handlers::presence::heartbeat))
.route("/presence", delete(handlers::presence::remove))
```

---

## 4. KMP Shared Layer (`commonMain`)

### 4.1 New: `SyncOrchestrator`

The single entry point for all sync logic, called from both foreground UI and background services.

Create `findme-kmp/src/commonMain/kotlin/com/ruirui/findme/sync/SyncOrchestrator.kt`:

```kotlin
package com.ruirui.findme.sync

import com.ruirui.findme.repository.FriendRepository
import com.ruirui.findme.repository.LocationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates all data synchronization in a single, mutex-protected entry point.
 * Called from:
 *   - Background location service (after submitting own location)
 *   - Foreground polling timer
 *   - Periodic background tasks (WorkManager / BGTaskScheduler)
 *   - Manual pull-to-refresh
 *
 * Uses a [Mutex] to prevent concurrent sync runs from racing on the
 * Double Ratchet state machine, which is NOT safe for parallel decryption.
 */
class SyncOrchestrator(
    private val locationRepository: LocationRepository,
    private val friendRepository: FriendRepository,
) {
    private val syncMutex = Mutex()

    /**
     * Runs a full sync cycle: friends list + inbox decryption.
     * Returns [Result.success] if both succeed, or the first failure.
     *
     * Thread-safe: concurrent calls queue behind the mutex rather than
     * corrupting ratchet state.
     */
    suspend fun syncAll(): Result<Unit> = syncMutex.withLock {
        // Sync friends first — inbox decryption may need the friend list
        friendRepository.syncFriends()
            .onFailure { return Result.failure(it) }

        locationRepository.syncInbox()
    }

    /**
     * Lightweight sync that only fetches inbox messages.
     * Use when the friends list is known to be fresh (e.g., app just opened).
     */
    suspend fun syncInboxOnly(): Result<Unit> = syncMutex.withLock {
        locationRepository.syncInbox()
    }
}
```

> [!WARNING]
> The `Mutex` is critical. `DoubleRatchetSession.decrypt()` advances the ratchet's internal chain
> key. If two `syncInbox()` calls run concurrently, they could read the same ratchet state, decrypt
> different messages, and both save — one overwriting the other's advancement. The mutex serializes
> all sync operations.

### 4.2 New: `PresenceManager`

Handles heartbeat and presence lifecycle.

Create `findme-kmp/src/commonMain/kotlin/com/ruirui/findme/sync/PresenceManager.kt`:

```kotlin
package com.ruirui.findme.sync

import com.ruirui.findme.network.api.PresenceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the user's "actively watching" presence with the backend.
 *
 * When started, sends a heartbeat to `POST /presence` every 30 seconds
 * so the server knows this user has the app open. The backend triggers
 * silent pushes to friends to wake up their GPS.
 *
 * Call [start] when the map screen becomes visible.
 * Call [stop] when the app backgrounds or navigates away from the map.
 */
class PresenceManager(
    private val presenceApi: PresenceApi,
    private val scope: CoroutineScope,
) {
    private var heartbeatJob: Job? = null

    fun start() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch {
            while (isActive) {
                presenceApi.heartbeat()
                delay(30_000) // 30-second heartbeat interval
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null

        scope.launch {
            presenceApi.remove()
        }
    }
}
```

### 4.3 New: `PresenceApi`

Create `findme-kmp/src/commonMain/kotlin/com/ruirui/findme/network/api/PresenceApi.kt`:

```kotlin
package com.ruirui.findme.network.api

import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post

/**
 * Handles watcher presence heartbeat and removal.
 */
class PresenceApi(private val client: HttpClient) {

    /** Sends a heartbeat marking this user as actively watching. */
    suspend fun heartbeat(): Result<Unit> = safeApiCall {
        client.post("/presence")
    }

    /** Removes this user's active session. */
    suspend fun remove(): Result<Unit> = safeApiCall {
        client.delete("/presence")
    }
}
```

### 4.4 Update: `LocationApi` — Batch Submit

Update `LocationApi.kt`:

```kotlin
package com.ruirui.findme.network.api

import com.ruirui.findme.models.InboxResponse
import com.ruirui.findme.models.InboxMessage
import com.ruirui.findme.models.SubmitLocationResponse
import com.ruirui.findme.models.SubmitMessageRequest
import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess

class LocationApi(private val client: HttpClient) {

    suspend fun getInbox(): Result<InboxResponse> {
        // ... (existing getInbox code) ...
    }

    /**
     * Submits encrypted location payloads to multiple friends' inboxes in a single request.
     * Returns the number of accepted messages.
     */
    suspend fun submitMessages(requests: List<SubmitMessageRequest>): Result<SubmitLocationResponse> {
        return safeApiCall {
            client.post("/inbox") {
                setBody(requests)
            }
        }
    }
}
```

### 4.5 New Model: `SubmitLocationResponse`

Add to `LocationModels.kt`:

```kotlin
@Serializable
data class SubmitLocationResponse(
    val accepted: Int
)
```

### 4.6 Update: `LocationRepository` — Batch Submission

Collect all encrypted envelopes into a list and submit them in one batch call:

```kotlin
override suspend fun submitLocalLocation(lat: Double, lng: Double): Result<Unit> = runCatching {
    val timestamp = getTimeMillis()
    val jsonPayload = Json.encodeToString(LocationPayload(lat, lng, timestamp))

    val friends = db.friendQueries.getAllFriends().executeAsList()
        .map { FriendDto(it.user_id, it.username) }

    // Build all messages (encrypting concurrently)
    val messages: List<SubmitMessageRequest> = coroutineScope {
        friends.map { friend ->
            async {
                val envelope = encryptForFriend(friend.userId, jsonPayload)
                SubmitMessageRequest(
                    receiverId = friend.userId,
                    encryptedBlob = Json.encodeToString(envelope)
                )
            }
        }.awaitAll()
    }

    // Single batched HTTP request instead of N separate ones
    locationApi.submitMessages(messages).getOrThrow()

    db.locationQueries.insertOwnLocation(lat, lng, timestamp)
}
```

---

## 5. Android Implementation

### 5.1 Dependencies

Add to `gradle/libs.versions.toml`:

```toml
[versions]
# ... existing ...
work-runtime = "2.11.0"
play-services-location = "21.4.0"
firebase-messaging = "24.0.0"

[libraries]
# ... existing ...
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work-runtime" }
firebase-messaging = { module = "com.google.firebase:firebase-messaging", version.ref = "firebase-messaging" }
```

### 5.2 Push Receiver (`FindMeMessagingService`)

Create a service extending `FirebaseMessagingService` to catch the silent push and wake the location service.

```kotlin
package com.ruirui.findme.location

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FindMeMessagingService : FirebaseMessagingService() {
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO: Send token to backend via DeviceApi
    }
    
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

### 5.3 `LocationSharingService` (Foreground Service)

Create in the Android app module: `app/src/main/java/com/ruirui/findme/location/LocationSharingService.kt`

```kotlin
package com.ruirui.findme.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ruirui.findme.sync.SyncOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android Foreground Service that continuously shares the user's location with friends.
 *
 * Operates in two modes, switched dynamically via Intents from the Push Receiver:
 *
 * - **HIGH mode**: GPS every 5 seconds via FusedLocationProviderClient time-based interval.
 * - **LOW mode**: 5-minute interval with balanced accuracy (cell/WiFi, no GPS hardware).
 *
 * Note: Android's FusedLocationProviderClient supports time-based intervals natively.
 * iOS uses distance-based triggers instead — see LocationSharingManager.swift.
 */
class LocationSharingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentMode: Mode = Mode.LOW
    private var lastSentLocation: Location? = null

    private enum class Mode { HIGH, LOW }

    companion object {
        private const val CHANNEL_ID = "findme_location_channel"
        private const val NOTIFICATION_ID = 1
        private const val HIGH_INTERVAL_MS = 5_000L
        private const val LOW_INTERVAL_MS = 300_000L  // 5 minutes
        private const val DISPLACEMENT_THRESHOLD_METERS = 5f
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forcedMode = intent?.getStringExtra("force_mode")
        val targetMode = if (forcedMode == "HIGH") Mode.HIGH else Mode.LOW
        
        if (targetMode != currentMode || locationCallback == null) {
            startLocationUpdates(targetMode)
        }
        
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(CHANNEL_ID, "FindMe Location Sharing", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindMe is sharing your location")
            .setContentText("Your friends can see where you are")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates(mode: Mode) {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }

        val (priority, interval) = when (mode) {
            Mode.HIGH -> Priority.PRIORITY_HIGH_ACCURACY to HIGH_INTERVAL_MS
            Mode.LOW -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOW_INTERVAL_MS
        }

        val request = LocationRequest.Builder(priority, interval).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationCallback = callback
            currentMode = mode
        } catch (e: SecurityException) {
            Log.e("FindMe", "Lost location permissions", e)
        }
    }

    private fun handleNewLocation(location: Location) {
        val last = lastSentLocation
        if (last != null && last.distanceTo(location) < DISPLACEMENT_THRESHOLD_METERS) {
            return
        }
        lastSentLocation = location

        val repo = FindMeApp.instance.locationRepository
        val orchestrator = FindMeApp.instance.syncOrchestrator

        serviceScope.launch {
            repo.submitLocalLocation(location.latitude, location.longitude)
            orchestrator.syncInboxOnly()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

### 5.4 `InboxSyncWorker` (WorkManager Periodic Fallback)

Create `app/src/main/java/com/ruirui/findme/location/InboxSyncWorker.kt`:

```kotlin
package com.ruirui.findme.location

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ruirui.findme.sync.SyncOrchestrator
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync as a fallback. Runs every 15 minutes.
 */
class InboxSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val orchestrator: SyncOrchestrator = FindMeApp.instance.syncOrchestrator

        return orchestrator.syncAll().fold(
            onSuccess = {
                Log.d("FindMe", "Periodic sync succeeded")
                Result.success()
            },
            onFailure = { error ->
                Log.e("FindMe", "Periodic sync failed", error)
                Result.retry()
            }
        )
    }

    companion object {
        private const val WORK_NAME = "findme_inbox_sync"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboxSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
```

### 5.5 AndroidManifest.xml Additions

```xml
<!-- Permissions -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Inside <application> -->
<service
    android:name=".location.LocationSharingService"
    android:exported="false"
    android:foregroundServiceType="location" />

<service
    android:name=".location.FindMeMessagingService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

---

## 6. iOS Implementation

### 6.1 Background Location Setup

In your iOS app's `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
    <string>location</string>           <!-- Continuous background location -->
    <string>fetch</string>              <!-- Background fetch (BGTaskScheduler) -->
    <string>remote-notification</string> <!-- Silent Push wake ups -->
</array>

<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>FindMe shares your location with friends you've added.</string>

<key>NSLocationWhenInUseUsageDescription</key>
<string>FindMe shows your location on the map.</string>

<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.ruirui.findme.inbox-sync</string>
</array>
```

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

### 6.3 `LocationSharingManager` (Swift)

```swift
import CoreLocation
import FindMeCore  // KMP framework

/**
 * Manages continuous background location sharing on iOS.
 *
 * IMPORTANT: iOS does NOT support time-based location intervals like Android's
 * FusedLocationProviderClient. Instead, CLLocationManager uses a DISTANCE-BASED model:
 *
 * - `desiredAccuracy`: Controls which hardware is used (GPS vs cell/WiFi).
 * - `distanceFilter`: Minimum meters the user must move before the next callback fires.
 *
 * With `kCLLocationAccuracyBest` + `distanceFilter = 5`:
 *   - Walking (~1.5 m/s)  → callback every ~3-4 seconds
 *   - Driving (~15 m/s)   → callback every ~0.3 seconds (more frequent than needed)
 *   - Stationary           → no callbacks at all (perfect — saves battery)
 */
class LocationSharingManager: NSObject, CLLocationManagerDelegate {

    private let locationManager = CLLocationManager()
    private let locationRepository: LocationRepository
    private let syncOrchestrator: SyncOrchestrator

    private var currentMode: Mode = .low
    private var lastSentLocation: CLLocation?

    private enum Mode {
        case high  // kCLLocationAccuracyBest + distanceFilter 5m (continuous GPS)
        case low   // Significant-change monitoring only (cell tower transitions, no GPS)
    }

    init(locationRepository: LocationRepository, syncOrchestrator: SyncOrchestrator) {
        self.locationRepository = locationRepository
        self.syncOrchestrator = syncOrchestrator
        super.init()

        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.showsBackgroundLocationIndicator = true  // Blue bar in status bar
        locationManager.pausesLocationUpdatesAutomatically = false
    }

    func startSharing() {
        applyMode(.low)  // Start in LOW mode
    }

    func stopSharing() {
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
    }
    
    // Called by AppDelegate when silent push arrives
    func forceHighMode() {
        applyMode(.high)
    }

    private func applyMode(_ mode: Mode) {
        // Stop both modes first to avoid overlap
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()

        switch mode {
        case .high:
            locationManager.desiredAccuracy = kCLLocationAccuracyBest
            locationManager.distanceFilter = 5  // Fires after 5 meters of movement
            locationManager.startUpdatingLocation()
        case .low:
            locationManager.startMonitoringSignificantLocationChanges()
        }
        currentMode = mode
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }

        // Displacement filter
        if let last = lastSentLocation, last.distance(from: location) < 5 { return }
        lastSentLocation = location

        Task {
            _ = try? await locationRepository.submitLocalLocation(
                lat: location.coordinate.latitude,
                lng: location.coordinate.longitude
            )
            // Piggyback inbox sync
            _ = try? await syncOrchestrator.syncInboxOnly()
        }
    }
}
```

### 6.4 BGTaskScheduler Fallback

```swift
// In AppDelegate or SceneDelegate

func registerBackgroundSync() {
    BGTaskScheduler.shared.register(
        forTaskWithIdentifier: "com.ruirui.findme.inbox-sync",
        using: nil
    ) { task in
        self.handleBackgroundSync(task: task as! BGAppRefreshTask)
    }
}

func scheduleBackgroundSync() {
    let request = BGAppRefreshTaskRequest(identifier: "com.ruirui.findme.inbox-sync")
    request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
    try? BGTaskScheduler.shared.submit(request)
}

private func handleBackgroundSync(task: BGAppRefreshTask) {
    scheduleBackgroundSync()  // Schedule next occurrence

    let syncTask = Task {
        _ = try? await FindMeDI.shared.syncOrchestrator.syncAll()
    }

    task.expirationHandler = { syncTask.cancel() }

    Task {
        await syncTask.value
        task.setTaskCompleted(success: true)
    }
}
```

---

## 7. Foreground Polling (Receiver Side)

When Bob opens the app to watch Alice, he needs to poll for incoming messages:

```kotlin
// In the map screen's ViewModel or Composable lifecycle

class MapViewModel(
    private val syncOrchestrator: SyncOrchestrator,
    private val presenceManager: PresenceManager,
) : ViewModel() {

    fun onMapVisible() {
        // 1. Start presence heartbeat (tells server "I'm watching", triggering push to Alice)
        presenceManager.start()

        // 2. Start polling inbox every 5 seconds
        viewModelScope.launch {
            while (isActive) {
                syncOrchestrator.syncInboxOnly()
                delay(5_000)
            }
        }
    }

    fun onMapHidden() {
        presenceManager.stop()
        // Polling loop is cancelled when viewModelScope is cancelled
    }
}
```

---

## 8. Auth Lifecycle Integration

### After Login / Registration

```kotlin
suspend fun onLoginSuccess(context: Context) {
    // 1. Initial sync
    syncOrchestrator.syncAll()

    // 2. Start periodic fallback sync
    InboxSyncWorker.enqueue(context)

    // 3. Register push token with backend (FCM/APNs)
    // deviceApi.registerDevice(...)
}
```

### On Logout

```kotlin
suspend fun onLogout(context: Context) {
    // 1. Stop presence heartbeat
    presenceManager.stop()

    // 2. Cancel periodic sync
    InboxSyncWorker.cancel(context)

    // 3. Stop location service
    context.stopService(Intent(context, LocationSharingService::class.java))

    // 4. Clear auth token
    secureStorage.remove(SecureStorageKeys.AUTH_TOKEN)
}
```

---

## 9. Implementation Checklist

Follow this order to avoid breaking the build at any step:

```
Backend:
- [x] 1. Create `active_sessions` table in `src/db/presence.rs`
- [x] 2. Create `device_tokens` table & registration endpoint
- [x] 3. Integrate FCM / APNs Rust SDK (e.g. `fcm` crate)
- [ ] 4. Update `POST /presence` to trigger silent pushes
- [x] 5. Ensure `POST /inbox` returns `accepted` count (no watchers)

KMP Shared (findme-kmp):
- [x] 6. Update `LocationApi.submitMessages()` to accept List
- [ ] 7. Add `DeviceApi` to register push tokens
- [ ] 8. Create `SyncOrchestrator` in `commonMain/sync/`
- [ ] 9. Create `PresenceManager` in `commonMain/sync/`
- [ ] 10. Add unit tests: SyncOrchestratorTest, PresenceManagerTest
- [ ] 11. Update FakeBackend to handle batch /inbox endpoints

Android:
- [ ] 12. Add WorkManager & Firebase Cloud Messaging dependencies
- [ ] 13. Implement `FindMeMessagingService` for silent pushes
- [ ] 14. Create `LocationSharingService` with adaptive HIGH/LOW modes
- [ ] 15. Create `InboxSyncWorker` (periodic fallback)
- [ ] 16. Register service + permissions in AndroidManifest.xml
- [ ] 17. Add foreground polling in map ViewModel
- [ ] 18. Hook into auth lifecycle (start on login, stop on logout)

iOS:
- [ ] 19. Setup APNs certificates in Apple Developer Portal
- [ ] 20. Add background location + fetch + remote-notification to Info.plist
- [ ] 21. Create `LocationSharingManager` with adaptive HIGH/LOW modes
- [ ] 22. Register BGTaskScheduler fallback
- [ ] 23. Handle silent push in `AppDelegate`
- [ ] 24. Wire PresenceManager into SwiftUI lifecycle
- [ ] 25. Add foreground polling in map view
```

---

## 10. Testing Strategy

### Unit Tests (KMP `commonTest`)

#### `SyncOrchestratorTest.kt`

```kotlin
class SyncOrchestratorTest {
    @Test
    fun syncAllCallsFriendsThenInbox() = runTest {
        val callOrder = mutableListOf<String>()
        // ... stub repos that record call order ...
        val orchestrator = SyncOrchestrator(locationRepo, friendRepo)
        orchestrator.syncAll().getOrThrow()
        assertEquals(listOf("friends", "inbox"), callOrder)
    }

    @Test
    fun concurrentSyncsAreSerializedByMutex() = runTest {
        val maxConcurrent = AtomicInteger(0)
        // ... stub repo with delay that tracks concurrency ...
        // Launch 5 concurrent syncs, verify max concurrent == 1
    }
}
```

#### `LocationRepositoryTest.kt` Updates

- Test that `submitLocalLocation()` sends a single batched HTTP request.

### Manual Verification

| Step | What to verify                                                                           |
|------|------------------------------------------------------------------------------------------|
| 1    | Alice starts sharing → foreground service runs in LOW mode (infrequent updates)          |
| 2    | Bob opens the map → presence heartbeat starts → backend fires push to Alice              |
| 3    | Alice's phone receives push → switches to HIGH mode within a few seconds                 |
| 4    | Bob sees Alice's location updating in near-real-time on the map (every few seconds while moving) |
| 5    | Bob closes the app → presence expires → Alice switches back to LOW mode                  |
| 6    | Alice is stationary → displacement filter skips submissions → minimal battery drain      |
| 7    | Both apps killed → WorkManager/BGTaskScheduler syncs inbox every ~15 min                 |

---

## 11. File Summary

| Action       | File Path                                                                            |
|--------------|--------------------------------------------------------------------------------------|
| **[NEW]**    | `findme-backend/src/db/presence.rs` — Active sessions CRUD                           |
| **[NEW]**    | `findme-backend/src/db/device_tokens.rs` — Device token CRUD (FCM/APNs)              |
| **[NEW]**    | `findme-backend/src/handlers/presence.rs` — Heartbeat (triggers push)              |
| **[MODIFY]** | `findme-backend/src/handlers/location.rs` — Accept array, return `SubmitLocationResponse` |
| **[NEW]**    | `findme-kmp/.../sync/SyncOrchestrator.kt` — Mutex-protected sync coordinator         |
| **[NEW]**    | `findme-kmp/.../sync/PresenceManager.kt` — Heartbeat lifecycle manager               |
| **[NEW]**    | `findme-kmp/.../network/api/PresenceApi.kt` — `/presence` HTTP client                |
| **[MODIFY]** | `findme-kmp/.../network/api/LocationApi.kt` — `submitMessages(List)`               |
| **[NEW]**    | Android `FindMeMessagingService.kt` — Silent push receiver                           |
| **[NEW]**    | Android `LocationSharingService.kt` — Adaptive foreground service                    |
| **[NEW]**    | Android `InboxSyncWorker.kt` — WorkManager periodic fallback                         |
| **[MODIFY]** | Android `AndroidManifest.xml` — Register services + permissions                      |
| **[NEW]**    | iOS `LocationSharingManager.swift` — Adaptive CLLocationManager                      |
| **[MODIFY]** | iOS `AppDelegate.swift` — Handle silent pushes                                       |
| **[MODIFY]** | iOS `Info.plist` — Background location + fetch + push modes                          |

---

## 12. Gotchas & Tips

1. **Presence auto-expiry is critical.** If Bob's app crashes without calling `DELETE /presence`, Alice would stay in HIGH mode forever. The backend must expire Bob's presence automatically if he hasn't heartbeated in 60s.

2. **Don't block `submitLocalLocation` on `syncInboxOnly`.** The piggyback sync is a best-effort optimization. If it fails (e.g., network error), the location submission should still succeed.

3. **Android `PRIORITY_HIGH_ACCURACY` vs `PRIORITY_BALANCED_POWER_ACCURACY`.** The former activates the GPS hardware. The latter uses cell towers and WiFi — no GPS radio, dramatically less battery. LOW mode should use balanced accuracy; HIGH mode should use high accuracy.

4. **iOS significant-change service survives app kill.** Unlike standard location updates, `startMonitoringSignificantLocationChanges()` will **relaunch** your app from a killed state when a significant location change occurs.

5. **Displacement filter threshold.** 5 meters is a good starting point. For driving, you might want to increase it to 10–20m to avoid noisy GPS jitter at traffic lights.

6. **Batch `POST /inbox` is a breaking change.** The handler expects `Vec<SubmitLocationRequest>`. Update the `FakeBackend` in `commonTest` accordingly.

7. **WorkManager 15-minute minimum.** Android enforces a minimum periodic interval of 15 minutes for `PeriodicWorkRequest`. You cannot make it shorter.

8. **iOS background fetch budget.** iOS gives each app a limited number of background fetch opportunities per day, prioritized by how often the user opens the app.

9. **The Mutex protects ratchet state, not GPS state.** The `SyncOrchestrator.syncMutex` prevents concurrent decryption runs.

10. **Don't heartbeat when the foreground service isn't running.** Only start `PresenceManager` when the map screen is actually visible and the user expects to see friends' locations.

11. **Android OEM battery management can kill foreground services.** Samsung, Xiaomi, Huawei, and OnePlus devices have custom battery optimization that can kill foreground services. Consider showing a one-time prompt linking to [dontkillmyapp.com](https://dontkillmyapp.com/).

12. **iOS App Store review requires justification for background location.** Your App Store description must clearly state that FindMe is a location-sharing app and uses location in the background.

13. **iOS uses distance-based triggers, not time-based intervals.** Unlike Android's `FusedLocationProviderClient`, iOS's `CLLocationManager` fires based on `distanceFilter`. With `distanceFilter = 5`, updates fire every ~3–5s while walking, faster while driving, and not at all while stationary.

14. **Push notifications are not guaranteed.** FCM and APNs may throttle silent background pushes if the user rarely opens your app. This is why the `15-minute` fallback worker in Android/iOS is critical!
