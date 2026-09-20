---
name: findme-kmp
description: Detailed guidelines, architecture patterns, cryptography rules, testing requirements, and iOS compilation constraints for the FindMe Kotlin Multiplatform (KMP) core module.
---

# FindMe KMP Core Skill & Guide

Context, instructions, and constraints for the **FindMe** Kotlin Multiplatform core library
located in `findme-kmp/`.

## Architecture & Responsibilities

`findme-kmp` is the shared brain for both Android and iOS clients:

1. **Network Communication (Ktor 3.x):** All HTTP calls to the Rust backend.
2. **End-to-End Encryption (E2EE):** Full Signal Protocol — X3DH key agreement + Double Ratchet.
3. **Secure Persistence:** Sensitive keys in platform-encrypted storage (Android Keystore / iOS Keychain), structured data in SQLDelight.
4. **Reactive State Management:** Exposes `StateFlow` streams for native UIs.

## Source Map

```
findme-kmp/src/
├── commonMain/kotlin/com/ruirui/findme/
│   ├── Platform.kt                          # expect fun platform(): String
│   ├── crypto/
│   │   ├── Crypto.kt                        # Interface: X25519, Ed25519, AES-GCM, HKDF, HMAC
│   │   ├── CryptoKotlinAdapter.kt           # Actual implementation using dev.whyoleg.cryptography
│   │   ├── X3DH.kt                          # initX3DH() and receiveX3DH() key agreement functions
│   │   ├── DoubleRatchetSession.kt          # encrypt()/decrypt(), initAlice()/initBob() factory methods
│   │   ├── DoubleRatchetState.kt            # Serializable ratchet state (root key, chain keys, message counters)
│   │   ├── KdfChain.kt                      # Symmetric ratchet chain key derivation
│   │   ├── PreKeyManager.kt                 # OTPK batch generation, upload, and replenishment logic
│   │   └── SessionStore.kt                  # Load/save DoubleRatchetState to SecureStorage as JSON
│   ├── db/
│   │   └── DriverFactory.kt                 # expect class DriverFactory { fun createDriver(): SqlDriver }
│   ├── models/
│   │   ├── AuthModels.kt                    # RegisterRequest, RegisterResponse, LoginRequest, LoginResponse
│   │   ├── FriendModels.kt                  # FriendDto, FriendRequest, FriendshipStatus, RequestDirection
│   │   ├── KeyModels.kt                     # SignedPreKeyDto, OneTimePreKeyDto, UploadKeysRequest, PreKeyBundleResponse, PreKeyCountResponse
│   │   └── LocationModels.kt               # SignalMessageEnvelope (sealed: NormalSignalEnvelope, PreKeySignalEnvelope), InboxMessage, InboxResponse, SubmitMessageRequest, EncryptedMessage, LocationPayload, MessageHeader
│   ├── network/
│   │   ├── ApiResult.kt                     # safeApiCall<T>() — wraps Ktor calls into Result<T>
│   │   ├── HttpClientFactory.kt             # Creates Ktor HttpClient with JSON + Auth Bearer plugins
│   │   └── api/
│   │       ├── AuthApi.kt                   # register(), login()
│   │       ├── FriendsApi.kt                # request(), accept(), getFriends(), getFriendRequests()
│   │       ├── KeysApi.kt                   # uploadKeys(), getPreKeyBundle(), getPreKeyCount()
│   │       └── LocationApi.kt               # submitMessage(), getInbox()
│   ├── repository/
│   │   ├── AuthRepository.kt               # register/login, stores JWT + keys in SecureStorage
│   │   ├── FriendRepository.kt             # syncFriends() writes API→DB, exposes StateFlow<List<FriendDto>>
│   │   └── LocationRepository.kt           # syncInbox() (fetch→decrypt→DB), submitLocalLocation() (encrypt→send), friendLocations StateFlow
│   └── storage/
│       ├── SecureStorage.kt                 # Interface: getString, putString, remove, clear
│       ├── SecureStorageKeys.kt             # Constants for all key names (AUTH_TOKEN, IDENTITY_PRIVATE_KEY_DH, etc.)
│       └── InMemorySecureStorage.kt         # Test implementation
│
├── commonMain/sqldelight/com/ruirui/findme/db/
│   ├── Friend.sq                            # friend + friend_request tables and queries
│   ├── Location.sq                          # location_history table (per-friend timestamped coords)
│   ├── IdentityPin.sq                       # identity_pin table (TOFU key pinning)
│   └── OneTimePreKey.sq                     # one_time_prekey table (OTPK private key storage)
│
├── androidMain/kotlin/com/ruirui/findme/
│   ├── Platform.android.kt
│   ├── db/DriverFactory.kt                  # AndroidSqliteDriver("findme.db")
│   └── storage/
│       ├── AndroidCryptoHelper.kt           # AES-256-GCM via AndroidKeyStore with random 12-byte IV
│       └── SecureStorage.kt                 # actual class: Jetpack DataStore<Preferences> + Keystore encryption
│
├── iosMain/kotlin/com/ruirui/findme/
│   ├── Platform.ios.kt
│   └── db/DriverFactory.kt                  # NativeSqliteDriver("findme.db")
│
├── commonTest/kotlin/com/ruirui/findme/
│   ├── crypto/
│   │   ├── CryptoKotlinAdapterTest.kt       # X25519 key gen, Ed25519 sign/verify, AES-GCM round-trip
│   │   ├── X3DHTest.kt                      # Shared secret agreement between Alice and Bob
│   │   └── DoubleRatchetSessionTest.kt      # Multi-message encrypt/decrypt, out-of-order, ratchet advancement
│   ├── db/TestDatabase.kt                   # expect fun createTestDatabase(): FindMeDatabase
│   ├── e2e/E2EMessageExchangeTest.kt        # Full flow: register → exchange prekeys → X3DH → ratchet → decrypt
│   ├── fakes/
│   │   ├── FakeBackend.kt                   # Complete MockEngine backend (routes all endpoints)
│   │   ├── FakeFriendsApiBackend.kt         # Friend request/accept/list mock
│   │   ├── FakeKeysApiBackend.kt            # PreKey upload/fetch/count mock
│   │   └── FakeLocationApiBackend.kt        # Inbox submit/fetch mock
│   ├── models/ModelSerializationTest.kt     # JSON round-trip matching Axum Serde format
│   ├── network/api/NetworkApiTest.kt        # Ktor MockEngine header/auth/response tests
│   ├── repository/
│   │   ├── AuthRepositoryTest.kt            # Registration + token storage
│   │   ├── FriendRepositoryTest.kt          # Sync friends → verify DB state
│   │   └── LocationRepositoryTest.kt        # Inbox sync → decrypt → verify DB + StateFlow
│   └── storage/InMemorySecureStorageTest.kt # SecureStorage interface contract tests
│
├── androidHostTest/kotlin/.../db/TestDatabase.android.kt  # JdbcSqliteDriver(IN_MEMORY)
└── iosTest/kotlin/.../db/TestDatabase.ios.kt              # NativeSqliteDriver(IN_MEMORY)
```

