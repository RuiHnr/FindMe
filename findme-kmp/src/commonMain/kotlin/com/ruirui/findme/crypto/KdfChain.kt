package com.ruirui.findme.crypto

class KdfChain(
    chainKey: ByteArray,
    index: Int = 0,
    private val crypto: Crypto
) {
    var chainKey: ByteArray = chainKey
        private set

    var index: Int = index
        private set

    /**
     * Advances the KDF chain by one step.
     * 
     * Applies HMAC-SHA256 to the current chain key to derive two new outputs:
     * 1. The Message Key (used to encrypt/decrypt the current message).
     * 2. The Next Chain Key (replaces the current chain key for future use).
     *
     * @return A Pair containing the derived 32-byte Message Key and the index of this message in the chain.
     */
    suspend fun next(): Pair<ByteArray, Int> {
        // HMAC with specific constants (one for message key, one for next chain key)
        val messageKey = crypto.hmacSha256(chainKey, byteArrayOf(0x01))
        chainKey = crypto.hmacSha256(chainKey, byteArrayOf(0x02))
        val currentIndex = index
        index++
        return Pair(messageKey, currentIndex)
    }
}