# FindMe KMP: Repository Layer & UI Integration Guide

This document outlines how the Kotlin Multiplatform (KMP) repository layer integrates with the native UIs (Jetpack Compose and SwiftUI), and provides a step-by-step implementation guide with concrete code examples for the core repositories.

---

## 1. Connecting the KMP Layer to the UI

The **Repository Layer** in `findme-kmp` acts as the single source of truth for the native apps. It abstracts away network calls, cryptography, and local storage.

### 1.1 The Repository API Design (KMP)
The repositories will expose their APIs using Kotlin Coroutines:
*   **State:** Exposed as `StateFlow<T>`. This allows the UI to observe changes reactively (e.g., `isLoggedIn`, `friendsList`, `latestLocations`).
*   **Actions:** Exposed as `suspend` functions for one-shot operations (e.g., `register()`, `sendFriendRequest()`, `syncInbox()`).

### 1.2 Android Integration (Jetpack Compose)
On Android, the integration is native because both KMP and Android use Kotlin and Coroutines.
*   **Architecture:** The Android app will use `ViewModel` classes that instantiate or inject the KMP Repositories.
*   **State Consumption:** The `ViewModel` exposes the KMP `StateFlow` directly to the Compose UI. The Compose UI observes it using `collectAsStateWithLifecycle()`.
*   **Action Execution:** When the user clicks a button, the Compose UI calls a `ViewModel` function, which launches a coroutine (`viewModelScope.launch`) to call the Repository's `suspend` function.

### 1.3 iOS Integration (SwiftUI)
On iOS, Kotlin Coroutines need to be translated into Swift Concurrency concepts.
*   **Architecture:** The SwiftUI app will use a Swift class annotated with `@Observable` (or `ObservableObject`) acting as the ViewModel. This Swift ViewModel holds a reference to the KMP Repository.
*   **Bridging Coroutines:** 
    *   Using the standard KMP compiler or a plugin like **SKIE** (highly recommended), Kotlin `suspend` functions are mapped to Swift `async throws` functions.
    *   Kotlin `StateFlow` is mapped to Swift `AsyncSequence`.
*   **State Consumption:** In the Swift ViewModel, a `Task` is launched to iterate over the `AsyncSequence` (`for await state in repository.stateFlow { ... }`), updating Swift `@Published` or `@Observable` properties which SwiftUI binds to.
*   **Action Execution:** SwiftUI buttons trigger Swift ViewModel functions, which use `Task { try await repository.register() }`.

---

## 2. Concrete Implementations & Guides

### 2.1 Easiest: `AuthRepository`

**The Job:**
Manage the user's identity, device registration, and local session lifecycle. 

**Hints & Considerations:**
*   **Initialization:** Reading from `SecureStorage` (especially Jetpack DataStore on Android) can be asynchronous. Your `AuthState` should start as `Loading` until you've confirmed if a token exists.
*   **Key Generation:** This repository is responsible for generating the initial Identity Keypair during registration, ensuring that every identity is cryptographically backed from the start.
*   **Error Handling:** Use Kotlin's `Result` type to safely pass backend/network errors to the UI without crashing the app.

**Concrete Code (Best Solution):**

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val userId: String) : AuthState
}

interface AuthRepository {
    val authState: StateFlow<AuthState>
    suspend fun init()
    suspend fun register(username: String): Result<Unit>
    suspend fun logout()
}

