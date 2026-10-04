# Signal Protocol & Cryptography Engine in KMP

This document provides an assessment of the different architectural options for implementing the
Signal Protocol (X3DH and Double Ratchet) in the Kotlin Multiplatform (`findme-kmp`) module,
followed by a step-by-step implementation guide for the best approach.

---

## Part 1: Options Assessment

When building E2EE into a KMP module targeting iOS and Android, there are four main approaches:

### Option 1: Official `libsignal-client` via Platform Wrappers

The Signal Foundation maintains a Rust-based `libsignal-client` with official Java and Swift
bindings.

* **Pros:** The gold standard for security. Fully audited, spec-compliant, and highly optimized.
* **Cons:** Extreme build complexity. You would need to add the Android AAR as a dependency in
  `androidMain`, compile the Swift package into an XCFramework for `iosMain`, and then write complex
  Kotlin `expect`/`actual` wrappers to bridge the two drastically different native APIs into your
  `commonMain` logic.

### Option 2: Shared Rust Core via Mozilla UniFFI

Instead of KMP, write the crypto core in Rust (using crates like `x3dh` and `doubleratchet`) and use
UniFFI to generate Kotlin and Swift bindings.

* **Pros:** Single source of truth in Rust; high performance.
* **Cons:** Breaks your existing `findme-kmp` architecture. You would have to manage
  cross-compilation toolchains for iOS and Android inside your repository, completely bypassing the
  Kotlin Multiplatform toolchain for this layer.

### Option 3: Pure Kotlin Implementations (e.g., korlibs-crypto or Kodium)

Use a 100% Kotlin cryptography library (like `korlibs-crypto` or a community Double Ratchet KMP port
like `Kodium`).

* **Pros:** Zero native dependencies. Easiest to integrate, build, and debug. 100% of the code sits
  in `commonMain`.
* **Cons:** Pure software implementations of cryptography are typically slower and miss out on
  OS-level hardware acceleration and secure enclaves. They are also rarely subjected to professional
  security audits.

### Option 4: KMP Crypto Primitives + Custom Protocol State Machine (✨ Recommended)

Use a KMP abstraction library for cryptographic **primitives** (like `cryptography-kotlin` by
whyoleg) and build the X3DH and Double Ratchet **protocol state machines** yourself in `commonMain`.

* **Pros:** You get OS-level security and hardware acceleration (it binds to Apple CryptoKit on iOS
  and Android Keystore/BouncyCastle on Android). You avoid C-interop/JNI build nightmares because
  the library handles the platform bridges. You maintain full control over the protocol logic in
  Kotlin.
* **Cons:** You have to write the X3DH and Double Ratchet state machines yourself based on the
  Signal specifications.

**Verdict:** For FindMe, **Option 4** is the best choice. It perfectly balances cross-platform ease
of use, hardware-backed security, and educational value.

---

## Part 2: Step-by-Step Implementation Guide

This guide breaks down **Option 4** into manageable steps you can implement in `commonMain`.

### Step 1: Add Dependencies

We will use `cryptography-kotlin` to provide the primitive operations (X25519, Ed25519, AES-GCM,
HMAC, SHA).

**1. Update `gradle/libs.versions.toml`:**

```toml
[versions]
cryptography = "0.6.0"

[libraries]
cryptography-core = { module = "dev.whyoleg.cryptography:cryptography-core", version.ref = "cryptography" }
cryptography-provider-optimal = { module = "dev.whyoleg.cryptography:cryptography-provider-optimal", version.ref = "cryptography" }
```

**2. Update `findme-kmp/build.gradle.kts`:**

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation(libs.cryptography.core)
        implementation(libs.cryptography.provider.optimal)
    }
}
```

---

### Step 2: Define Cryptographic Primitives Wrapper

To make the Double Ratchet implementation clean, define a wrapper interface in
`commonMain/crypto/CryptoProvider.kt` that abstracts the suspending functions from the
`cryptography-kotlin` library.

```kotlin
// commonMain/crypto/CryptoProvider.kt
interface FindMeCrypto {
    // HKDF based on SHA-256
    suspend fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLength: Int): ByteArray

    // HMAC-SHA256
    suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    // AES-256-GCM
    suspend fun encryptAesGcm(
        key: ByteArray,
        plaintext: ByteArray,
        iv: ByteArray,
        ad: ByteArray
    ): ByteArray
    suspend fun decryptAesGcm(
        key: ByteArray,
        ciphertext: ByteArray,
        iv: ByteArray,
        ad: ByteArray
    ): ByteArray

    // X25519 (Elliptic Curve Diffie-Hellman)
    suspend fun generateX25519KeyPair(): KeyPair
    suspend fun calculateDhAgreement(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    // Ed25519 (Signatures)
    suspend fun generateEd25519KeyPair(): KeyPair
    suspend fun sign(privateKey: ByteArray, message: ByteArray): ByteArray
    suspend fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)
