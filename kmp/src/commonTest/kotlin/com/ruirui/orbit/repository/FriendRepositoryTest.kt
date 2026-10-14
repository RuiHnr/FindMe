package com.ruirui.orbit.repository

import com.ruirui.orbit.fakes.FakeBackend
import com.ruirui.orbit.models.SignedPreKeyDto
import com.ruirui.orbit.models.UploadKeysRequest
import com.ruirui.orbit.network.api.FriendsApi
import com.ruirui.orbit.network.api.KeysApi
import com.ruirui.orbit.storage.InMemorySecureStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FriendRepositoryTest {

    @Test
    fun testSyncFriendsAndRequests() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(com.ruirui.orbit.storage.SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = com.ruirui.orbit.network.HttpClientFactory.create(
            backend.engine,
            storage,
            "http://localhost:8080"
        )

        val db = com.ruirui.orbit.db.createTestDatabase()
        val repo = FriendRepositoryImpl(FriendsApi(client), KeysApi(client), db, backgroundScope)

        // Add fake data
        backend.friendsBackend.requestFriend("bob", "alice_id")

        repo.syncFriends().getOrThrow()

        val inDb = db.friendQueries.getAllFriendRequests().executeAsList()
        assertEquals(1, inDb.size, "Database should contain 1 request but had: $inDb")

        val requests = repo.friendRequests.first { it.isNotEmpty() }
        assertEquals(1, requests.size)
        assertEquals("bob", requests[0].userId)
    }

    @Test
    fun testAcceptFriendRequest() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(com.ruirui.orbit.storage.SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = com.ruirui.orbit.network.HttpClientFactory.create(
            backend.engine,
            storage,
            "http://localhost:8080"
        )

        val db = com.ruirui.orbit.db.createTestDatabase()
        val repo = FriendRepositoryImpl(FriendsApi(client), KeysApi(client), db, backgroundScope)

        // Seed keys for bob_id in backend so getPreKeyBundle succeeds
        backend.keysBackend.uploadKeys(
            "bob_id",
            UploadKeysRequest(
                signedPreKey = SignedPreKeyDto(1, "bob_spk_pub", "signature")
            )
        )

        // Accept
        repo.acceptFriendRequest("bob_id").getOrThrow()
        testScheduler.advanceUntilIdle()

        // Verify identity pin was saved
        val pin = db.identityPinQueries.getPin("bob_id").executeAsOneOrNull()
        assertNotNull(pin, "Identity pin should be saved on accepting friend request")
    }
}
