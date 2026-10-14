# Orbit KMP: TOFU Identity Pinning & Safety Number Guide

A comprehensive, step-by-step implementation guide for **Trust-On-First-Use (TOFU) Identity Pinning
** and **Safety Number Verification** in the Kotlin Multiplatform (`kmp`) core module.

---

## 1. Threat Model & Overview

### 1.1 What is TOFU?

In an End-to-End Encrypted (E2EE) messaging system, the server is treated as an **untrusted relay**.
While the server cannot decrypt payloads protected by the Signal Protocol (X3DH + Double Ratchet), a
compromised or malicious server could mount an active **Man-In-The-Middle (MITM)** attack by
swapping public keys during key bundle retrieval.

**Trust-On-First-Use (TOFU)** guarantees that:

1. When you communicate with a friend for the first time, their permanent Identity Public Key is
   recorded ("pinned") in your local database.
2. In all subsequent handshakes and messages, any change in that friend's Identity Public Key is
   flagged as a potential MITM attack or a device reset.
3. Out-of-band verification (Safety Numbers / QR code scanning) allows users to cryptographically
   verify each other's identities independently of the server.

```
       [ Malicious Server ]
        /                \
 Swaps Bob's PreKey       Tries to impersonate Bob
 Bundle with Eve's Key    to Alice
      /                    \
   [ Alice ] ==========> [ Bob ]
    Local DB: "Bob's pinned key = PK_Bob"
    Incoming: "Bob's key = PK_Eve"
    🚨 MISMATCH DETECTED -> Handshake Aborted!
```

### 1.2 Two Threat Scenarios

| Scenario                                | What Happens                                                                                           | Security Consequence                                                                                                                   |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **Server MITM Attack**                  | Malicious server generates its own keypair, returns fake PreKey bundle to Alice, and proxies messages. | Without TOFU, Alice encrypts with attacker's key. With TOFU, Alice refuses the handshake because the key doesn't match the pinned key. |
| **Legitimate Device Reset / Reinstall** | Friend reinstalled Orbit or got a new phone, generating fresh Identity Keys.                          | TOFU detects the key change and alerts the user. Communication is held until the user acknowledges/verifies the new identity.          |

---

## 2. Current State vs. Target State in `kmp`

### 2.1 Current Gaps

Inspect `LocationRepository.kt`:

1. **Receiver Side (Bob):** When receiving a `PreKeySignalEnvelope` from Alice, Bob checks
   `db.identityPinQueries.getPin(friendId)`. If mismatched, it throws a generic `Exception`.
2. **Sender Side (Alice) [VULNERABILITY]:** When Alice initiates X3DH with Bob in
   `encryptForFriend()`, she fetches Bob's `PreKeyBundle` from `KeysApi.getPreKeyBundle(friendId)`.
   **She does not check `identity_pin` at all!** A rogue server can swap Bob's bundle, and Alice
   will silently complete the handshake with the rogue key.
3. **Missing Sign Key Pinning:** Only the Diffie-Hellman identity key (`identityKeyDh`) is stored.
   The Ed25519 signing identity key (`identityKeySign`) should also be pinned or hashed together.
4. **No Typed Exceptions:** Errors throw generic `Exception("Identity key mismatch...")`, preventing
   the UI or background workers from distinguishing security alerts from network dropouts.
5. **No Safety Number Computation:** No mechanism exists for friends to compare safety numbers (
   fingerprints) in the UI.
6. **No Re-Pinning / Trust Recovery Flow:** If Bob legitimately reinstalled the app, Alice has no
   API to accept the new identity key.

---

## 3. Database Schema (`IdentityPin.sq`)

Expand `kmp/src/commonMain/sqldelight/com/ruirui/orbit/db/IdentityPin.sq` to track both DH
and Signing identity keys, along with trust metadata:

```sql
CREATE TABLE identity_pin (
    user_id TEXT NOT NULL PRIMARY KEY,
    identity_key_dh TEXT NOT NULL,
    identity_key_sign TEXT NOT NULL,
    first_seen_at INTEGER NOT NULL,
    last_verified_at INTEGER NOT NULL,
    is_trusted INTEGER NOT NULL DEFAULT 1 -- 1 = active/trusted, 0 = flagged mismatch pending user approval
);

-- Queries

getPin:
SELECT * FROM identity_pin WHERE user_id = ?;

getAllPins:
SELECT * FROM identity_pin ORDER BY user_id ASC;

upsertPin:
INSERT OR REPLACE INTO identity_pin (
    user_id,
    identity_key_dh,
    identity_key_sign,
    first_seen_at,
    last_verified_at,
    is_trusted
) VALUES (?, ?, ?, ?, ?, ?);

updateTrustStatus:
UPDATE identity_pin
SET is_trusted = ?, last_verified_at = ?
WHERE user_id = ?;

deletePin:
DELETE FROM identity_pin WHERE user_id = ?;
```

