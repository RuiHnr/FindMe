package com.ruirui.findme.fakes

import com.ruirui.findme.models.PreKeyBundleResponse
import com.ruirui.findme.models.UploadKeysRequest

class FakeKeysApiBackend {
    // Maps userId -> PreKeyBundleResponse
    private val bundles = mutableMapOf<String, PreKeyBundleResponse>()

    // Temporary storage for identity keys from registration
    val identityKeysDh = mutableMapOf<String, String>()
    val identityKeysSign = mutableMapOf<String, String>()

    fun uploadKeys(userId: String, request: UploadKeysRequest) {
        val existingBundle = bundles[userId]
        val signedPreKey = request.signedPreKey ?: existingBundle?.signedPreKey
        ?: throw Exception("SignedPreKey required for initial upload")

        bundles[userId] = PreKeyBundleResponse(
            userId = userId,
            identityKeyDh = identityKeysDh[userId] ?: existingBundle?.identityKeyDh ?: "",
            identityKeySign = identityKeysSign[userId] ?: existingBundle?.identityKeySign ?: "",
            signedPreKey = signedPreKey,
            oneTimePreKey = request.oneTimePreKeys?.firstOrNull() ?: existingBundle?.oneTimePreKey
        )
    }

    fun getPreKeyBundle(userId: String): PreKeyBundleResponse {
        return bundles[userId]
            ?: throw Exception("PreKeyBundle not found for '$userId'. Available bundles: ${bundles.keys.joinToString()}")
    }
}
