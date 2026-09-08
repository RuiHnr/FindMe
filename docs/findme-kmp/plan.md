# FindMe KMP Core (`findme-kmp`) Implementation Plan & Progress

**Target Module:** `findme-kmp`  
**Platforms:** Android (`androidTarget`), iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64`)  
**Last Updated:** September 2026

---

## 1. Scope & Architecture Overview

`findme-kmp` is the cross-platform core library containing business logic, cryptography, secure local persistence, and backend networking for FindMe mobile clients.

```
FindMe/
├── findme-backend/           # Rust Axum backend (Auth, Relay, Signal PreKeys)
├── findme-kmp/               # Kotlin Multiplatform shared core
│   └── src/
│       ├── commonMain/       # DTOs, Ktor Client, Signal Crypto, Repositories, SecureStorage interface
│       ├── androidMain/      # Android Keystore Encryption + Jetpack DataStore implementation
│       ├── iosMain/          # iOS Keychain / CryptoKit implementation
│       └── commonTest/       # Test doubles (InMemorySecureStorage), Serialization & contract tests
└── spikes/
    └── androidlocationspike/ # Native Android Compose app & background location service
```

---

## 2. Implementation Progress

### Step 1: KMP Project & Gradle Infrastructure
- [x] Configure multiplatform targets (`androidTarget`, `iosArm64`, `iosSimulatorArm64`, `iosX64`).
- [x] Configure root `settings.gradle.kts` and root `build.gradle.kts` for Android Studio recognition.
- [x] Configure version catalog `gradle/libs.versions.toml` with AGP 9.1 KMP library plugin and Kotlinx Serialization.

### Step 2: Shared Models & Serialization (`commonMain/models/`)
- [x] `AuthModels.kt`: `RegisterRequest`, `RegisterResponse`.
- [x] `FriendModels.kt`: `FriendRequestDto`, `RespondFriendRequestDto`, `FriendDto`, `FriendshipStatus`.
- [x] `KeyModels.kt`: `SignedPreKeyDto`, `OneTimePreKeyDto`, `UploadKeysRequest`, `PreKeyBundleResponse`, `PreKeyCountResponse`.
- [x] `LocationModels.kt`: `SubmitLocationRequest`, `LocationPayloadResponse`.
- [x] Unit test suite in `commonTest`: `ModelSerializationTest.kt` verifying serialization symmetry with Axum Serde.

### Step 3 & 4: Modern Secure Storage
- [x] `SecureStorage` interface in `commonMain/storage/SecureStorage.kt`.
- [x] `AndroidCryptoHelper` in `androidMain/storage/`: Hardware-backed AES-256-GCM via `AndroidKeyStore` with random 12-byte IV prepending.
- [x] `AndroidSecureStorage` in `androidMain/storage/`: Jetpack `DataStore<Preferences>` with Keystore-encrypted values.
- [x] `InMemorySecureStorage` in `commonMain/storage/` for in-memory and mock environments.
- [x] `InMemorySecureStorageTest` in `commonTest/storage/` verifying interface contract with `kotlinx.coroutines.test.runTest`.

---

## 3. Next Implementation Roadmap

### Step 5: Ktor HTTP Client & API Engine (`commonMain/network`)
- [ ] **Phase 5.1: Dependencies**
  - Add Ktor 3.x dependencies to `gradle/libs.versions.toml` and `findme-kmp/build.gradle.kts`:
    - `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-auth` (`commonMain`)
    - `ktor-client-okhttp` (`androidMain`)
    - `ktor-client-darwin` (`iosMain`)
    - `ktor-client-mock` (`commonTest`)
- [ ] **Phase 5.2: `FindMeApiClient.kt`**
  - Configure `HttpClient` with JSON content negotiation and auth bearer token injection via `SecureStorage`.
  - Implement type-safe API methods matching backend Axum routes:
    - `register(username, pubKey): Result<RegisterResponse>` -> `POST /users/register`
    - `uploadPreKeys(request): Result<Unit>` -> `POST /keys`
    - `getPreKeyBundle(userId): Result<PreKeyBundleResponse>` -> `GET /keys/{userId}`
    - `getRemainingPreKeyCount(): Result<PreKeyCountResponse>` -> `GET /keys/count`
    - `submitLocation(request): Result<Unit>` -> `POST /inbox`
    - `fetchInbox(): Result<List<LocationPayloadResponse>>` -> `GET /inbox/{receiver_id}`
- [ ] **Phase 5.3: Client Unit Tests**
  - Use Ktor `MockEngine` in `commonTest` to verify headers, token injection, and response parsing.

### Step 6: Signal Protocol & Cryptography Engine (`commonMain/crypto`)
- [ ] Implement Curve25519 / X25519 key generation (Identity Key, Signed PreKey, One-Time PreKeys).
- [ ] Sign signed-prekey with Identity Key using Ed25519.
- [ ] Implement X3DH key agreement calculation (initiator and receiver).
- [ ] Implement Double Ratchet state machine for continuous forward secrecy.
- [ ] Save ratchet state and keys to `SecureStorage`.

### Step 7: Repository Layer & Reactive State (`commonMain/repository`)
- [ ] `AuthRepository`: Coordinates registration, token storage, and session lifecycle.
- [ ] `FriendRepository`: Manages friend requests, accepted friendships, and fetching prekey bundles.
- [ ] `LocationRepository`: Ingests location updates from native OS services, encrypts via Ratchet session, and dispatches to `/inbox`.
- [ ] Expose reactive Kotlin `StateFlow` and `SharedFlow` streams for UI consumption.