---

## 4. Domain Models & Typed Exceptions

Create `kmp/src/commonMain/kotlin/com/ruirui/orbit/crypto/IdentityPinning.kt`:

```kotlin
package orbit.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Thrown when an incoming or fetched Identity Key differs from the pinned key in local storage.
 * Indicates a potential Man-In-The-Middle (MITM) attack or a friend device reinstall.
 */
class IdentityKeyMismatchException(
    val friendUserId: String,
    val keyType: String, // "DH" or "SIGN"
    val pinnedKeyBase64: String,
    val receivedKeyBase64: String
) : SecurityException(
    "Identity key mismatch for $friendUserId ($keyType)! " +
            "Pinned: $pinnedKeyBase64, Received: $receivedKeyBase64"
)

/**
 * Represents the identity verification state of a friend for UI consumption.
 */
data class IdentityPinInfo(
    val userId: String,
    val identityKeyDhBase64: String,
    val identityKeySignBase64: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
    val isTrusted: Boolean
)
```

---

## 5. Safety Number (Fingerprint) Generation

Signal computes a **Safety Number** by sorting the two users' IDs and public keys, hashing them with
SHA-512, and formatting the output into a human-readable string (or a QR code).

Add `SafetyNumberHelper.kt` in `kmp/src/commonMain/kotlin/com/ruirui/orbit/crypto/`:

```kotlin
package orbit.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.digest.SHA512
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object SafetyNumberHelper {

    /**
     * Generates a 60-digit human-readable Safety Number between two users.
     * Formatted in 12 groups of 5 digits: "12345 67890 ..."
     *
     * Symmetric: produces the EXACT same string regardless of whether Alice or Bob computes it.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun computeSafetyNumber(
        userAId: String,
        userAPubKeyDh: String,
        userBId: String,
        userBPubKeyDh: String
    ): String {
        // 1. Sort users lexicographically so calculation is order-independent
        val (firstId, firstKey, secondId, secondKey) = if (userAId < userBId) {
            listOf(userAId, userAPubKeyDh, userBId, userBPubKeyDh)
        } else {
            listOf(userBId, userBPubKeyDh, userAId, userAPubKeyDh)
        }

        // 2. Prepare payload to hash
        val payload = "$firstId:$firstKey|$secondId:$secondKey".encodeToByteArray()

        // 3. Digest using SHA-512 via cryptography provider
        val sha512 = CryptographyProvider.Default.get(SHA512)
        val hasher = sha512.hasher()
        val hash = hasher.hash(payload)

        // 4. Convert first 30 bytes into 12 five-digit blocks (60 digits total)
        val stringBuilder = StringBuilder()
        for (i in 0 until 12) {
            val offset = i * 2
            val chunk =
                ((hash[offset].toInt() and 0xFF) shl 8) or (hash[offset + 1].toInt() and 0xFF)
            val fiveDigits = (chunk % 100000).toString().padStart(5, '0')
            if (i > 0) stringBuilder.append(' ')
            stringBuilder.append(fiveDigits)
        }
        return stringBuilder.toString()
    }
}
```

---

## 6. Implementation in `LocationRepository`

### 6.1 Sender Side: Alice verifies Bob before X3DH

In `kmp/src/commonMain/kotlin/com/ruirui/orbit/repository/LocationRepository.kt`, update
`encryptForFriend()`:

```kotlin
private suspend fun encryptForFriend(friendId: String, payload: String): SignalMessageEnvelope {
    var session = sessionStore.loadSession(friendId)

    if (session == null) {
        // Lazy initialization: fetch friend's PreKey Bundle
        val preKeyBundle = keysApi.getPreKeyBundle(friendId).getOrThrow()

        // 1. TOFU CHECK: Verify Bob's Identity Keys against local pin
        val existingPin = db.identityPinQueries.getPin(friendId).executeAsOneOrNull()
        if (existingPin != null) {
            if (existingPin.identity_key_dh != preKeyBundle.identityKeyDh) {
                throw IdentityKeyMismatchException(
                    friendUserId = friendId,
                    keyType = "DH",
                    pinnedKeyBase64 = existingPin.identity_key_dh,
                    receivedKeyBase64 = preKeyBundle.identityKeyDh
                )
            }
            if (existingPin.identity_key_sign != preKeyBundle.identityKeySign) {
                throw IdentityKeyMismatchException(
                    friendUserId = friendId,
                    keyType = "SIGN",
                    pinnedKeyBase64 = existingPin.identity_key_sign,
                    receivedKeyBase64 = preKeyBundle.identityKeySign
                )
            }
            // Update last verified timestamp
            db.identityPinQueries.upsertPin(
                user_id = friendId,
                identity_key_dh = existingPin.identity_key_dh,
                identity_key_sign = existingPin.identity_key_sign,
                first_seen_at = existingPin.first_seen_at,
                last_verified_at = getTimeMillis(),
                is_trusted = 1L
            )
        } else {
            // First contact: Trust-On-First-Use Pinning
            db.identityPinQueries.upsertPin(
                user_id = friendId,
                identity_key_dh = preKeyBundle.identityKeyDh,
                identity_key_sign = preKeyBundle.identityKeySign,
                first_seen_at = getTimeMillis(),
                last_verified_at = getTimeMillis(),
                is_trusted = 1L
            )
        }

        // 2. Verify SPK signature using Bob's (now pinned & validated) Identity Sign Key
        if (!crypto.verify(
                Base64.decode(preKeyBundle.identityKeySign),
                Base64.decode(preKeyBundle.signedPreKey.publicKey),
                Base64.decode(preKeyBundle.signedPreKey.signature)
            )
        ) {
            throw Exception("Invalid SPK signature from $friendId")
        }

        // 3. Complete X3DH & initialize Double Ratchet
        // ... (proceed with initX3DH and DoubleRatchetSession.initAlice)
```