```

You will instantiate this using `CryptographyProvider.Default` which automatically resolves to JDK
on Android and CryptoKit on iOS.

---

### Step 3: Implement X3DH (Key Agreement)

The X3DH (Extended Triple Diffie-Hellman) protocol establishes a shared secret between two users.

**1. The Initiator (Sending a Friend Request / First Message):**
When Alice wants to message Bob, she fetches his `PreKeyBundleResponse` from the API.

```kotlin
// commonMain/crypto/X3DH.kt
suspend fun initiateX3DH(
    crypto: FindMeCrypto,
    aliceIdentityKey: KeyPair,
    aliceBaseKey: KeyPair,     // Ephemeral X25519 key Alice generates right now
    bobIdentityKey: ByteArray, // From bundle
    bobSignedPreKey: ByteArray, // From bundle
    bobOneTimePreKey: ByteArray? // From bundle (if available)
): ByteArray {
    // 1. Calculate DH Outputs
    val dh1 = crypto.calculateDhAgreement(aliceIdentityKey.privateKey, bobSignedPreKey)
    val dh2 = crypto.calculateDhAgreement(aliceBaseKey.privateKey, bobIdentityKey)
    val dh3 = crypto.calculateDhAgreement(aliceBaseKey.privateKey, bobSignedPreKey)

    var sharedSecret = dh1 + dh2 + dh3

    // If a one-time prekey was available, add a 4th DH
    if (bobOneTimePreKey != null) {
        val dh4 = crypto.calculateDhAgreement(aliceBaseKey.privateKey, bobOneTimePreKey)
        sharedSecret += dh4
    }

    // 2. Run through HKDF to generate the Master Secret
    return crypto.hkdf(
        ikm = sharedSecret,
        salt = ByteArray(32) { 0 }, // 32 bytes of zeros
        info = "FindMe-X3DH".encodeToByteArray(),
        outLength = 32
    )
}
```

---

### Step 4: Implement the Double Ratchet

The Double Ratchet uses the `sharedSecret` from X3DH to create rotating keys for every message. It
consists of a **Root Ratchet** (Diffie-Hellman, changes per conversation turn) and a
**Sending/Receiving Ratchet** (Symmetric KDF, changes per message).

Because FindMe locations can arrive out-of-order or be dropped (due to mobile networks), the implementation must support **skipped messages**.

**1. Symmetric Key Ratchet (KDF Chain):**
Creates a new Message Key for encryption and advances the chain. It also tracks the message index.

```kotlin
// commonMain/crypto/KdfChain.kt
class KdfChain(var chainKey: ByteArray, var index: Int = 0, val crypto: FindMeCrypto) {
    suspend fun next(): Pair<ByteArray, Int> {
        // HMAC with specific constants (e.g., 0x01 for Message Key, 0x02 for Next Chain Key)
        val messageKey = crypto.hmacSha256(chainKey, byteArrayOf(0x01))
        chainKey = crypto.hmacSha256(chainKey, byteArrayOf(0x02))
        val currentIndex = index
        index++
        return Pair(messageKey, currentIndex)
    }
}
```

**2. The Double Ratchet Session:**
The session manages the root chain, the sending/receiving chains, and stores skipped message keys.

```kotlin
// commonMain/crypto/DoubleRatchetSession.kt
@OptIn(ExperimentalEncodingApi::class)
class DoubleRatchetSession(
    rootKey: ByteArray,
    sendingChain: KdfChain?,
    receivingChain: KdfChain?,
    sendingRatchetKey: KeyPair,
    receivingRatchetKey: ByteArray?,
    val skippedMessageKeys: MutableMap<String, MutableMap<Int, ByteArray>> = mutableMapOf(),
    previousSendingChainLength: Int = 0,
    private val crypto: FindMeCrypto
) {
    var rootKey: ByteArray = rootKey
        private set
    var sendingChain: KdfChain? = sendingChain
        private set
    var receivingChain: KdfChain? = receivingChain
        private set
    var sendingRatchetKey: KeyPair = sendingRatchetKey
        private set
    var receivingRatchetKey: ByteArray? = receivingRatchetKey
        private set
    var previousSendingChainLength: Int = previousSendingChainLength
        private set

    companion object {
        const val MAX_SKIP = 2000

        // Initialization for Alice (Initiator)
        suspend fun initAlice(
            sharedSecret: ByteArray,
            bobSignedPreKeyPub: ByteArray,
            crypto: FindMeCrypto
        ): DoubleRatchetSession {
            val aliceRatchetKey = crypto.generateX25519KeyPair()
            val dhOut = crypto.calculateDhAgreement(aliceRatchetKey.privateKey, bobSignedPreKeyPub)
            val (rootKey, sendingChainKey) = hkdfSplit(sharedSecret, dhOut, crypto)

            return DoubleRatchetSession(
                rootKey = rootKey,
                sendingChain = KdfChain(sendingChainKey, 0, crypto),
                receivingChain = null,
                sendingRatchetKey = aliceRatchetKey,
                receivingRatchetKey = bobSignedPreKeyPub,
                previousSendingChainLength = 0,
                crypto = crypto
            )
        }

        // Initialization for Bob (Receiver)
        suspend fun initBob(
            sharedSecret: ByteArray,
            bobRatchetKeyPair: KeyPair,
            crypto: FindMeCrypto
        ): DoubleRatchetSession {
            return DoubleRatchetSession(
                rootKey = sharedSecret,
                sendingChain = null,
                receivingChain = null,
                sendingRatchetKey = bobRatchetKeyPair,
                receivingRatchetKey = null,
                previousSendingChainLength = 0,
                crypto = crypto
            )
        }

        private suspend fun hkdfSplit(root: ByteArray, dh: ByteArray, crypto: FindMeCrypto): Pair<ByteArray, ByteArray> {
            val output = crypto.hkdf(dh, root, "FindMe-Ratchet".encodeToByteArray(), 64)
            return Pair(output.copyOfRange(0, 32), output.copyOfRange(32, 64))
        }
    }

    suspend fun encrypt(plaintext: ByteArray): EncryptedMessage {
        val (messageKey, msgNum) = sendingChain!!.next()
        val ciphertextWithIv = crypto.encryptAesGcm(messageKey, plaintext, ad = byteArrayOf())

        return EncryptedMessage(
            ratchetKey = sendingRatchetKey.publicKey,
            msgNumber = msgNum,
            previousChainLength = previousSendingChainLength,
            ciphertext = ciphertextWithIv
        )
    }

    suspend fun decrypt(message: EncryptedMessage): ByteArray {
        val remoteKeyStr = Base64.encode(message.ratchetKey)

        // 1. Check if we already skipped this message and saved its key
        skippedMessageKeys[remoteKeyStr]?.get(message.msgNumber)?.let { messageKey ->
            skippedMessageKeys[remoteKeyStr]!!.remove(message.msgNumber)
            return crypto.decryptAesGcm(messageKey, message.ciphertext, ad = byteArrayOf())
        }

        // 2. If the ratchet key changed, perform a Diffie-Hellman Ratchet step
        if (!message.ratchetKey.contentEquals(receivingRatchetKey)) {
            skipMessageKeys(message.previousChainLength)
            performDhRatchet(message.ratchetKey)
        }

        // 3. Fast-forward missed messages in the NEW chain up to the current message
        skipMessageKeys(message.msgNumber)

        // 4. Decrypt the actual message
        val (messageKey, _) = receivingChain!!.next()
        return crypto.decryptAesGcm(messageKey, message.ciphertext, ad = byteArrayOf())
    }

    private suspend fun skipMessageKeys(until: Int) {
        val chain = receivingChain ?: return
        if (until - chain.index > MAX_SKIP) {
            throw IllegalArgumentException("Too many skipped messages: ${until - chain.index}")
        }
        val remoteKeyStr = Base64.encode(receivingRatchetKey!!)
        while (chain.index < until) {
            val (msgKey, msgNum) = chain.next()
            val map = skippedMessageKeys.getOrPut(remoteKeyStr) { mutableMapOf() }
            map[msgNum] = msgKey
        }
    }

    private suspend fun performDhRatchet(newRemoteKey: ByteArray) {
        receivingRatchetKey = newRemoteKey

        // 1. DH between our sending key and their new receiving key
        val dh1 = crypto.calculateDhAgreement(sendingRatchetKey.privateKey, receivingRatchetKey!!)
        val (newRoot1, newRecvChainKey) = Companion.hkdfSplit(rootKey, dh1, crypto)
        receivingChain = KdfChain(newRecvChainKey, 0, crypto)

        // Save length before rotating
        previousSendingChainLength = sendingChain?.index ?: 0

        // 2. Generate new sending key pair
        sendingRatchetKey = crypto.generateX25519KeyPair()

        // 3. DH between our new sending key and their receiving key
        val dh2 = crypto.calculateDhAgreement(sendingRatchetKey.privateKey, receivingRatchetKey!!)
        val (newRoot2, newSendChainKey) = Companion.hkdfSplit(newRoot1, dh2, crypto)
        rootKey = newRoot2
        sendingChain = KdfChain(newSendChainKey, 0, crypto)
    }
}
```

---

### Step 5: Connecting to `SecureStorage`

The state of the Double Ratchet (Root Key, Chain Keys, Identity Keys, and Skipped Message Keys) **must be persisted** after every single encryption or decryption operation. If the app restarts and loses the current ratchet state, you will be permanently locked out of the conversation.

**1. Create Serializable State Models:**
Since you cannot serialize a class containing business logic directly into DataStore/Keychain, create a pure data transfer object representing the state.

```kotlin
// commonMain/crypto/SessionState.kt
import kotlinx.serialization.Serializable

