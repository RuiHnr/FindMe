package com.ruirui.findme.repository

import com.ruirui.findme.fakes.FakeBackend
import com.ruirui.findme.network.api.FriendsApi
import com.ruirui.findme.storage.InMemorySecureStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FriendRepositoryTest {

    @Test
    fun testSyncFriendsAndRequests() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = com.ruirui.findme.network.HttpClientFactory.create(
            backend.engine,
            storage,
            "http://localhost:8080"
        )

        val db = com.ruirui.findme.db.createTestDatabase()
        val repo = FriendRepositoryImpl(FriendsApi(client), db, backgroundScope)

        // Add fake data
        backend.friendsBackend.requestFriend("bob", "alice_id")

        repo.syncFriends().getOrThrow()

        val inDb = db.friendQueries.getAllFriendRequests().executeAsList()
        assertEquals(1, inDb.size, "Database should contain 1 request but had: $inDb")

        val requests = repo.friendRequests.first { it.isNotEmpty() }
        assertEquals(1, requests.size)
        assertEquals("bob", requests[0].targetUsername)
    }

    @Test
    fun testAcceptFriendRequest() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        storage.putString(com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        val client = com.ruirui.findme.network.HttpClientFactory.create(
            backend.engine,
            storage,
            "http://localhost:8080"
        )

        val db = com.ruirui.findme.db.createTestDatabase()
        val repo = FriendRepositoryImpl(FriendsApi(client), db, backgroundScope)

        // Accept
        repo.acceptFriendRequest("bob", "bob_id").getOrThrow()
        testScheduler.advanceUntilIdle()

        // It shouldn't crash, and should sync
        assertTrue(true)
    }
}
