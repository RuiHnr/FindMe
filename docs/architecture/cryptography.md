# FindMe Cryptography & E2EE Architecture

FindMe implements a robust End-to-End Encryption (E2EE) protocol inspired heavily by the [Signal Protocol](https://signal.org/docs/). The backend acts entirely as a "dumb" encrypted payload relay and key server. It has zero knowledge of the actual location data, ensuring total privacy.

This document outlines the cryptographic primitives, the identity key structures, and the session lifecycle (X3DH and Double Ratchet).

---

## 1. Cryptographic Primitives

The multiplatform cryptography is backed by the `dev.whyoleg.cryptography` library.

*   **Key Agreement (Diffie-Hellman):** `X25519`
*   **Digital Signatures:** `Ed25519`
*   **Symmetric Encryption:** `AES-256-GCM`
*   **Key Derivation (KDF):** `HKDF-SHA256`
*   **Message Authentication:** Handled natively by the GCM authentication tag.

---

## 2. Key Hierarchy & Persistence

During registration (`AuthRepository.register`), the application generates several cryptographic key pairs. These are persisted locally on the device using OS-level secure storage (e.g., EncryptedSharedPreferences on Android, Keychain on iOS).

1.  **Identity Key Pair (DH):** An `X25519` key pair used for Diffie-Hellman key exchanges. This represents the user's permanent identity.
2.  **Identity Key Pair (Sign):** An `Ed25519` key pair used exclusively for signing the `SignedPreKey` to prevent tampering/man-in-the-middle attacks.
3.  **Signed PreKey:** An `X25519` key pair rotated periodically (currently just once on registration). The public key is signed using the `Identity Key Pair (Sign)` and uploaded to the backend.
4.  **One-Time PreKeys:** A batch of single-use `X25519` key pairs uploaded to the server to provide immediate Perfect Forward Secrecy (PFS). When a friend starts a session, they consume one of these keys.

---

## 3. Session Initialization: X3DH (Extended Triple Diffie-Hellman)

FindMe uses a lazy initialization strategy. When Alice accepts Bob's friend request, no cryptographic session is established. The session is only created when Alice actually attempts to send her first location update to Bob (`LocationRepository.submitLocalLocation`).

### The Handshake Steps

1.  **Fetch PreKeys:** Alice asks the backend for Bob's Key Bundle (Identity DH Key, Identity Sign Key, Signed PreKey, and optionally a One-Time PreKey).
2.  **Verify Signature:** Alice verifies that Bob's `SignedPreKey` is authentically signed by Bob's `Ed25519` Identity Key.
3.  **Generate Ephemeral Key:** Alice generates a throwaway `X25519` base key just for this handshake.
4.  **Diffie-Hellman Math:** Alice calculates 3 (or 4) shared secrets by mixing her private keys with Bob's public keys.
    *   `DH1 = AliceIdentityPrivate + BobSignedPreKeyPublic`
    *   `DH2 = AliceBasePrivate + BobIdentityPublic`
    *   `DH3 = AliceBasePrivate + BobSignedPreKeyPublic`
    *   `DH4 = AliceBasePrivate + BobOneTimePreKeyPublic` (If available)
5.  **HKDF:** These secrets are concatenated and pushed through `HKDF-SHA256` to derive the 32-byte **Master Secret**.
6.  **First Message:** Alice encrypts her location using the Double Ratchet (seeded by the Master Secret) and sends the `PreKeySignalEnvelope`. This envelope includes a `MessageHeader` containing Alice's public keys.

When Bob receives this `PreKeySignalEnvelope` via `LocationRepository.syncInbox()`, he extracts Alice's public keys and performs the exact inverse math using his private keys, deriving the exact same Master Secret, successfully initializing his side of the session.

---

## 4. The Double Ratchet Algorithm

Once the X3DH Master Secret is established, the session transitions to the Double Ratchet algorithm.

### Why Double Ratchet?
It provides two critical security properties:
*   **Perfect Forward Secrecy (PFS):** If a device is compromised today, the attacker cannot decrypt messages sent yesterday.
*   **Future Secrecy (Post-Compromise Security):** If a device is temporarily compromised and the attacker steals the current keys, the system will eventually "heal" itself. As soon as the user sends a new message, the keys ratcheted forward become unguessable to the attacker.

### The Mechanics

*   **KDF Chains:** Each message sent or received advances a Key Derivation Function (KDF) chain. A KDF takes an existing key, hashes it, and spits out two new keys: one to encrypt the current message, and one to act as the root for the next hash.
*   **Symmetric Ratchet:** Every single message advances the sending/receiving chain.
*   **Diffie-Hellman Ratchet:** Whenever a user replies, they generate a new ephemeral `X25519` key pair and attach the public key to the message header. This mixes new entropy into the root chain, providing Future Secrecy.

All session state (the current KDF keys, message counts, and active DH keys) is serialized and persisted via `SessionStore`.

---

## 5. Message Envelopes

The backend stores and forwards raw strings. The client handles polymorphic deserialization of `SignalMessageEnvelope`:

*   **`PreKeySignalEnvelope`:** Used only for the very first message. Contains the X3DH headers (Alice's Identity Key, Alice's Ephemeral Base Key, and IDs indicating which of Bob's prekeys she consumed).
*   **`NormalSignalEnvelope`:** Used for all subsequent messages once the session is active. It contains only the Double Ratchet headers (current DH ratchet key, message numbers) and the `AES-256-GCM` ciphertext.
