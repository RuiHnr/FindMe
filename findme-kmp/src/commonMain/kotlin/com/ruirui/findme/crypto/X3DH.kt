package com.ruirui.findme.crypto

/**
 * Performs the X3DH (Extended Triple Diffie-Hellman) key agreement handshake as the Initiator (Alice).
 * Mixes multiple X25519 keys to establish a mutually authenticated, forward-secret master key.
 * 
 * Note: ALL keys provided to this function must be X25519 keys.
 *
 * @param crypto The cryptographic primitive provider.
 * @param aliceIdentityPrivateKey Alice's long-term identity private dh key pair (X25519). Authenticates Alice to Bob.
 * @param aliceBasePrivateKey Alice's ephemeral base key pair (X25519) generated for this specific session. Provides forward secrecy.
 * @param bobIdentityPublicKey Bob's long-term identity public key (X25519) fetched from the server. Authenticates Bob to Alice.
 * @param bobSignedPreKeyPublic Bob's medium-term signed prekey public key (X25519). Provides fallback forward secrecy.
 * @param bobOneTimePreKeyPublic Bob's single-use prekey public key (X25519). Provides immediate perfect forward secrecy if available.
 * @return A 32-byte master secret derived via HKDF-SHA256, used to initialize the Double Ratchet.
 */
suspend fun initX3DH(
    crypto: Crypto,
    aliceIdentityPrivateKey: ByteArray,
    aliceBasePrivateKey: ByteArray,
    bobIdentityPublicKey: ByteArray,
    bobSignedPreKeyPublic: ByteArray,
    bobOneTimePreKeyPublic: ByteArray?
): ByteArray {
    // 1. Calculate DH Outputs
    val dh1 = crypto.calculateDhAgreement(aliceIdentityPrivateKey, bobSignedPreKeyPublic)
    val dh2 = crypto.calculateDhAgreement(aliceBasePrivateKey, bobIdentityPublicKey)
    val dh3 = crypto.calculateDhAgreement(aliceBasePrivateKey, bobSignedPreKeyPublic)

    var sharedSecret = dh1 + dh2 + dh3

    // if a one-time prekey was available, add a 4th DH
    if (bobOneTimePreKeyPublic != null) {
        val dh4 = crypto.calculateDhAgreement(aliceBasePrivateKey, bobOneTimePreKeyPublic)
        sharedSecret += dh4
    }

    // 2. Run through HKDF to generate the Master Secret
    return crypto.hkdf(
        ikm = sharedSecret,
        salt = ByteArray(32),
        info = "FindMe-X3DH".encodeToByteArray(),
        outLength = 32
    )
}


/**
 * Performs the X3DH (Extended Triple Diffie-Hellman) key agreement handshake as the Receiver (Bob).
 * This complements [initX3DH] to establish the same shared master secret from Bob's side, 
 * using the ephemeral keys Alice transmitted in her [com.ruirui.findme.models.MessageHeader].
 *
 * @param crypto The cryptographic primitive provider.
 * @param bobIdentityPrivateKey Bob's long-term identity private dh key (X25519).
 * @param bobSignedPreKeyPrivate Bob's medium-term signed prekey private key (X25519) that Alice targeted.
 * @param bobOneTimePreKeyPrivate Bob's one-time prekey private key (X25519) that Alice consumed (if any).
 * @param aliceIdentityPublicKey Alice's long-term identity public key (X25519) extracted from the message header.
 * @param aliceBasePublicKey Alice's ephemeral base public key (X25519) extracted from the message header.
 * @return A 32-byte master secret derived via HKDF-SHA256, matching Alice's output.
 */
suspend fun receiveX3DH(
    crypto: Crypto,
    bobIdentityPrivateKey: ByteArray,
    bobSignedPreKeyPrivate: ByteArray,
    bobOneTimePreKeyPrivate: ByteArray?,
    aliceIdentityPublicKey: ByteArray,
    aliceBasePublicKey: ByteArray
): ByteArray {
    // Bob does the exact same 4 DH calculations, but inverted
    val dh1 = crypto.calculateDhAgreement(bobSignedPreKeyPrivate, aliceIdentityPublicKey)
    val dh2 = crypto.calculateDhAgreement(bobIdentityPrivateKey, aliceBasePublicKey)
    val dh3 = crypto.calculateDhAgreement(bobSignedPreKeyPrivate, aliceBasePublicKey)

    var sharedSecret = dh1 + dh2 + dh3

    if (bobOneTimePreKeyPrivate != null) {
        val dh4 = crypto.calculateDhAgreement(bobOneTimePreKeyPrivate, aliceBasePublicKey)
        sharedSecret += dh4
    }
    // Run through HKDF to get the exact same Master Secret Alice got
    return crypto.hkdf(
        ikm = sharedSecret,
        salt = ByteArray(32),
        info = "FindMe-X3DH".encodeToByteArray(),
        outLength = 32
    )
}