## Key Data Flows

### Sending a Location (Alice → Bob)

1. `LocationRepository.submitLocalLocation(lat, lng)` is called.
2. Queries `db.friendQueries.getAllFriends()` for all accepted friends.
3. For each friend (concurrently via `async/awaitAll`):
   - Loads `DoubleRatchetSession` from `SessionStore`. If null (first message):
     - Fetches Bob's `PreKeyBundle` via `KeysApi.getPreKeyBundle()`.
     - Verifies signed prekey signature with Ed25519.
     - Runs `initX3DH()` to derive shared secret.
     - Creates `DoubleRatchetSession.initAlice()`.
     - Wraps encrypted payload in `PreKeySignalEnvelope` (includes X3DH public keys).
   - If session exists: encrypts with `session.encrypt()`, wraps in `NormalSignalEnvelope`.
   - Saves session state back to `SessionStore`.
   - Submits via `LocationApi.submitMessage(SubmitMessageRequest(receiverId, json))`.
4. Saves own location to `db.locationQueries.insertOwnLocation()`.

### Receiving Locations (Bob's Inbox Sync)

1. `LocationRepository.syncInbox()` calls `LocationApi.getInbox()` → `GET /inbox`.
2. For each `InboxMessage`:
   - Loads session from `SessionStore`. If null and envelope is `PreKeySignalEnvelope`:
     - Reads Bob's private identity key + signed prekey from `SecureStorage`.
     - Reads and deletes Bob's OTPK from `db.oneTimePreKeyQueries`.
     - Runs `receiveX3DH()` to derive shared secret.
     - Checks identity pin via `db.identityPinQueries.getPin()` (TOFU / MITM detection).
     - Creates `DoubleRatchetSession.initBob()`.
   - Decrypts `envelope.ciphertext` via `session.decrypt()`.
   - Saves advanced session state.
   - Parses `LocationPayload` from JSON, inserts into `db.locationQueries.insertLocationForFriend()`.
