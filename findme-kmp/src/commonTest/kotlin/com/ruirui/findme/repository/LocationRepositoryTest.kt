package com.ruirui.findme.repository

import com.ruirui.findme.crypto.CryptoKotlinAdapter
import com.ruirui.findme.crypto.SessionStoreImpl
import com.ruirui.findme.fakes.FakeBackend
import com.ruirui.findme.network.api.FriendsApi
import com.ruirui.findme.network.api.KeysApi
import com.ruirui.findme.network.api.LocationApi
import com.ruirui.findme.storage.InMemorySecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class LocationRepositoryTest {

    @Test
    fun testSyncInbox() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = com.ruirui.findme.network.HttpClientFactory.create(
            backend.engine,
            storage,
            "http://localhost:8080"
        )
        val crypto = CryptoKotlinAdapter()

        // Set user ID so syncInbox can fetch it
        storage.putString(SecureStorageKeys.USER_ID, "alice_id")

        val friendRepo = FriendRepositoryImpl(FriendsApi(client))

        val repo = LocationRepositoryImpl(
            LocationApi(client),
            KeysApi(client),
            crypto,
            storage,
            SessionStoreImpl(storage, crypto),
            friendRepo
        )

        // Sync empty inbox
        repo.syncInbox()

        // Inbox should be empty map
        assertTrue(repo.friendLocations.value.isEmpty())
    }
}
