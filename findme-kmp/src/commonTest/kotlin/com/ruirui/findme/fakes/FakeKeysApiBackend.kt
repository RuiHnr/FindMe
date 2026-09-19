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
        bundles[userId] = PreKeyBundleResponse(
            userId = userId,
            identityKeyDh = identityKeysDh[userId] ?: "",
            identityKeySign = identityKeysSign[userId] ?: "",
            signedPreKey = request.signedPreKey!!,
            oneTimePreKey = request.oneTimePreKeys?.firstOrNull()
        )
    }

    fun getPreKeyBundle(userId: String): PreKeyBundleResponse {
        return bundles[userId]
            ?: throw Exception("PreKeyBundle not found for '$userId'. Available bundles: ${bundles.keys.joinToString()}")
    }
}