@Serializable
data class DoubleRatchetState(
    val rootKeyBase64: String,
    val sendingChainKeyBase64: String?,
    val sendingChainIndex: Int,
    val receivingChainKeyBase64: String?,
    val receivingChainIndex: Int,
    val sendingRatchetPubKeyBase64: String,
    val sendingRatchetPrivKeyBase64: String,
    val receivingRatchetPubKeyBase64: String?,
    // Skipped keys mapping: RemoteRatchetPubKeyBase64 -> (MessageIndex -> MessageKeyBase64)
    val skippedMessageKeys: Map<String, Map<Int, String>>,
    val previousSendingChainLength: Int
)
```

**2. Session Store Interface:**
Create a `SessionStore` that bridges this state object to your existing `SecureStorage`.

```kotlin
// commonMain/crypto/SessionStore.kt
interface SessionStore {
    suspend fun loadSession(remoteUserId: String): DoubleRatchetSession?
    suspend fun saveSession(remoteUserId: String, session: DoubleRatchetSession)
}
```

**3. Important Persistence Rules & Edge Cases to Consider:**

* **Atomic Updates (`Mutex`):** Because location updates happen rapidly in the background, `encrypt` and `saveSession` must act atomically. Use a Kotlin `Mutex` in your repository so two concurrent network requests don't try to ratchet simultaneously and overwrite each other's state in `SecureStorage`.
* **State Mapping:** Write extension functions `DoubleRatchetSession.toState()` and `DoubleRatchetState.toSession(crypto)` to easily map between the active memory class and the JSON serializable model.
* **Transactionality (Rollbacks):** Only call `saveSession()` **after** a successful `decrypt()` or `encrypt()`. If `decryptAesGcm` fails (e.g., due to payload tampering or a bad MAC), it throws an exception. You MUST NOT save the mutated `DoubleRatchetSession` back to storage. If you do, your state will have advanced, but the message was rejected, permanently breaking the session.
* **Skipped Message Cleanup:** The `skippedMessageKeys` map can grow indefinitely if messages are permanently lost in transit. You should implement a pruning strategy (e.g., limit the map to max 100 entries, or clear keys older than 30 days) to prevent `SecureStorage` from bloating and slowing down.
* **App Reinstalls & Session Resets:** E2EE keys are stored strictly locally. If a user uninstalls FindMe and reinstalls, their `SecureStorage` is wiped. They will be unable to read pending messages or send messages to existing friends. Your UI and backend must handle **Session Resets** gracefully: if decryption fails continuously, the app should request a new PreKey bundle from the backend and start a fresh session, discarding the old corrupted one.

---

### Implementation Phases for You

When you begin implementing this, tackle it in this exact order:

1. **Phase 1: Crypto Wrapper:** Implement the `FindMeCrypto` interface using `cryptography-kotlin`
   and write `commonTest` unit tests to ensure AES encryption and DH derivations match expected
   results.
2. **Phase 2: X3DH:** Implement `initiateX3DH` and write a unit test where Alice and Bob generate
   keys and successfully derive the exact same `sharedSecret`.
3. **Phase 3: Chains:** Implement the Symmetric KDF Chain and write a test ensuring it produces
   identical key streams for consecutive calls.
4. **Phase 4: Double Ratchet:** Combine them into `DoubleRatchetSession`. Write a "Ping Pong" unit
   test where Alice and Bob send 10 messages back and forth, asserting decryption works and
   `performDhRatchet` is triggered correctly.
5. **Phase 5: Integration:** Wire `DoubleRatchetSession` into your `LocationRepository` to encrypt
   the location payload before sending to `POST /inbox`.
