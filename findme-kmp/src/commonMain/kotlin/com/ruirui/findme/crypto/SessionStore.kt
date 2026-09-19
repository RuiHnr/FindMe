package com.ruirui.findme.crypto

import com.ruirui.findme.storage.SecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import kotlinx.serialization.json.Json

/**
 * Defines the contract for securely persisting and retrieving cryptographic sessions 
 * established with other users (friends).
 * 
 * Cryptographic sessions (instances of [DoubleRatchetSession]) contain highly sensitive 
 * symmetric keys used to encrypt/decrypt location updates. They must be persisted 
 * across app restarts to maintain the End-to-End Encryption (E2EE) messaging chain.
 */
interface SessionStore {
    /**
     * Loads the stored [DoubleRatchetSession] for the given [remoteUserId].
     * Returns null if no active session exists yet.
     */
    suspend fun loadSession(remoteUserId: String): DoubleRatchetSession?
    
    /**
     * Persists the given [DoubleRatchetSession] state for the [remoteUserId].
     * This is typically called after the ratchet turns (e.g., after sending or receiving a message),
     * ensuring the latest KDF chain keys are securely stored.
     */
    suspend fun saveSession(remoteUserId: String, session: DoubleRatchetSession)
}

class SessionStoreImpl(
    private val secureStorage: SecureStorage,
    private val crypto: Crypto
) : SessionStore {
    override suspend fun loadSession(remoteUserId: String): DoubleRatchetSession? {
        // 1. Fetch JSON string from storage
        val jsonString = secureStorage.getString(SecureStorageKeys.ratchetState(remoteUserId))
            ?: return null

        // 2. Deserialize JSON string into DoubleRatchetState
        return try {
            val state = Json.decodeFromString(DoubleRatchetState.serializer(), jsonString)
            state.toSession(crypto)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun saveSession(
        remoteUserId: String,
        session: DoubleRatchetSession
    ) {
        // 1. Convert DoubleRatchetSession to DoubleRatchetState
        val state = session.toState()

        // 2. Serialize DoubleRatchetState to JSON string
        val jsonString = Json.encodeToString(DoubleRatchetState.serializer(), state)

        // 3. Save JSON string to storage
        secureStorage.putString(SecureStorageKeys.ratchetState(remoteUserId), jsonString)
    }

}