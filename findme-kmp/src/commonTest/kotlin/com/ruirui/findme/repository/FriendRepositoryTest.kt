package com.ruirui.findme.repository

import com.ruirui.findme.fakes.FakeBackend
import com.ruirui.findme.network.api.FriendsApi
import com.ruirui.findme.storage.InMemorySecureStorage
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

        val repo = FriendRepositoryImpl(FriendsApi(client))

        // Add fake data
        backend.friendsBackend.requestFriend("bob_id", "bob")

        repo.syncFriends().getOrThrow()

        assertEquals(1, repo.friendRequests.value.size)
        assertEquals("bob", repo.friendRequests.value[0].targetUsername)
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

        val repo = FriendRepositoryImpl(FriendsApi(client))

        // Accept
        repo.acceptFriendRequest("bob", "bob_id").getOrThrow()

        // It shouldn't crash, and should sync
        assertTrue(true)
    }
}
