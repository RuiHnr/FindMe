# FindMe Local DB Migration Guide (SQLDelight)

A hands-on, step-by-step guide for migrating from `SecureStorage`-only persistence to a proper local
database using **SQLDelight**.

---

## 1. Why This Migration?

Currently, **all** persistence lives in `SecureStorage` (Android Keystore-encrypted DataStore / iOS
Keychain). This has three critical problems:

| Problem                             | Impact                                                                                                                                                                        |
|-------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Friend list is ephemeral**        | `FriendRepository` stores `_friends` in a `MutableStateFlow`. On app restart, the friend list is empty until `syncFriends()` completes. No offline support.                   |
| **Location history is ephemeral**   | `LocationRepository._friendLocations` is a `MutableStateFlow<Map>`. All location data disappears on restart.                                                                  |
| **SecureStorage is not a database** | It's a flat key-value store. You can't query, index, or paginate. Storing 100+ OTPK private keys as individual `onetime_prekey_private_{id}` keys is fragile and uncleanable. |
| **No TOFU identity pinning**        | There's no persistent record of a friend's Identity Key. A server-side MITM could silently swap keys.                                                                         |

### What stays in SecureStorage (cryptographic secrets)

These are truly sensitive and **must** remain encrypted at rest:

| Key                          | Why it stays                                                      |
|------------------------------|-------------------------------------------------------------------|
| `auth_token`                 | JWT bearer token                                                  |
| `user_id`                    | User's own UUID                                                   |
| `identity_private_key_dh`    | Long-term X25519 private key                                      |
| `identity_public_key_dh`     | Long-term X25519 public key                                       |
| `identity_private_key_sign`  | Long-term Ed25519 signing private key                             |
| `identity_public_key_sign`   | Long-term Ed25519 signing public key                              |
| `signed_prekey_private_{id}` | Signed PreKey private keys (kept across rotations)                |
| `signed_prekey_public_{id}`  | Signed PreKey public keys                                         |
| `current_signed_prekey_id`   | Current SPK ID tracker                                            |
| `ratchet_state_{friendId}`   | Serialized `DoubleRatchetState` JSON (contains active chain keys) |

### What moves to SQLDelight

| Data                            | Current Location                                | Why it moves                         |
|---------------------------------|-------------------------------------------------|--------------------------------------|
| Friend list                     | `StateFlow` (volatile)                          | Offline-first, queryable             |
| Friend requests                 | `StateFlow` (volatile)                          | Offline-first                        |
| Location history                | `StateFlow` (volatile)                          | Timestamped queries, history display |
| OTPK private keys               | `SecureStorage` (`onetime_prekey_private_{id}`) | Bulk cleanup, indexed lookup         |
| Friend Identity Key pins (TOFU) | **Doesn't exist yet**                           | MITM detection                       |

---

## 2. Setup: Add SQLDelight to the Project

### 2.1 Version Catalog

Add to [
`gradle/libs.versions.toml`](file:///c:/Users/lauri/Documents/OwnProjects/FindMe/gradle/libs.versions.toml):

```toml
[versions]
# ... existing versions ...
sqldelight = "2.0.2"

[libraries]
# ... existing libraries ...
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native-driver = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }

[plugins]
# ... existing plugins ...
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

### 2.2 Root `build.gradle.kts`

Register the plugin (don't apply):

```kotlin
plugins {
    // ... existing ...
    alias(libs.plugins.sqldelight) apply false
}
```

### 2.3 Module `findme-kmp/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.sqldelight)  // ADD
}

// ADD: SQLDelight database configuration
sqldelight {
    databases {
        create("FindMeDatabase") {
            packageName.set("com.ruirui.findme.db")
        }
    }
}