3. Checks `X-Remaining-PreKeys` header; replenishes OTPKs if below threshold (20).

## Cryptography Rules

- **Lazy session init:** X3DH sessions are NOT created when a friend is accepted. They're initialized on the first location send/receive.
- **Ed25519 signature verification:** SignedPreKey must be verified before any X3DH computation.
- **Session persistence:** `SessionStore` serializes `DoubleRatchetState` to `SecureStorage` as JSON after every ratchet turn. If the app is killed mid-ratchet, the state must not be lost.
- **OTPK single-use:** One-Time PreKeys are deleted from the DB immediately after use for Perfect Forward Secrecy.

## What Lives Where (Storage Split)

### SecureStorage (encrypted at rest — Android Keystore / iOS Keychain)

| Key Pattern                       | Content                                    |
|-----------------------------------|--------------------------------------------|
| `auth_token`                      | JWT bearer token                           |
| `user_id`                         | User's own UUID                            |
| `identity_private_key_dh`         | Long-term X25519 private key               |
| `identity_public_key_dh`          | Long-term X25519 public key                |
| `identity_private_key_sign`       | Long-term Ed25519 signing private key      |
| `identity_public_key_sign`        | Long-term Ed25519 signing public key       |
| `signed_prekey_private_{id}`      | Signed PreKey private keys                 |
| `signed_prekey_public_{id}`       | Signed PreKey public keys                  |
| `current_signed_prekey_id`        | Active SPK ID tracker                      |
| `ratchet_state_{friendId}`        | Serialized DoubleRatchetState JSON         |

### SQLDelight Database (findme.db)

| Table               | Content                                    |
|---------------------|--------------------------------------------|
| `friend`            | Accepted friends (user_id, username)       |
| `friend_request`    | Pending requests (target_username, direction) |
| `location_history`  | Timestamped friend locations (lat, lng)    |
| `identity_pin`      | TOFU identity key pins for MITM detection  |
| `one_time_prekey`   | OTPK private keys (key_id, base64 key)    |

## Testing & Mocking

- **No MockK / Mockito.** Tests run cross-platform. Use hand-written fakes and Ktor `MockEngine`.
- **`FakeBackend`** in `commonTest/fakes/` provides a complete in-memory mock server.
- **Auth token injection:** Ktor's `Auth` plugin evaluates `loadTokens` internally. You MUST inject a token into `InMemorySecureStorage` BEFORE creating the `HttpClient`, or requests fail with 401.
- **JSON discriminators:** When faking JSON for sealed classes in MockEngine, the `"type"` field must exactly match `@SerialName` (e.g., `"type": "normal_message"`, `"type": "prekey_message"`).
- **Test database:** Use `createTestDatabase()` (expect/actual) which provides an in-memory SQLite driver.

## iOS Compilation Quirks (CRITICAL)

- **Never use fully qualified extension function calls.**
  - ❌ `io.ktor.serialization.kotlinx.json.json(Json { ... })`
  - ✅ `import io.ktor.serialization.kotlinx.json.json` then call `json(Json { ... })`
  - Fully-qualified calls crash the Kotlin Native / iOS compiler during linkage.

## Build Commands

```bash
# Run all KMP tests (JVM host tests + iOS simulator tests)
./gradlew :findme-kmp:check

# Android host tests only
./gradlew :findme-kmp:testAndroidHostTest

# iOS simulator tests only
./gradlew :findme-kmp:iosSimulatorArm64Test

# Regenerate SQLDelight code after .sq file changes
./gradlew :findme-kmp:generateCommonMainFindMeDatabaseInterface
```
