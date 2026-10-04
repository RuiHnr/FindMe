package com.ruirui.findme.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Polymorphic base class for all encrypted messages sent via the FindMe backend.
 * Uses Kotlin Serialization's sealed class support to automatically inject and parse 
 * a "type" discriminator field ("normal_message" or "prekey_message") in JSON.
 */
@Serializable
sealed class SignalMessageEnvelope {
    abstract val ciphertext: EncryptedMessage
}

/**
 * A standard Double Ratchet message used after the X3DH session is fully established.
 * Contains only the ratchet metadata and the encrypted payload.
 */
@Serializable
@SerialName("normal_message")
data class NormalSignalEnvelope(
    override val ciphertext: EncryptedMessage
) : SignalMessageEnvelope()

/**
 * The initial message sent by Alice to Bob to establish the X3DH session.
 * Contains both the unencrypted X3DH public keys (header) and the first 
 * encrypted message (ciphertext) initialized with the new Double Ratchet.
 */
@Serializable
@SerialName("prekey_message") // This tells Kotlin Serialization what 'type' string to inject
data class PreKeySignalEnvelope(
    val header: MessageHeader,
    override val ciphertext: EncryptedMessage
) : SignalMessageEnvelope()

/**
 * Message retrieved and consumed from a user's location inbox.
 *
 * @param senderId The UUID of the friend who sent this location update.
 * @param encryptedPayload The opaque ciphertext to be decrypted by the recipient, either a PreKeySignalMessage or NormalSignalEnvelope.
 */
@Serializable
data class InboxMessage(
    @SerialName("sender_id") val senderId: String,
    @SerialName("encrypted_payload") val encryptedPayload: SignalMessageEnvelope
)

/**
 * Result of fetching the inbox. Contains the messages and the server's remaining OTPK count.
 */
data class InboxResponse(
    val messages: List<InboxMessage>,
    val remainingPreKeys: Int?
)

/**
 * Payload sent by an authenticated client to queue an encrypted location package
 * in a friend's inbox.
 *
 * @param receiverId The UUID of the friend who will receive this update.
 * @param encryptedBlob The opaque ciphertext containing the encrypted location data.
 */
@Serializable
data class SubmitMessageRequest(
    @SerialName("receiver_id")
    val receiverId: String,
    @SerialName("encrypted_blob")
    val encryptedBlob: String
)

@Serializable
data class SubmitLocationResponse(
    val accepted: Int
)


/**
 * Represents the AES-256-GCM encrypted payload and the metadata required to 
 * turn the Double Ratchet (DH ratchet and symmetric ratchet) on the receiver's side.
 */
@Serializable
data class EncryptedMessage(
    // The sender's current public Diffie-Hellman key (triggers the DH Ratchet)
    val ratchetKey: ByteArray,

    // The index of this message in the current sending chain
    val msgNumber: Int,

    // How many messages were in the previous chain (helps the receiver know exactly how many messages they missed when the ratchet turns)
    val previousChainLength: Int,

    // The actual AES-256-GCM encrypted location payload
    val ciphertext: ByteArray,
)

/**
 * The inner, highly sensitive plaintext location data that gets serialized to JSON 
 * and then encrypted into the [EncryptedMessage.ciphertext].
 */
@Serializable
data class LocationPayload(val lat: Double, val lng: Double, val timestamp: Long)


/**
 * The unencrypted header of a [PreKeySignalEnvelope]. Contains Alice's public keys 
 * so Bob can run his side of the X3DH agreement when he receives this message.
 */
@Serializable
data class MessageHeader(
    val aliceIdentityKeyDh: String, // Base64 Public Key
    val aliceBaseKeyDh: String,     // Base64 Ephemeral Public Key
    val bobSignedPreKeyId: Int,     // Which of Bob's prekeys Alice used
    val bobOneTimePreKeyId: Int?    // Which one-time key Alice used (if any)
)