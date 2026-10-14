package com.ruirui.orbit.repository

import com.ruirui.orbit.crypto.CryptoKotlinAdapter
import com.ruirui.orbit.crypto.SessionStoreImpl
import com.ruirui.orbit.fakes.FakeBackend
import com.ruirui.orbit.network.api.KeysApi
import com.ruirui.orbit.network.api.LocationApi
import com.ruirui.orbit.storage.InMemorySecureStorage
import com.ruirui.orbit.storage.SecureStorageKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class LocationRepositoryTest {

    @Test
    fun testSyncInbox() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = com.ruirui.orbit.network.HttpClientFactory.create(
            backend.engine,
            storage,
            "http://localhost:8080"
        )
        val crypto = CryptoKotlinAdapter()

        // Set user ID so syncInbox can fetch it
        storage.putString(SecureStorageKeys.USER_ID, "alice_id")

        val db = com.ruirui.orbit.db.createTestDatabase()
        val preKeyManager = com.ruirui.orbit.crypto.PreKeyManagerImpl(crypto, storage, KeysApi(client), db)
        val repo = LocationRepositoryImpl(
            LocationApi(client),
            KeysApi(client),
            crypto,
            storage,
            SessionStoreImpl(storage, crypto),
            preKeyManager,
            db,
            backgroundScope
        )

        // Sync empty inbox
        repo.syncInbox().getOrThrow()

        // Inbox should be empty map
        assertTrue(repo.friendLocations.value.isEmpty())
    }
}