### 6.2 Receiver Side: Bob verifies Alice upon receiving `PreKeySignalEnvelope`

In `decryptFromFriend()`:

```kotlin
is PreKeySignalEnvelope -> {
    val header = envelope.header

    // 1. TOFU CHECK: Verify Alice's DH Identity Key
    val existingPin = db.identityPinQueries.getPin(friendId).executeAsOneOrNull()
    if (existingPin != null && existingPin.identity_key_dh != header.aliceIdentityKeyDh) {
        throw IdentityKeyMismatchException(
            friendUserId = friendId,
            keyType = "DH",
            pinnedKeyBase64 = existingPin.identity_key_dh,
            receivedKeyBase64 = header.aliceIdentityKeyDh
        )
    }

    // Save or refresh pin
    db.identityPinQueries.upsertPin(
        user_id = friendId,
        identity_key_dh = header.aliceIdentityKeyDh,
        identity_key_sign = existingPin?.identity_key_sign ?: "", // updated during reciprocal exchange or friends sync
        first_seen_at = existingPin?.first_seen_at ?: getTimeMillis(),
        last_verified_at = getTimeMillis(),
        is_trusted = 1L
    )

    // ... (proceed with receiveX3DH and DoubleRatchetSession.initBob)
```

---

## 7. Recovery & UI Verification API

Add the following methods to `LocationRepository` (or a dedicated `SecurityRepository`):

```kotlin
interface LocationRepository {
    // ... existing methods ...

    /**
     * Retrieves the stored identity pin details for a given friend.
     */
    suspend fun getIdentityPin(friendId: String): IdentityPinInfo?

    /**
     * Computes the human-readable Safety Number between the local user and a friend.
     */
    suspend fun getSafetyNumber(friendId: String): Result<String>

    /**
     * Manually accepts a new Identity Key for a friend after out-of-band verification
     * (e.g. following a legitimate device reset). Clears any old session and establishes a new pin.
     */
    suspend fun trustNewIdentityKey(
        friendId: String,
        newIdentityKeyDh: String,
        newIdentityKeySign: String
    ): Result<Unit>
}
```

Implementation in `LocationRepositoryImpl`:

```kotlin
override suspend fun getIdentityPin(friendId: String): IdentityPinInfo? {
    val row = db.identityPinQueries.getPin(friendId).executeAsOneOrNull() ?: return null
    return IdentityPinInfo(
        userId = row.user_id,
        identityKeyDhBase64 = row.identity_key_dh,
        identityKeySignBase64 = row.identity_key_sign,
        firstSeenAt = row.first_seen_at,
        lastVerifiedAt = row.last_verified_at,
        isTrusted = row.is_trusted == 1L
    )
}

override suspend fun getSafetyNumber(friendId: String): Result<String> = runCatching {
    val myId = secureStorage.getString(SecureStorageKeys.USER_ID)
        ?: error("User ID not found")
    val myPubKeyDh = secureStorage.getString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH)
        ?: error("Identity DH public key not found")

    val friendPin = db.identityPinQueries.getPin(friendId).executeAsOneOrNull()
        ?: error("No pinned identity found for friend $friendId. Message them first.")

    SafetyNumberHelper.computeSafetyNumber(
        userAId = myId,
        userAPubKeyDh = myPubKeyDh,
        userBId = friendId,
        userBPubKeyDh = friendPin.identity_key_dh
    )
}

override suspend fun trustNewIdentityKey(
    friendId: String,
    newIdentityKeyDh: String,
    newIdentityKeySign: String
): Result<Unit> = runCatching {
    // 1. Overwrite pin with new verified key
    db.identityPinQueries.upsertPin(
        user_id = friendId,
        identity_key_dh = newIdentityKeyDh,
        identity_key_sign = newIdentityKeySign,
        first_seen_at = getTimeMillis(),
        last_verified_at = getTimeMillis(),
        is_trusted = 1L
    )

    // 2. Clear old session ratchet state to force fresh X3DH on next send
    sessionStore.deleteSession(friendId)
}
```