kotlin {
    // ... existing target config ...

    sourceSets {
        commonMain.dependencies {
            // ... existing ...
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            // ... existing ...
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        // commonTest stays the same — SQLDelight generates an in-memory driver for testing
    }
}
```

> [!IMPORTANT]
> After adding the plugin, run `./gradlew :findme-kmp:generateCommonMainFindMeDatabaseInterface` to
> verify the plugin activates correctly before writing any `.sq` files.

---

## 3. Schema Design (`.sq` files)

Create the directory `findme-kmp/src/commonMain/sqldelight/com/ruirui/findme/db/` and add these
files:

### 3.1 `Friend.sq`

```sql
-- Friend.sq
CREATE TABLE friend (
    user_id TEXT NOT NULL PRIMARY KEY,
    username TEXT NOT NULL,
    added_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE friend_request (
    target_username TEXT NOT NULL PRIMARY KEY,
    direction TEXT NOT NULL DEFAULT 'inbound',
    created_at INTEGER NOT NULL DEFAULT 0
);

-- Queries

getAllFriends:
SELECT * FROM friend ORDER BY username ASC;

insertFriend:
INSERT OR REPLACE INTO friend (user_id, username, added_at)
VALUES (?, ?, ?);

deleteFriend:
DELETE FROM friend WHERE user_id = ?;

deleteAllFriends:
DELETE FROM friend;

getAllFriendRequests:
SELECT * FROM friend_request WHERE direction = 'inbound' ORDER BY created_at DESC;

insertFriendRequest:
INSERT OR REPLACE INTO friend_request (target_username, direction, created_at)
VALUES (?, ?, ?);

deleteFriendRequest:
DELETE FROM friend_request WHERE target_username = ?;

deleteAllFriendRequests:
DELETE FROM friend_request;
```

### 3.2 `Location.sq`

```sql
-- Location.sq
CREATE TABLE location_history (
    friend_user_id TEXT NOT NULL,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    timestamp INTEGER NOT NULL,
    received_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (friend_user_id, timestamp)
);

-- Queries

getLatestLocationPerFriend:
SELECT * FROM location_history lh
WHERE timestamp = (SELECT MAX(timestamp) FROM location_history WHERE friend_user_id = lh.friend_user_id)
ORDER BY friend_user_id;

getLocationHistoryForFriend:
SELECT * FROM location_history
WHERE friend_user_id = ?
ORDER BY timestamp DESC
LIMIT ?;

insertLocation:
INSERT OR REPLACE INTO location_history (friend_user_id, latitude, longitude, timestamp, received_at)
VALUES (?, ?, ?, ?, ?);

deleteLocationsByFriend:
DELETE FROM location_history WHERE friend_user_id = ?;

deleteOldLocations:
DELETE FROM location_history WHERE received_at < ?;
```

### 3.3 `IdentityPin.sq` (TOFU)

```sql
-- IdentityPin.sq
CREATE TABLE identity_pin (
    user_id TEXT NOT NULL PRIMARY KEY,
    identity_key_dh TEXT NOT NULL,
    first_seen_at INTEGER NOT NULL,
    last_verified_at INTEGER NOT NULL
);

-- Queries

getPin:
SELECT * FROM identity_pin WHERE user_id = ?;

upsertPin:
INSERT OR REPLACE INTO identity_pin (user_id, identity_key_dh, first_seen_at, last_verified_at)
VALUES (?, ?, ?, ?);

deletePin:
DELETE FROM identity_pin WHERE user_id = ?;
```

### 3.4 `OneTimePreKey.sq`

```sql
-- OneTimePreKey.sq
-- Replaces the scattered SecureStorage keys `onetime_prekey_private_{id}`
CREATE TABLE one_time_prekey (
    key_id INTEGER NOT NULL PRIMARY KEY,
    private_key_base64 TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT 0
);

-- Queries

getPrivateKey:
SELECT private_key_base64 FROM one_time_prekey WHERE key_id = ?;

insertKey:
INSERT OR IGNORE INTO one_time_prekey (key_id, private_key_base64, created_at)
VALUES (?, ?, ?);

deleteKey:
DELETE FROM one_time_prekey WHERE key_id = ?;

countKeys:
SELECT COUNT(*) FROM one_time_prekey;

deleteAll:
DELETE FROM one_time_prekey;
```

After creating these files, run `./gradlew :findme-kmp:generateCommonMainFindMeDatabaseInterface` to
generate the type-safe Kotlin code.

---

## 4. Platform Drivers (Expect/Actual)

### 4.1 Common Interface

Create `findme-kmp/src/commonMain/kotlin/com/ruirui/findme/db/DriverFactory.kt`:

```kotlin
package com.ruirui.findme.db

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}
```

### 4.2 Android Implementation

Create `findme-kmp/src/androidMain/kotlin/com/ruirui/findme/db/DriverFactory.android.kt`:

```kotlin
package com.ruirui.findme.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(FindMeDatabase.Schema, context, "findme.db")
    }
}
```

### 4.3 iOS Implementation

Create `findme-kmp/src/iosMain/kotlin/com/ruirui/findme/db/DriverFactory.ios.kt`:

```kotlin
package com.ruirui.findme.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(FindMeDatabase.Schema, "findme.db")
    }
}
```

### 4.4 Test Driver

For `commonTest`, you do **not** need an expect/actual. Just create an in-memory driver directly in
your test setup:

```kotlin
// In test files
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
FindMeDatabase.Schema.create(driver)
val db = FindMeDatabase(driver)
```

> [!WARNING]
> For the test in-memory driver you need to add the JVM SQLite JDBC driver as a test dependency:
> ```kotlin
> commonTest.dependencies {
>     // ... existing ...
>     implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
> }
> ```

---

## 5. Repository Refactoring

### 5.1 `FriendRepository` — The Biggest Win

**Current state:** Friends and requests are fetched from the API, dumped into `MutableStateFlow`,
and lost on restart.

**Target state:** API responses are written to SQLDelight first, then the `StateFlow` observes the
database as the source of truth.

```kotlin
// BEFORE (current)
class FriendRepositoryImpl(
    private val friendsApi: FriendsApi,
) : FriendRepository {
    private val _friends = MutableStateFlow<List<FriendDto>>(emptyList())

    override suspend fun syncFriends(): Result<Unit> = runCatching {
        val friendsList = friendsApi.getFriends().getOrThrow()
        _friends.value = friendsList  // Gone on restart!
    }
}

