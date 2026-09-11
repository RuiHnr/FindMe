package com.ruirui.findme.crypto

/**
 * Performs the X3DH (Extended Triple Diffie-Hellman) key agreement handshake as the Initiator (Alice).
 * Mixes multiple X25519 keys to establish a mutually authenticated, forward-secret master key.
 * 
 * Note: ALL keys provided to this function must be X25519 keys.
 *
 * @param crypto The cryptographic primitive provider.
 * @param aliceIdentityKey Alice's long-term identity key pair (X25519). Authenticates Alice to Bob.
 * @param aliceBaseKey Alice's ephemeral base key pair (X25519) generated for this specific session. Provides forward secrecy.
 * @param bobIdentityKey Bob's long-term identity public key (X25519) fetched from the server. Authenticates Bob to Alice.
 * @param bobSignedPreKey Bob's medium-term signed prekey public key (X25519). Provides fallback forward secrecy.
 * @param bobOneTimePreKey Bob's single-use prekey public key (X25519). Provides immediate perfect forward secrecy if available.
 * @return A 32-byte master secret derived via HKDF-SHA256, used to initialize the Double Ratchet.
 */
suspend fun initX3DH(
    crypto: Crypto,
    aliceIdentityKey: KeyPair,
    aliceBaseKey: KeyPair,
    bobIdentityKey: ByteArray,
    bobSignedPreKey: ByteArray,
    bobOneTimePreKey: ByteArray?
): ByteArray {
    // 1. Calculate DH Outputs
    val dh1 = crypto.calculateDhAgreement(aliceIdentityKey.privateKey, bobSignedPreKey)
    val dh2 = crypto.calculateDhAgreement(aliceBaseKey.privateKey, bobIdentityKey)
    val dh3 = crypto.calculateDhAgreement(aliceBaseKey.privateKey, bobSignedPreKey)

    var sharedSecret = dh1 + dh2 + dh3

    // if a one-time prekey was available, add a 4th DH
    if (bobOneTimePreKey != null) {
        val dh4 = crypto.calculateDhAgreement(aliceBaseKey.privateKey, bobOneTimePreKey)
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