class AuthRepositoryImpl(
    private val apiClient: FindMeApiClient,
    private val secureStorage: SecureStorage,
    private val cryptoEngine: CryptoEngine,
    private val appScope: CoroutineScope // e.g. Dispatchers.Default
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override suspend fun init() {
        // Read async from secure storage
        val token = secureStorage.getString("auth_token")
        val userId = secureStorage.getString("user_id")
        
        if (token != null && userId != null) {
            _authState.value = AuthState.Authenticated(userId)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    override suspend fun register(username: String): Result<Unit> = runCatching {
        // 1. Generate cryptographic identity
        val identityKeyPair = cryptoEngine.generateIdentityKeyPair()
        val publicKeyString = cryptoEngine.encodePublicKey(identityKeyPair.publicKey)
        
        // 2. Network call to Axum backend
        val response = apiClient.register(username, publicKeyString).getOrThrow()
        
        // 3. Persist secrets securely
        secureStorage.putString("auth_token", response.token) // Ktor client reads this implicitly via interceptor
        secureStorage.putString("user_id", response.userId)
        secureStorage.putString("identity_private_key", cryptoEngine.encodePrivateKey(identityKeyPair.privateKey))
        
        // 4. Update UI State
        _authState.value = AuthState.Authenticated(response.userId)
    }

    override suspend fun logout() {
        secureStorage.clear() // Wipes tokens and keys
        _authState.value = AuthState.Unauthenticated
    }
}
```

---

### 2.2 Medium: `FriendRepository`

**The Job:**
Manage the social graph (friend requests, active friendships) and orchestrate the cryptographic handshakes required when a friendship is established.

**Hints & Considerations:**
*   **Separation of State:** Keep `friends` (accepted) and `requests` (pending) in separate state flows so the UI can observe them independently.
*   **The Crypto Handshake:** The hardest part is orchestration. Accepting a friend request isn't just an API call; it requires downloading their `PreKeyBundle` and feeding it into the `CryptoEngine` to initialize a Double Ratchet session *before* you can send or receive locations.
*   **Atomicity:** If the API call to accept a friend succeeds, but the key bundle fetch fails, you're left in a broken state. Make sure to handle this cleanly (e.g. retry mechanisms).

**Concrete Code (Best Solution):**

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface FriendRepository {
    val friends: StateFlow<List<FriendDto>>
    val friendRequests: StateFlow<List<FriendRequestDto>>
    
    suspend fun syncFriends(): Result<Unit>
    suspend fun sendFriendRequest(targetUsername: String): Result<Unit>
    suspend fun acceptFriendRequest(requestId: String, friendId: String): Result<Unit>
}

class FriendRepositoryImpl(
    private val apiClient: FindMeApiClient,
    private val cryptoEngine: CryptoEngine,
    private val secureStorage: SecureStorage
) : FriendRepository {

    private val _friends = MutableStateFlow<List<FriendDto>>(emptyList())
    override val friends = _friends.asStateFlow()

    private val _friendRequests = MutableStateFlow<List<FriendRequestDto>>(emptyList())
    override val friendRequests = _friendRequests.asStateFlow()

    override suspend fun syncFriends(): Result<Unit> = runCatching {
        // Fetch from API in parallel or sequentially
        val friendsList = apiClient.getFriends().getOrThrow()
        val requestsList = apiClient.getFriendRequests().getOrThrow()
        
        _friends.value = friendsList
        _friendRequests.value = requestsList
    }

    override suspend fun sendFriendRequest(targetUsername: String): Result<Unit> = runCatching {
        apiClient.sendFriendRequest(targetUsername).getOrThrow()
        syncFriends() // Refresh local state
    }

    override suspend fun acceptFriendRequest(requestId: String, friendId: String): Result<Unit> = runCatching {
        // 1. Tell backend we accept
        apiClient.respondFriendRequest(requestId, accept = true).getOrThrow()
        
        // 2. Initiate Cryptographic Session
        // Download the friend's PreKey bundle to initialize the ratchet session
        val preKeyBundle = apiClient.getPreKeyBundle(friendId).getOrThrow()
        
        // Pass to crypto engine to perform X3DH and initialize Double Ratchet
        val sessionState = cryptoEngine.initializeSessionAsInitiator(preKeyBundle)
        
        // Save the initialized ratchet state so LocationRepository can use it
        secureStorage.putString("ratchet_state_$friendId", cryptoEngine.serializeSession(sessionState))
        
        // 3. Update UI state
        syncFriends()
    }
}
```

---

### 2.3 Hardest: `LocationRepository`

**The Job:**
Act as the secure relay between the native OS location services, the Crypto Engine, and the backend blind relay (`/inbox`).

**Hints & Considerations:**
*   **Concurrency:** When emitting a location, you must encrypt and send it uniquely for *every* friend. Do this concurrently (`async` + `awaitAll`) rather than sequentially to avoid blocking the background location task.
*   **Error Isolation:** If encrypting for Friend A fails (e.g. corrupted state), it should *not* prevent Friend B from receiving your location. Wrap per-friend operations in `runCatching`.
*   **State Updates (Ratchet):** The Double Ratchet algorithm evolves its keys with every message sent/received. You **must** save the updated session state back to `SecureStorage` after every encryption/decryption, otherwise future messages will fail to decrypt.

**Concrete Code (Best Solution):**

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Domain model
data class LocationPayload(val lat: Double, val lng: Double, val timestamp: Long)

interface LocationRepository {
    val friendLocations: StateFlow<Map<String, LocationPayload>>
    
    // Called by iOS / Android native background location delegates
    suspend fun submitLocalLocation(lat: Double, lng: Double)
    
    // Called periodically or via Push Notification wake-ups
    suspend fun syncInbox()
}

class LocationRepositoryImpl(
    private val apiClient: FindMeApiClient,
    private val cryptoEngine: CryptoEngine,
    private val secureStorage: SecureStorage,
    private val friendRepository: FriendRepository
) : LocationRepository {

    // Maps a friend's userId to their last known Location
    private val _friendLocations = MutableStateFlow<Map<String, LocationPayload>>(emptyMap())
    override val friendLocations = _friendLocations.asStateFlow()

    override suspend fun submitLocalLocation(lat: Double, lng: Double) {
        val payload = LocationPayload(lat, lng, System.currentTimeMillis())
        val jsonPayload = serializeLocation(payload) // Convert to String/Bytes
        
        val friends = friendRepository.friends.value

        coroutineScope {
            // Process all friends concurrently
            friends.map { friend ->
                async {
                    runCatching {
                        // 1. Load session for this friend
                        val serializedState = secureStorage.getString("ratchet_state_${friend.userId}") 
                            ?: throw Exception("No session for ${friend.userId}")
                        var sessionState = cryptoEngine.deserializeSession(serializedState)
                        
                        // 2. Encrypt specifically for this friend's session
                        val (encryptedBlob, newState) = cryptoEngine.encrypt(sessionState, jsonPayload)
                        
                        // 3. Save the advanced ratchet state immediately!
                        secureStorage.putString("ratchet_state_${friend.userId}", cryptoEngine.serializeSession(newState))
                        
                        // 4. Send to the blind backend relay
                        apiClient.submitLocation(receiverId = friend.userId, encryptedBlob = encryptedBlob)
                    }
                }
            }.awaitAll() // Wait for all parallel tasks to finish
        }
    }

    override suspend fun syncInbox() {
        val inboxMessages = runCatching { apiClient.fetchInbox().getOrThrow() }.getOrElse { return }
        
        val newLocations = _friendLocations.value.toMutableMap()

        inboxMessages.forEach { message ->
            runCatching {
                val senderId = message.senderId
                
                // 1. Load session for sender
                val serializedState = secureStorage.getString("ratchet_state_$senderId") 
                    ?: throw Exception("No session for $senderId")
                var sessionState = cryptoEngine.deserializeSession(serializedState)
                
                // 2. Decrypt message using Ratchet
                val (decryptedString, newState) = cryptoEngine.decrypt(sessionState, message.encryptedBlob)
                
                // 3. Save the advanced ratchet state!
                secureStorage.putString("ratchet_state_$senderId", cryptoEngine.serializeSession(newState))
                
                // 4. Parse coordinates and update map
                val location = deserializeLocation(decryptedString)
                newLocations[senderId] = location
            }
        }
        
        // Emit new state to the UI maps
        _friendLocations.value = newLocations
    }
    
    private fun serializeLocation(loc: LocationPayload): String = "${loc.lat},${loc.lng},${loc.timestamp}"
    private fun deserializeLocation(data: String): LocationPayload {
        val parts = data.split(",")
        return LocationPayload(parts[0].toDouble(), parts[1].toDouble(), parts[2].toLong())
    }
}
```

---

## 3. Kotlin & Jetpack Compose Syntax Glossary

If you are new to Kotlin or Android development, here is a breakdown of the specific syntax and concepts used in the code above:

### 3.1 Kotlin Coroutines (`suspend`, `launch`, `async`)
*   **`suspend fun`**: A function that can be paused and resumed later without blocking the main thread. It's Kotlin's answer to `async/await` in JavaScript or Swift. You can only call a `suspend` function from another `suspend` function or from inside a coroutine.
*   **`CoroutineScope` / `viewModelScope`**: Coroutines need a "scope" to run in. This defines their lifecycle. `viewModelScope` is built into Android; any coroutine launched in it will automatically be canceled if the screen/ViewModel is destroyed, preventing memory leaks.
*   **`launch { ... }`**: Starts a new coroutine that doesn't return a result (like "fire and forget"). Used in ViewModels to trigger repository actions.
*   **`async { ... }`**: Starts a coroutine that *does* return a result (a `Deferred<T>`, similar to a Promise). 
*   **`awaitAll()`**: Takes a list of `Deferred` (from multiple `async` calls) and waits for all of them to finish concurrently. Used in `LocationRepository` to encrypt locations for multiple friends at the same time.
*   **`coroutineScope { ... }`**: A block that waits for all child coroutines (like the `async` ones) to finish before moving on. It ensures structured concurrency.

### 3.2 Reactive State (`StateFlow`, `MutableStateFlow`)
*   **`StateFlow<T>`**: An observable data holder that always holds exactly one value. The UI observes this to know when to redraw.
*   **`MutableStateFlow<T>`**: The internal, mutable version of `StateFlow`. We use this inside the repository to update the value (e.g., `_authState.value = ...`).
*   **`.asStateFlow()`**: A safety mechanism. We expose `_authState.asStateFlow()` to the public API so the UI can *read* the state, but cannot *modify* it directly.
*   **`collectAsStateWithLifecycle()` (Jetpack Compose)**: A special Jetpack Compose function. It converts a Kotlin `StateFlow` into a Compose `State` object. It automatically stops listening to the flow when the app goes into the background, saving battery—crucial for a background location app!

### 3.3 Error Handling (`runCatching`, `Result`)
*   **`runCatching { ... }`**: Kotlin's functional equivalent to a `try-catch` block. It executes the code inside the block and wraps the outcome in a `Result` object.
*   **`Result<T>`**: Can be either a `Success` (holding the value) or a `Failure` (holding the Exception). It's much safer to return `Result` from a repository than throwing exceptions, because it forces the ViewModel (and thus the UI) to explicitly handle success and failure cases.
*   **`.getOrThrow()`**: Unwraps the `Result`. If it's a success, it returns the value. If it's a failure, it throws the exception. We use this inside the repository to fail fast if an API call breaks.
*   **`.getOrElse { ... }`**: Unwraps the `Result`, but if it failed, executes the fallback block (like returning early).

### 3.4 Data Models (`data class`, `sealed interface`)
*   **`sealed interface` / `sealed class`**: Defines a restricted hierarchy. The compiler knows all possible subclasses (like `Loading`, `Unauthenticated`, `Authenticated`). When the UI uses a `when` (switch) statement to check the state, the compiler will force you to handle all three cases, preventing UI bugs.
*   **`data class`**: A class whose main purpose is to hold data. Kotlin automatically generates `equals()`, `hashCode()`, and `toString()` for it.
*   **`data object`**: A singleton used in sealed hierarchies when there is no data to hold (e.g., `AuthState.Loading`).
