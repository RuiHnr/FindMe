package com.ruirui.findme.crypto

import com.ruirui.findme.models.OneTimePreKeyDto
import com.ruirui.findme.models.SignedPreKeyDto
import com.ruirui.findme.models.UploadKeysRequest
import com.ruirui.findme.network.api.KeysApi
import com.ruirui.findme.storage.SecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

interface PreKeyManager {
    /**
     * Generates the initial Signed PreKey and a batch of One-Time PreKeys, then uploads them.
     * Should be called immediately after user registration and identity key generation.
     */
    suspend fun generateAndUploadInitialKeys()

    /**
     * Checks the server for the remaining OTPK count. If below the threshold,
     * generates and uploads a new batch to replenish the server pool.
     */
    suspend fun replenishOneTimePreKeysIfNeeded()

    /**
     * Rotates the Signed PreKey by generating a new one with an incremented ID,
     * uploading it to the backend, and securely persisting it locally.
     * The old Signed PreKey is intentionally kept in storage to decrypt delayed incoming messages.
     */
    suspend fun rotateSignedPreKey()
}

@OptIn(ExperimentalEncodingApi::class)
class PreKeyManagerImpl(
    private val crypto: Crypto,
    private val secureStorage: SecureStorage,
    private val keysApi: KeysApi
) : PreKeyManager {

    companion object {
        const val OTPK_BATCH_SIZE = 100
        const val OTPK_REPLENISH_THRESHOLD = 20
    }

    override suspend fun generateAndUploadInitialKeys() {
        // 1. Generate Signed PreKey
        val signedPreKeyId = 1
        secureStorage.putString(SecureStorageKeys.CURRENT_SIGNED_PREKEY_ID, signedPreKeyId.toString())
        val signedPreKeyPair = crypto.generateX25519KeyPair()

        secureStorage.putString(
            SecureStorageKeys.signedPreKeyPrivate(signedPreKeyId),
            Base64.encode(signedPreKeyPair.privateKey)
        )
        secureStorage.putString(
            SecureStorageKeys.signedPreKeyPublic(signedPreKeyId),
            Base64.encode(signedPreKeyPair.publicKey)
        )

        // Fetch Identity Sign Private Key to sign the Signed PreKey
        val identityPrivateSignBase64 = secureStorage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_SIGN)
            ?: throw Exception("Identity sign private key not found in storage")
        val identityPrivateSignBytes = Base64.decode(identityPrivateSignBase64)

        val signature = crypto.sign(identityPrivateSignBytes, signedPreKeyPair.publicKey)

        val signedPreKeyDto = SignedPreKeyDto(
            keyId = signedPreKeyId,
            publicKey = Base64.encode(signedPreKeyPair.publicKey),
            signature = Base64.encode(signature)
        )

        // 2. Generate One-Time PreKeys
        val oneTimePreKeys = generateOneTimePreKeys(OTPK_BATCH_SIZE)

        // 3. Upload to backend
        keysApi.upload(
            UploadKeysRequest(
                signedPreKey = signedPreKeyDto,
                oneTimePreKeys = oneTimePreKeys
            )
        ).getOrThrow()
    }

    override suspend fun replenishOneTimePreKeysIfNeeded() {
        val result = keysApi.getKeyCount()
        if (result.isFailure) return // Fail silently on background sync

        val count = result.getOrThrow().remainingOneTimePrekeys

        if (count < OTPK_REPLENISH_THRESHOLD) {
            val amountToGenerate = (OTPK_BATCH_SIZE - count).toInt()
            if (amountToGenerate <= 0) return

            val newKeys = generateOneTimePreKeys(amountToGenerate)

            keysApi.upload(
                UploadKeysRequest(
                    signedPreKey = null, // Do not rotate signed prekey yet
                    oneTimePreKeys = newKeys
                )
            ).getOrNull() // Ignore errors in background job
        }
    }

    private suspend fun generateOneTimePreKeys(amount: Int): List<OneTimePreKeyDto> {
        val dtos = mutableListOf<OneTimePreKeyDto>()

        for (i in 0 until amount) {
            // Cryptographically Random IDs to avoid sequential collisions
            // Ensure positive integers since some DBs or logic might assume ID > 0.
            val keyId = Random.nextInt(1, Int.MAX_VALUE)
            val keyPair = crypto.generateX25519KeyPair()

            // Persist the private key locally
            secureStorage.putString(
                SecureStorageKeys.oneTimePreKeyPrivate(keyId),
                Base64.encode(keyPair.privateKey)
            )

            dtos.add(
                OneTimePreKeyDto(
                    keyId = keyId,
                    publicKey = Base64.encode(keyPair.publicKey)
                )
            )
        }

        return dtos
    }

    override suspend fun rotateSignedPreKey() {
        val currentIdStr = secureStorage.getString(SecureStorageKeys.CURRENT_SIGNED_PREKEY_ID) ?: "0"
        val nextId = currentIdStr.toInt() + 1

        val signedPreKeyPair = crypto.generateX25519KeyPair()

        secureStorage.putString(
            SecureStorageKeys.signedPreKeyPrivate(nextId),
            Base64.encode(signedPreKeyPair.privateKey)
        )
        secureStorage.putString(
            SecureStorageKeys.signedPreKeyPublic(nextId),
            Base64.encode(signedPreKeyPair.publicKey)
        )

        val identityPrivateSignBase64 = secureStorage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_SIGN)
            ?: throw Exception("Identity sign private key not found in storage")
        val identityPrivateSignBytes = Base64.decode(identityPrivateSignBase64)

        val signature = crypto.sign(identityPrivateSignBytes, signedPreKeyPair.publicKey)

        val signedPreKeyDto = SignedPreKeyDto(
            keyId = nextId,
            publicKey = Base64.encode(signedPreKeyPair.publicKey),
            signature = Base64.encode(signature)
        )

        // Upload the new signed prekey to the backend
        val uploadResult = keysApi.upload(
            UploadKeysRequest(
                signedPreKey = signedPreKeyDto,
                oneTimePreKeys = null // We are only rotating the SPK, not OTPKs
            )
        )

        if (uploadResult.isSuccess) {
            secureStorage.putString(SecureStorageKeys.CURRENT_SIGNED_PREKEY_ID, nextId.toString())
        } else {
            // Upload failed, remove the newly generated keys to prevent orphaned keys in storage
            secureStorage.remove(SecureStorageKeys.signedPreKeyPrivate(nextId))
            secureStorage.remove(SecureStorageKeys.signedPreKeyPublic(nextId))
            println("Failed to rotate Signed PreKey.")
        }
    }
}
