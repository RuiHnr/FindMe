package com.ruirui.findme.storage

object SecureStorageKeys {
    // Auth & Identity
    const val AUTH_TOKEN = "auth_token"
    const val USER_ID = "user_id"

    // Cryptography
    const val IDENTITY_PRIVATE_KEY_DH = "identity_private_key_dh"

    const val IDENTITY_PUBLIC_KEY_DH = "identity_public_key_dh"
    const val IDENTITY_PRIVATE_KEY_SIGN = "identity_private_key_sign"

    const val IDENTITY_PUBLIC_KEY_SIGN = "identity_public_key_sign"

    // Dynamic Keys
    fun ratchetState(friendId: String): String = "ratchet_state_$friendId"

    fun signedPreKeyPrivate(keyId: Int) = "signed_prekey_private_$keyId"

    fun signedPreKeyPublic(keyId: Int) = "signed_prekey_public_$keyId"

    fun oneTimePreKeyPrivate(keyId: Int) = "onetime_prekey_private_$keyId"
}