// AFTER (with SQLDelight)
class FriendRepositoryImpl(
    private val friendsApi: FriendsApi,
    private val db: FindMeDatabase,
) : FriendRepository {
    // Observe the DB as the source of truth using SQLDelight's Flow extension
    override val friends: StateFlow<List<FriendDto>> =
        db.friendQueries.getAllFriends()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { FriendDto(it.user_id) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun syncFriends(): Result<Unit> = runCatching {
        val friendsList = friendsApi.getFriends().getOrThrow()
        val requestsList = friendsApi.getFriendRequests().getOrThrow()

        // Write to DB (this triggers the Flow above automatically)
        db.transaction {
            db.friendQueries.deleteAllFriends()
            friendsList.forEach { friend ->
                db.friendQueries.insertFriend(
                    user_id = friend.userId,
                    username = friend.userId, // TODO: add username to FriendDto
                    added_at = currentTimeMillis()
                )
            }
            db.friendQueries.deleteAllFriendRequests()
            requestsList.forEach { request ->
                db.friendQueries.insertFriendRequest(
                    target_username = request.targetUsername,
                    direction = "inbound",
                    created_at = currentTimeMillis()
                )
            }
        }
    }
}
```

### 5.2 `LocationRepository` — Add History

**Current:** Only the latest location per friend is kept in a `MutableStateFlow<Map>`.

**Target:** Write every decrypted location to the DB for history. The StateFlow still serves the
latest-only view for the map UI.

In `syncInbox()`, after decrypting a location, add:

```kotlin
// After: val location: LocationPayload = Json.decodeFromString(jsonString)
db.locationQueries.insertLocation(
    friend_user_id = senderId,
    latitude = location.lat,
    longitude = location.lng,
    timestamp = location.timestamp,
    received_at = currentTimeMillis()
)
newLocations[senderId] = location
```

And expose a new method for history:

```kotlin
suspend fun getLocationHistory(friendId: String, limit: Long = 50): List<LocationPayload> {
    return db.locationQueries.getLocationHistoryForFriend(friendId, limit)
        .executeAsList()
        .map { LocationPayload(it.latitude, it.longitude, it.timestamp) }
}
```

### 5.3 `PreKeyManager` — Migrate OTPKs

**Current:** Each OTPK private key is stored as `SecureStorage["onetime_prekey_private_{keyId}"]`.
This creates potentially hundreds of scattered key-value entries that are never cleaned up.

**Target:** Store OTPK private keys in the `one_time_prekey` table.

In `generateOneTimePreKeys()`:

```kotlin
// BEFORE
secureStorage.putString(
    SecureStorageKeys.oneTimePreKeyPrivate(keyId),
    Base64.encode(keyPair.privateKey)
)

// AFTER
db.oneTimePreKeyQueries.insertKey(
    key_id = keyId.toLong(),
    private_key_base64 = Base64.encode(keyPair.privateKey),
    created_at = currentTimeMillis()
)
```

In `LocationRepository.syncInbox()` (when consuming an OTPK):

```kotlin
// BEFORE
val key = secureStorage.getString(oneTimeKeyName)?.let { Base64.decode(it) }
secureStorage.remove(oneTimeKeyName)

// AFTER
val key = db.oneTimePreKeyQueries.getPrivateKey(oneTimeId.toLong())
    .executeAsOneOrNull()
    ?.let { Base64.decode(it) }
db.oneTimePreKeyQueries.deleteKey(oneTimeId.toLong())
```

> [!NOTE]
> **Security consideration:** OTPK private keys are cryptographic secrets. Storing them in
> SQLDelight means they're in a plaintext SQLite file on disk. For production, you should
> enable [SQLCipher](https://github.com/nicnacnic/sqlcipher-kmp) to encrypt the entire database, OR
> keep OTPKs in SecureStorage and only move non-secret data to SQLDelight. The trade-off is
> queryability vs. encryption-at-rest. Signal stores OTPK private keys in an encrypted SQLite
> database.

### 5.4 TOFU Identity Pinning (New Feature)

When `LocationRepository` receives a `PreKeySignalEnvelope` from a new friend, pin their Identity
Key:

```kotlin
// In syncInbox(), inside the PreKeySignalEnvelope branch, after successful X3DH:
val existingPin = db.identityPinQueries.getPin(senderId).executeAsOneOrNull()
if (existingPin != null && existingPin.identity_key_dh != header.aliceIdentityKeyDh) {
    // IDENTITY KEY CHANGED! Possible MITM or device reset.
    // Signal shows a "Safety Number changed" warning here.
    throw SecurityException("Identity key mismatch for $senderId! Expected ${existingPin.identity_key_dh}")
}
db.identityPinQueries.upsertPin(
    user_id = senderId,
    identity_key_dh = header.aliceIdentityKeyDh,
    first_seen_at = existingPin?.first_seen_at ?: currentTimeMillis(),
    last_verified_at = currentTimeMillis()
)
```

---

## 6. Dependency Injection Changes

The `FindMeDatabase` instance needs to be created once and injected. Update your DI wiring:

```kotlin
// App startup (Android)
val driverFactory = DriverFactory(applicationContext)
val driver = driverFactory.createDriver()
val database = FindMeDatabase(driver)

// Inject into repositories
val friendRepo = FriendRepositoryImpl(friendsApi, database)
val locationRepo = LocationRepositoryImpl(
    locationApi,
    keysApi,
    crypto,
    secureStorage,
    sessionStore,
    friendRepo,
    preKeyManager,
    database
)
val preKeyManager = PreKeyManagerImpl(crypto, secureStorage, keysApi, database)
```

---

## 7. Migration Checklist

Follow this order to avoid breaking the build at any step:

```
- [x] 1. Add SQLDelight dependencies to version catalog and build.gradle.kts
- [x] 2. Create all 4 `.sq` schema files
- [x] 3. Run Gradle sync, verify code generation works
- [x] 4. Create DriverFactory expect/actual for Android, iOS, and test
- [x] 5. Refactor FriendRepository to write API results to DB
         (keep the old StateFlow working alongside — dual-write)
- [x] 6. Refactor LocationRepository to persist decrypted locations
- [x] 7. Refactor PreKeyManager to use one_time_prekey table
- [x] 8. Update LocationRepository to read OTPKs from DB instead of SecureStorage
- [x] 9. Add TOFU identity pinning in the PreKeySignalEnvelope handler
- [x] 10. Update all test files to create in-memory DB and inject it
- [x] 11. Run full test suite: ./gradlew :findme-kmp:check
- [x] 12. Remove old SecureStorageKeys.oneTimePreKeyPrivate() if fully migrated
- [x] 13. Add cleanup job: db.locationQueries.deleteOldLocations(olderThan7Days)
```

---

## 8. Testing Strategy

### Unit Tests

Each repository test needs a small setup block:

```kotlin
class FriendRepositoryTest {
    @Test
    fun testSyncFriends() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FindMeDatabase.Schema.create(driver)
        val db = FindMeDatabase(driver)

        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = HttpClientFactory.create(backend.engine, storage, "http://localhost:8080")

        val repo = FriendRepositoryImpl(FriendsApi(client), db)

        // ... test as before, but now verify DB state too:
        repo.syncFriends().getOrThrow()
        val dbFriends = db.friendQueries.getAllFriends().executeAsList()
        assertEquals(1, dbFriends.size)
    }
}
```

### E2E Test

The existing `E2EMessageExchangeTest` should be updated to verify that after Alice sends and Bob
receives, Bob's `location_history` table contains the decrypted coordinates.

---

## 9. File Summary

| Action       | File Path                                                                                 |
|--------------|-------------------------------------------------------------------------------------------|
| **[MODIFY]** | `gradle/libs.versions.toml` — Add SQLDelight version + libraries + plugin                 |
| **[MODIFY]** | `build.gradle.kts` (root) — Register SQLDelight plugin                                    |
| **[MODIFY]** | `findme-kmp/build.gradle.kts` — Apply plugin, add `sqldelight {}` block, add dependencies |
| **[NEW]**    | `findme-kmp/src/commonMain/sqldelight/com/ruirui/findme/db/Friend.sq`                     |
| **[NEW]**    | `findme-kmp/src/commonMain/sqldelight/com/ruirui/findme/db/Location.sq`                   |
| **[NEW]**    | `findme-kmp/src/commonMain/sqldelight/com/ruirui/findme/db/IdentityPin.sq`                |
| **[NEW]**    | `findme-kmp/src/commonMain/sqldelight/com/ruirui/findme/db/OneTimePreKey.sq`              |
| **[NEW]**    | `findme-kmp/src/commonMain/kotlin/com/ruirui/findme/db/DriverFactory.kt`                  |
| **[NEW]**    | `findme-kmp/src/androidMain/kotlin/com/ruirui/findme/db/DriverFactory.android.kt`         |
| **[NEW]**    | `findme-kmp/src/iosMain/kotlin/com/ruirui/findme/db/DriverFactory.ios.kt`                 |
| **[MODIFY]** | `FriendRepository.kt` — Inject `FindMeDatabase`, write to DB, observe with Flow           |
| **[MODIFY]** | `LocationRepository.kt` — Persist locations, read OTPKs from DB, add TOFU                 |
| **[MODIFY]** | `PreKeyManager.kt` — Store/read OTPKs in `one_time_prekey` table                          |
| **[MODIFY]** | All test files — Create in-memory DB driver and inject                                    |

---

## 10. Gotchas & Tips

1. **SQLDelight Gradle plugin ordering:** The `sqldelight` plugin must be applied *after*
   `kotlin.multiplatform` and the Android library plugin. If you get "no targets configured" errors,
   check plugin order.

2. **iOS compilation:** SQLDelight's `NativeSqliteDriver` uses the system SQLite on iOS. No extra
   CocoaPods or SPM dependencies needed.

3. **Thread safety:** SQLDelight drivers are thread-safe. Use `Dispatchers.IO` for database
   operations in coroutines. The `.asFlow()` extension handles thread switching internally.

4. **Schema migrations:** SQLDelight handles migrations via numbered `.sqm` files. For the initial
   release, you won't need any. For future schema changes, create `1.sqm`, `2.sqm`, etc. in the same
   directory.

5. **Don't move ratchet state to SQLDelight:** The `DoubleRatchetState` JSON contains active
   symmetric chain keys. These are the crown jewels. Keep them in `SecureStorage` (encrypted at
   rest). Only move *structural/metadata* to the DB.
