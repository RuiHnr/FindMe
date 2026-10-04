package com.ruirui.findme.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH

/**
 * A concrete implementation of the [Crypto] interface that delegates cryptographic operations
 * to the `cryptography-kotlin` multiplatform library.
 *
 * This adapter guarantees that underlying cryptographic primitives are backed by the host OS's 
 * secure hardware or native libraries (e.g., Apple CryptoKit on iOS, JCA/BouncyCastle on Android)
 * via the `CryptographyProvider.Default` resolution.
 *
 * It is responsible for bridging between our clean Double Ratchet mathematical interface 
 * and the slightly more verbose object-oriented API of the underlying library.
 *
 * @property provider The multiplatform cryptographic provider, resolving to native engines.
 */
class CryptoKotlinAdapter(private val provider: CryptographyProvider = CryptographyProvider.Default) :
    Crypto {
    override suspend fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outLength: Int
    ): ByteArray {
        return provider.get(HKDF)
            .secretDerivation(
                digest = SHA256,
                outputSize = (outLength * 8).bits,
                salt = salt,
                info = info
            )
            .deriveSecretToByteArray(ikm)
    }

    override suspend fun hmacSha256(
        key: ByteArray,
        data: ByteArray
    ): ByteArray {
        return provider.get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignature(data)
    }

    override suspend fun encryptAesGcm(
        key: ByteArray,
        plaintext: ByteArray,
        ad: ByteArray?
    ): ByteArray {
        return provider.get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArray(AES.Key.Format.RAW, key)
            .cipher()
            .encrypt(plaintext, ad)
    }

    override suspend fun decryptAesGcm(
        key: ByteArray,
        ciphertext: ByteArray,
        ad: ByteArray?
    ): ByteArray {
        return provider.get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArray(AES.Key.Format.RAW, key)
            .cipher()
            .decrypt(ciphertext, ad)
    }

    override suspend fun generateX25519KeyPair(): KeyPair {
        val keyPair = provider.get(XDH)
            .keyPairGenerator(XDH.Curve.X25519)
            .generateKey()
        return KeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW),
            privateKey = keyPair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.RAW)
        )
    }

    override suspend fun calculateDhAgreement(
        privateKey: ByteArray,
        publicKey: ByteArray
    ): ByteArray {
        val xdh = provider.get(XDH)

        val privateKeyObj = xdh.privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArray(XDH.PrivateKey.Format.RAW, privateKey)

        val publicKeyObj = xdh.publicKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArray(XDH.PublicKey.Format.RAW, publicKey)

        return privateKeyObj.sharedSecretGenerator().generateSharedSecretToByteArray(publicKeyObj)
    }

    override suspend fun generateEd25519KeyPair(): KeyPair {
        val keyPair = provider.get(EdDSA)
            .keyPairGenerator(EdDSA.Curve.Ed25519)
            .generateKey()
        return KeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW),
            privateKey = keyPair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.RAW)
        )
    }

    override suspend fun sign(
        privateKey: ByteArray,
        message: ByteArray
    ): ByteArray {
        return provider.get(EdDSA)
            .privateKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, privateKey)
            .signatureGenerator()
            .generateSignature(message)
    }

    override suspend fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean {
        return provider.get(EdDSA)
            .publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, publicKey)
            .signatureVerifier()
            .tryVerifySignature(message, signature)
    }
}