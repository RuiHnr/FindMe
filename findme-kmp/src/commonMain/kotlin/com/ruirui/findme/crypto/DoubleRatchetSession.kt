package com.ruirui.findme.crypto

import com.ruirui.findme.models.EncryptedMessage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Double Ratchet Session state machine.
 *
 * This class implements the Signal Protocol's Double Ratchet, consisting of:
 * - A Root Ratchet (Diffie-Hellman) that advances when the sender changes, providing post-compromise security.
 * - Sending and Receiving Ratchets (Symmetric KDF) that advance for every message, providing forward secrecy.
 * - Out-of-order message handling via the `skippedMessageKeys` buffer.
 */
@OptIn(ExperimentalEncodingApi::class)
class DoubleRatchetSession(
    rootKey: ByteArray,
    sendingChain: KdfChain?,
    receivingChain: KdfChain?,
    sendingRatchetKey: KeyPair,
    receivingRatchetKey: ByteArray?,
    /**
     * Stores keys for messages that arrived out of order.
     * Mapped by RemoteRatchetPubKeyBase64 -> (MessageIndex -> MessageKey)
     */
    val skippedMessageKeys: MutableMap<String, MutableMap<Int, ByteArray>> = mutableMapOf(),
    previousSendingChainLength: Int = 0,
    private val crypto: Crypto
) {
    var rootKey: ByteArray = rootKey
        private set

    var sendingChain: KdfChain? = sendingChain
        private set

    var receivingChain: KdfChain? = receivingChain
        private set

    var sendingRatchetKey: KeyPair = sendingRatchetKey
        private set

    var receivingRatchetKey: ByteArray? = receivingRatchetKey
        private set

    /**
     * Stores the length of the previous sending chain.
     * This is included in encrypted messages so the receiver knows how many messages to skip before ratcheting.
     */
    var previousSendingChainLength: Int = previousSendingChainLength
        private set

    companion object {
        /**
         * Maximum number of messages to skip in a single chain before throwing an exception.
         * Signal recommends 2000 to prevent memory exhaustion attacks (DoS).
         */
        const val MAX_SKIP = 2000

        /**
         * Initializes a new session for the Initiator (e.g., Alice).
         * Used when sending the very first message.
         *
         * @param sharedSecret The master secret derived from X3DH.
         * @param bobSignedPreKeyPub The receiver's public signed prekey.
         */
        suspend fun initAlice(
            sharedSecret: ByteArray,
            bobSignedPreKeyPub: ByteArray,
            crypto: Crypto
        ): DoubleRatchetSession {
            val aliceRatchetKey = crypto.generateX25519KeyPair()
            val dhOut = crypto.calculateDhAgreement(aliceRatchetKey.privateKey, bobSignedPreKeyPub)
            val (rootKey, sendingChainKey) = hkdfSplit(sharedSecret, dhOut, crypto)

            return DoubleRatchetSession(
                rootKey = rootKey,
                sendingChain = KdfChain(sendingChainKey, 0, crypto),
                receivingChain = null,
                sendingRatchetKey = aliceRatchetKey,
                receivingRatchetKey = bobSignedPreKeyPub,
                previousSendingChainLength = 0,
                crypto = crypto
            )
        }

        /**
         * Initializes a new session for the Receiver (e.g., Bob).
         * Used when waiting for the first message from the Initiator.
         *
         * @param sharedSecret The master secret derived from X3DH.
         * @param bobRatchetKeyPair The receiver's key pair that the Initiator aimed at.
         */
        suspend fun initBob(
            sharedSecret: ByteArray,
            bobRatchetKeyPair: KeyPair,
            crypto: Crypto
        ): DoubleRatchetSession {
            return DoubleRatchetSession(
                rootKey = sharedSecret,
                sendingChain = null,
                receivingChain = null,
                sendingRatchetKey = bobRatchetKeyPair,
                receivingRatchetKey = null,
                previousSendingChainLength = 0,
                crypto = crypto
            )
        }

        private suspend fun hkdfSplit(
            root: ByteArray,
            dh: ByteArray,
            crypto: Crypto
        ): Pair<ByteArray, ByteArray> {
            val output = crypto.hkdf(dh, root, "FindMe-Ratchet".encodeToByteArray(), 64)
            return Pair(output.copyOfRange(0, 32), output.copyOfRange(32, 64))
        }
    }

    /**
     * Encrypts a plaintext message and advances the sending chain.
     */
    suspend fun encrypt(plaintext: ByteArray): EncryptedMessage {
        val (messageKey, msgNum) = sendingChain!!.next()
        val ciphertextWithIv = crypto.encryptAesGcm(messageKey, plaintext, byteArrayOf())

        return EncryptedMessage(
            ratchetKey = sendingRatchetKey.publicKey,
            msgNumber = msgNum,
            previousChainLength = previousSendingChainLength,
            ciphertext = ciphertextWithIv,
        )
    }

    /**
     * Decrypts an incoming message.
     * Advances the receiving chain, buffers skipped messages, and performs a DH Ratchet step if the sender's ratchet key changed.
     */
    suspend fun decrypt(message: EncryptedMessage): ByteArray {
        val remoteKeyStr = Base64.encode(message.ratchetKey)

        // 1. Check if we already skipped this message and saved its key
        skippedMessageKeys[remoteKeyStr]?.get(message.msgNumber)?.let { messageKey ->
            skippedMessageKeys[remoteKeyStr]!!.remove(message.msgNumber)
            return crypto.decryptAesGcm(messageKey, message.ciphertext, byteArrayOf())
        }

        // 2. If the ratchet key changed, perform a DH Ratchet Step
        if (!message.ratchetKey.contentEquals(receivingRatchetKey)) {
            // Fast-forward missed messages in current receiving chain before ratcheting
            skipMessagesKeys(message.previousChainLength)
            performDHRatchet(message.ratchetKey)
        }

        // 3. Fast-forward missed messages in the NEW chain up to the current message
        skipMessagesKeys(message.msgNumber)

        // 4. Decrypt the actual message
        val (messageKey, _) = receivingChain!!.next()
        return crypto.decryptAesGcm(messageKey, message.ciphertext, byteArrayOf())
    }

    private suspend fun skipMessagesKeys(until: Int) {
        val chain = receivingChain ?: return
        if (until - chain.index > MAX_SKIP) {
            throw IllegalArgumentException("Too many skipped messages: ${until - chain.index}")
        }
        val remoteKeyStr = Base64.encode(receivingRatchetKey!!)
        while (chain.index < until) {
            val (msgKey, msgNum) = chain.next()
            val map = skippedMessageKeys.getOrPut(remoteKeyStr) { mutableMapOf() }
            map[msgNum] = msgKey
        }
    }

    /**
     * Performs a Diffie-Hellman Ratchet step.
     * Updates the root key, creates a new receiving chain from the remote key, and generates a new sending chain.
     */
    private suspend fun performDHRatchet(newRemoteKey: ByteArray) {
        receivingRatchetKey = newRemoteKey

        // 1. DH between our sending key and their new receiving key
        val dh1 = crypto.calculateDhAgreement(sendingRatchetKey.privateKey, receivingRatchetKey!!)
        val (newRoot1, newRecvChainKey) = Companion.hkdfSplit(rootKey, dh1, crypto)
        receivingChain = KdfChain(newRecvChainKey, 0, crypto)

        // Save the length of the previous sending chain before replacing it
        previousSendingChainLength = sendingChain?.index ?: 0

        // 2. Generate new sending key pair
        sendingRatchetKey = crypto.generateX25519KeyPair()

        // 3. DH between our new sending key and their receiving key
        val dh2 = crypto.calculateDhAgreement(sendingRatchetKey.privateKey, receivingRatchetKey!!)
        val (newRoot2, newSendChainKey) = Companion.hkdfSplit(newRoot1, dh2, crypto)
        rootKey = newRoot2
        sendingChain = KdfChain(newSendChainKey, 0, crypto)
    }
}