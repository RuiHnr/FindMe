package com.ruirui.findme.crypto

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A purely data-oriented representation of the [DoubleRatchetSession] state.
 *
 * This class is designed exclusively for serialization (e.g., saving to Android DataStore or iOS Keychain).
 * By separating this from the active session class, we avoid attempting to serialize business logic,
 * cryptographic primitive dependencies, or complex nested byte arrays.
 */
@Serializable
data class DoubleRatchetState(
    /** The root key for the Diffie-Hellman ratchet, encoded as a Base64 string. */
    val rootKeyBase64: String,
    /** The current chain key for the sending symmetric ratchet (null if not yet initialized). */
    val sendingChainKeyBase64: String?,
    /** The message index of the current sending chain. */
    val sendingChainIndex: Int,
    /** The current chain key for the receiving symmetric ratchet (null if not yet initialized). */
    val receivingChainKeyBase64: String?,
    /** The message index of the current receiving chain. */
    val receivingChainIndex: Int,
    /** The local ephemeral public key currently used for sending messages. */
    val sendingRatchetPubKeyBase64: String,
    /** The local ephemeral private key currently used for sending messages. */
    val sendingRatchetPrivKeyBase64: String,
    /** The remote user's ephemeral public key currently used for receiving messages. */
    val receivingRatchetPubKeyBase64: String?,
    /** The length of the previous sending chain, required for the header of outgoing messages. */
    val previousSendingChainLength: Int,
    /** 
     * Buffered keys for messages that arrived out of order.
     * Mapped as: RemoteRatchetPubKeyBase64 -> (MessageIndex -> MessageKeyBase64) 
     */
    val skippedMessageKeys: Map<String, Map<Int, String>>
)

/**
 * Converts an active [DoubleRatchetSession] state machine into a static, serializable [DoubleRatchetState].
 */
@OptIn(ExperimentalEncodingApi::class)
fun DoubleRatchetSession.toState(): DoubleRatchetState {
    return DoubleRatchetState(
        rootKeyBase64 = Base64.encode(this.rootKey),

        sendingChainKeyBase64 = this.sendingChain?.chainKey?.let { Base64.encode(it) },
        sendingChainIndex = this.sendingChain?.index ?: 0,

        receivingChainKeyBase64 = this.receivingChain?.chainKey?.let { Base64.encode(it) },
        receivingChainIndex = this.receivingChain?.index ?: 0,

        sendingRatchetPubKeyBase64 = Base64.encode(this.sendingRatchetKey.publicKey),
        sendingRatchetPrivKeyBase64 = Base64.encode(this.sendingRatchetKey.privateKey),

        receivingRatchetPubKeyBase64 = this.receivingRatchetKey?.let { Base64.encode(it) },

        previousSendingChainLength = this.previousSendingChainLength,

        skippedMessageKeys = this.skippedMessageKeys.mapValues { (_, innerMap) ->
            innerMap.mapValues { (_, msgKey) -> Base64.encode(msgKey) }
        }
    )
}

/**
 * Reconstructs an active [DoubleRatchetSession] state machine from a static [DoubleRatchetState].
 *
 * @param crypto The cryptographic provider required to initialize the KDF chains and the session.
 */
@OptIn(ExperimentalEncodingApi::class)
fun DoubleRatchetState.toSession(crypto: Crypto): DoubleRatchetSession {
    val sendingChain = this.sendingChainKeyBase64?.let {
        KdfChain(chainKey = Base64.decode(it), index = this.sendingChainIndex, crypto = crypto)
    }

    val receivingChain = this.receivingChainKeyBase64?.let {
        KdfChain(chainKey = Base64.decode(it), index = this.receivingChainIndex, crypto = crypto)
    }

    val sendingRatchetKey = KeyPair(
        publicKey = Base64.decode(this.sendingRatchetPubKeyBase64),
        privateKey = Base64.decode(this.sendingRatchetPrivKeyBase64)
    )

    val skippedKeysMap = this.skippedMessageKeys.mapValues { (_, innerMap) ->
        innerMap.mapValues { (_, msgKeyBase64) -> Base64.decode(msgKeyBase64) }.toMutableMap()
    }.toMutableMap()

    return DoubleRatchetSession(
        rootKey = Base64.decode(this.rootKeyBase64),
        sendingChain = sendingChain,
        receivingChain = receivingChain,
        sendingRatchetKey = sendingRatchetKey,
        receivingRatchetKey = this.receivingRatchetPubKeyBase64?.let { Base64.decode(it) },
        skippedMessageKeys = skippedKeysMap,
        previousSendingChainLength = this.previousSendingChainLength,
        crypto = crypto
    )
}