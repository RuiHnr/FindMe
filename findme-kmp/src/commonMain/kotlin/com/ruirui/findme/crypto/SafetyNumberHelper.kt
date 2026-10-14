package com.ruirui.findme.crypto

object SafetyNumberHelper {

    private val crypto: Crypto = CryptoKotlinAdapter()

    /**
     * Generates a 60-digit human-readable Safety Number between two users.
     * Formatted in 12 groups of 5 digits: "12345 67890 ..."
     *
     * Symmetric: produces the EXACT same string regardless of whether Alice or Bob computes it.
     */
    suspend fun computeSafetyNumber(
        aliceId: String,
        aliceIdentityPubKeyDh: String,
        bobId: String,
        bobIdentityPubKeyDh: String
    ): String {
        // 1. Sort users lexicographically so calculation is order-independent
        val (firstId, firstKey, secondId, secondKey) = if (aliceId < bobId) {
            listOf(aliceId, aliceIdentityPubKeyDh, bobId, bobIdentityPubKeyDh)
        } else {
            listOf(bobId, bobIdentityPubKeyDh, aliceId, aliceIdentityPubKeyDh)
        }

        // 2. Prepare payload to hash
        val payload = "$firstId:$firstKey|$secondId:$secondKey".encodeToByteArray()

        // 3. Digest using SHA-512
        val hash = crypto.sha512(payload)

        val stringBuilder = StringBuilder()
        for (i in 0 until 12) {
            val offset = i * 2
            val chunk = ((hash[offset].toInt() and 0xff) shl 8) or (hash[offset + 1].toInt() and 0xff)
            val fiveDigits = (chunk % 100000).toString().padStart(5, '0')
            if (i > 0) stringBuilder.append(' ')
            stringBuilder.append(fiveDigits)
        }
        return stringBuilder.toString()
    }
}