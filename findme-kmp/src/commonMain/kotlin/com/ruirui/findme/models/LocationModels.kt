package com.ruirui.findme.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload sent by an authenticated client to queue an encrypted location package
 * in a friend's inbox.
 *
 * @param receiverId The UUID of the friend who will receive this update.
 * @param encryptedBlob The opaque ciphertext containing the encrypted location data.
 */
@Serializable
data class SubmitLocationRequest(
    @SerialName("receiver_id")
    val receiverId: String,
    @SerialName("encrypted_blob")
    val encryptedBlob: String
)

/**
 * Message retrieved and consumed from a user's location inbox.
 *
 * @param senderId The UUID of the friend who sent this location update.
 * @param encryptedPayload The opaque ciphertext to be decrypted by the recipient.
 */
@Serializable
data class InboxMessage(
    @SerialName("sender_id")
    val senderId: String,
    @SerialName("encrypted_payload")
    val encryptedPayload: String
)