---

## 8. Unit Testing TOFU & MITM Scenarios

Add tests in `kmp/src/commonTest/kotlin/com/ruirui/orbit/crypto/IdentityPinningTest.kt`:

### 8.1 Test 1: Sender detects swapped key on second handshake

```kotlin
@Test
fun testSenderDetectsMitmKeySwap() = runTest {
    val db = createTestDatabase()
    val friendId = "bob_uuid"

    // 1. Pre-seed DB with Bob's legitimate pin
    db.identityPinQueries.upsertPin(
        user_id = friendId,
        identity_key_dh = "LEGI_KEY_DH_BASE64",
        identity_key_sign = "LEGI_KEY_SIGN_BASE64",
        first_seen_at = 1000L,
        last_verified_at = 1000L,
        is_trusted = 1L
    )

    // 2. Mock server returns an attacker-swapped bundle
    val fakeKeysApi = FakeKeysApiBackend().apply {
        // Return swapped identityKeyDh = "ATTACKER_KEY_DH"
    }

    // 3. Attempting to send location to Bob must fail with IdentityKeyMismatchException
    val result = runCatching {
        locationRepo.submitLocalLocation(10.0, 20.0)
    }

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is IdentityKeyMismatchException)
}
```

### 8.2 Test 2: Receiver detects spoofed sender key

```kotlin
@Test
fun testReceiverDetectsMitmPreKeyEnvelope() = runTest {
    val db = createTestDatabase()
    val friendId = "alice_uuid"

    // Pre-pin Alice's genuine key
    db.identityPinQueries.upsertPin(
        user_id = friendId,
        identity_key_dh = "ALICE_GENUINE_KEY",
        identity_key_sign = "ALICE_SIGN_KEY",
        first_seen_at = 1000L,
        last_verified_at = 1000L,
        is_trusted = 1L
    )

    // Fake an inbox message with a spoofed aliceIdentityKeyDh
    val spoofedEnvelope = PreKeySignalEnvelope(
        header = MessageHeader(
            aliceIdentityKeyDh = "ATTACKER_SPOOFED_KEY",
            aliceBaseKeyDh = "BASE_KEY",
            bobSignedPreKeyId = 1,
            bobOneTimePreKeyId = null
        ),
        ciphertext = EncryptedMessage(...)
    )

    val exception = assertFailsWith<IdentityKeyMismatchException> {
        locationRepo.decryptFromFriend(friendId, spoofedEnvelope)
    }
    assertEquals(friendId, exception.friendUserId)
    assertEquals("ALICE_GENUINE_KEY", exception.pinnedKeyBase64)
}
```

### 8.3 Test 3: Safety Numbers match on both sides

```kotlin
@Test
fun testSafetyNumbersSymmetric() = runTest {
    val aliceId = "alice_uuid"
    val aliceKey = "AlicePublicKeyBase64_XYZ123"

    val bobId = "bob_uuid"
    val bobKey = "BobPublicKeyBase64_ABC789"

    // Alice computes safety number
    val aliceView = SafetyNumberHelper.computeSafetyNumber(aliceId, aliceKey, bobId, bobKey)

    // Bob computes safety number (arguments reversed)
    val bobView = SafetyNumberHelper.computeSafetyNumber(bobId, bobKey, aliceId, aliceKey)

    assertEquals(aliceView, bobView, "Safety numbers must be identical regardless of caller")
    assertEquals(12, aliceView.split(" ").size, "Safety number must consist of 12 digit blocks")
}
```

---

## 9. Verification & Acceptance Checklist

- [ ] `IdentityPin.sq` updated with `identity_key_sign` and `is_trusted` fields.
- [ ] `./gradlew :kmp:generateCommonMainOrbitDatabaseInterface` passes without SQL
  compilation errors.
- [ ] Sender side (`LocationRepository.encryptForFriend`) checks existing pin before running
  `initX3DH`.
- [ ] Receiver side (`LocationRepository.decryptFromFriend`) checks existing pin and throws typed
  `IdentityKeyMismatchException`.
- [ ] `SafetyNumberHelper` computes deterministic 60-digit safety numbers.
- [ ] `trustNewIdentityKey()` allows recovery after a verified device reset by clearing the stale
  `DoubleRatchet` session and updating the pin.
- [ ] All tests run and pass under `./gradlew :kmp:testAndroidHostTest`.
