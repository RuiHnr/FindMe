package com.ruirui.findme.repository

import com.ruirui.findme.crypto.SafetyNumberHelper
import com.ruirui.findme.crypto.SessionStore
import com.ruirui.findme.db.FindMeDatabase
import com.ruirui.findme.models.IdentityPinInfo
import com.ruirui.findme.storage.SecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import io.ktor.util.date.getTimeMillis

interface SecurityRepository {
    /**
     * Retrieves the stored identity pin details for a given friend.
     */
    suspend fun getIdentityPin(friendId: String): IdentityPinInfo?

    /**
     * Computes the human-readable Safety Number between the local user and a friend.
     */
    suspend fun getSafetyNumber(friendId: String): Result<String>

    /**
     * Manually accepts a new Identity Key for a friend after out-of-band verification
     * (e.g. following a legitimate device reset). Clears any old session and establishes a new pin.
     */
    suspend fun trustNewIdentityKey(
        friendId: String,
        newIdentityKeyDh: String,
        newIdentityKeySign: String
    ): Result<Unit>
}

class SecurityRepositoryImpl(
    private val db: FindMeDatabase,
    private val secureStorage: SecureStorage,
    private val sessionStore: SessionStore
) : SecurityRepository {
    override suspend fun getIdentityPin(friendId: String): IdentityPinInfo? {
        val row = db.identityPinQueries.getPin(friendId).executeAsOneOrNull() ?: return null
        return IdentityPinInfo(
            userId = row.user_id,
            identityKeyDhBase64 = row.identity_key_dh,
            identityKeySignBase64 = row.identity_key_sign,
            firstSeenAt = row.first_seen_at,
            lastVerifiedAt = row.last_verified_at,
            isTrusted = row.is_trusted == 1L
        )
    }

    override suspend fun getSafetyNumber(friendId: String): Result<String> = runCatching {
        val myId = secureStorage.getString(SecureStorageKeys.USER_ID)
            ?: error("User ID not found")
        val myPubKeyDh = secureStorage.getString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH)
            ?: error("Identity DH public key not found")

        val friendPin = db.identityPinQueries.getPin(friendId).executeAsOneOrNull()
            ?: error("No pinned identity found for friend $friendId. Message them first.")

        SafetyNumberHelper.computeSafetyNumber(
            aliceId = myId,
            aliceIdentityPubKeyDh = myPubKeyDh,
            bobId = friendId,
            bobIdentityPubKeyDh = friendPin.identity_key_dh
        )
    }

    override suspend fun trustNewIdentityKey(
        friendId: String,
        newIdentityKeyDh: String,
        newIdentityKeySign: String
    ): Result<Unit>  = runCatching {
        // 1. Overwrite pin with new verified key
        db.identityPinQueries.upsertPin(
            user_id = friendId,
            identity_key_dh = newIdentityKeyDh,
            identity_key_sign = newIdentityKeySign,
            first_seen_at = getTimeMillis(),
            last_verified_at = getTimeMillis(),
            is_trusted = 1L
        )

        // 2. Clear old session ratchet state to force fresh X3DH on next send
        sessionStore.deleteSession(friendId)
    }
}