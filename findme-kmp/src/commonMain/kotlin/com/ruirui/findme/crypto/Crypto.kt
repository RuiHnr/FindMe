package com.ruirui.findme.crypto

interface Crypto {
    /**
     * HKDF (HMAC-based Extract-and-Expand Key Derivation Function) using SHA-256.
     * Used in X3DH to derive the Master Secret, and in the Double Ratchet to derive Root/Chain keys.
     *
     * @param ikm Initial Keying Material (e.g., Diffie-Hellman shared secret).
     * @param salt Optional salt value (can be zeros or the previous Root Key).
     * @param info Application-specific context string (e.g., "FindMe-X3DH").
     * @param outLength The desired length of the derived key output in bytes.
     */
    suspend fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLength: Int): ByteArray

    /**
     * Computes an HMAC (Hash-based Message Authentication Code) using SHA-256.
     * Used in the Double Ratchet symmetric KDF chain to generate Message Keys.
     */
    suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /**
     * Encrypts plaintext using AES-256-GCM. 
     * Auto-generates a secure 12-byte IV, returning it prepended to the ciphertext.
     *
     * @param key The 32-byte Message Key.
     * @param plaintext The payload to encrypt.
     * @param ad Associated Data (e.g., Message Header) to authenticate without encrypting.
     */
    suspend fun encryptAesGcm(
        key: ByteArray,
        plaintext: ByteArray,
        ad: ByteArray?
    ): ByteArray

    /**
     * Decrypts AES-256-GCM ciphertext (expecting the 12-byte IV prepended to it).
     * Fails if the ciphertext or Associated Data (AD) was tampered with.
     */
    suspend fun decryptAesGcm(
        key: ByteArray,
        ciphertext: ByteArray,
        ad: ByteArray?
    ): ByteArray

    /**
     * Generates an X25519 (Elliptic Curve Diffie-Hellman) key pair.
     * Used exclusively for key agreement (e.g., X3DH and Ratchet steps).
     */
    suspend fun generateX25519KeyPair(): KeyPair
    
    /**
     * Computes the Diffie-Hellman shared secret from a local X25519 private key and a remote X25519 public key.
     */
    suspend fun calculateDhAgreement(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    /**
     * Generates an Ed25519 (Digital Signatures) key pair.
     * Used exclusively for authentication (e.g., signing prekeys during registration).
     */
    suspend fun generateEd25519KeyPair(): KeyPair
    
    /**
     * Signs a message using an Ed25519 private key.
     */
    suspend fun sign(privateKey: ByteArray, message: ByteArray): ByteArray
    
    /**
     * Verifies an Ed25519 signature against a message and a public key.
     */
    suspend fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)
