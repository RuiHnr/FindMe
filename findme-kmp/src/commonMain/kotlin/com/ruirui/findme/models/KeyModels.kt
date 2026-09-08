package com.ruirui.findme.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a medium-term Signed PreKey in the Signal Protocol (X3DH).
 *
 * @param keyId Unique integer identifier for this signed prekey.
 * @param publicKey The public key string (Base64).
 * @param signature Signature of this public key generated with the user's Identity Key.
 */
@Serializable
data class SignedPreKeyDto(
    @SerialName("key_id")
    val keyId: Int,
    @SerialName("public_key")
    val publicKey: String,
    val signature: String
)

/**
 * Represents an ephemeral One-Time PreKey in the Signal Protocol pool.
 *
 * @param keyId Unique integer identifier for this one-time prekey.
 * @param publicKey The public key string (Base64).
 */
@Serializable
data class OneTimePreKeyDto(
    @SerialName("key_id")
    val keyId: Int,
    @SerialName("public_key")
    val publicKey: String
)

/**
 * Request body for uploading or rotating a user's Signal PreKeys.
 * Both fields are optional so a client can upload just a new signed prekey,
 * or replenish just the pool of one-time prekeys.
 */
@Serializable
data class UploadKeysRequest(
    @SerialName("signed_prekey")
    val signedPreKey: SignedPreKeyDto? = null,
    @SerialName("one_time_prekeys")
    val oneTimePreKeys: List<OneTimePreKeyDto>? = null
)

/**
 * PreKey bundle returned by the server when a user initiates a Signal session with a friend.
 * Contains the friend's Identity Key, active Signed PreKey, and an atomically popped One-Time PreKey.
 */
@Serializable
data class PreKeyBundleResponse(
    @SerialName("user_id")
    val userId: String,
    @SerialName("identity_key")
    val identityKey: String,
    @SerialName("signed_prekey")
    val signedPreKey: SignedPreKeyDto,
    @SerialName("one_time_prekey")
    val oneTimePreKey: OneTimePreKeyDto? = null
)

/**
 * Response from GET /keys/count indicating how many One-Time PreKeys remain in the user's server pool.
 */
@Serializable
data class KeyCountResponse(
    @SerialName("remaining_one_time_prekeys")
    val remainingOneTimePrekeys: Long
)
