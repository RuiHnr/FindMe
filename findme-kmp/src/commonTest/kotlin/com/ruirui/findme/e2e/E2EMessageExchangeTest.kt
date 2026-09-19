package com.ruirui.findme.e2e

import com.ruirui.findme.crypto.CryptoKotlinAdapter
import com.ruirui.findme.crypto.SessionStoreImpl
import com.ruirui.findme.fakes.FakeBackend
import com.ruirui.findme.network.api.AuthApi
import com.ruirui.findme.network.api.FriendsApi
import com.ruirui.findme.network.api.KeysApi
import com.ruirui.findme.network.api.LocationApi
import com.ruirui.findme.repository.AuthRepositoryImpl
import com.ruirui.findme.repository.FriendRepositoryImpl
import com.ruirui.findme.repository.LocationRepositoryImpl
import com.ruirui.findme.storage.InMemorySecureStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class E2EMessageExchangeTest {

    @Test
    fun testAliceSendsLocationToBob() = runTest {
        // 1. Setup Backend
        val backend = FakeBackend()
        val crypto = CryptoKotlinAdapter()

        // 2. Setup Alice
        val aliceStorage = InMemorySecureStorage()
        val aliceClient = com.ruirui.findme.network.HttpClientFactory.create(
            backend.engine,
            aliceStorage,
            "http://localhost:8080"
        )
        val alicePreKeyManager = com.ruirui.findme.crypto.PreKeyManagerImpl(crypto, aliceStorage, KeysApi(aliceClient))
        val aliceAuthRepo = AuthRepositoryImpl(
            AuthApi(aliceClient),
            alicePreKeyManager,
            aliceStorage,
            crypto,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        )
        val aliceFriendRepo = FriendRepositoryImpl(FriendsApi(aliceClient))
        val aliceLocationRepo = LocationRepositoryImpl(
            LocationApi(aliceClient),
            KeysApi(aliceClient),
            crypto,
            aliceStorage,
            SessionStoreImpl(aliceStorage, crypto),
            aliceFriendRepo,
            alicePreKeyManager
        )

        // 3. Setup Bob
        val bobStorage = InMemorySecureStorage()
        val bobClient = com.ruirui.findme.network.HttpClientFactory.create(
            backend.engine,
            bobStorage,
            "http://localhost:8080"
        )
        val bobPreKeyManager = com.ruirui.findme.crypto.PreKeyManagerImpl(crypto, bobStorage, KeysApi(bobClient))
        val bobAuthRepo = AuthRepositoryImpl(
            AuthApi(bobClient),
            bobPreKeyManager,
            bobStorage,
            crypto,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        )
        val bobFriendRepo = FriendRepositoryImpl(FriendsApi(bobClient))
        val bobLocationRepo = LocationRepositoryImpl(
            LocationApi(bobClient),
            KeysApi(bobClient),
            crypto,
            bobStorage,
            SessionStoreImpl(bobStorage, crypto),
            bobFriendRepo,
            bobPreKeyManager
        )

        // 4. Registration
        aliceAuthRepo.register("alice").getOrThrow()
        bobAuthRepo.register("bob").getOrThrow()

        // Wait for coroutines in AuthRepository to finish loading initial states
        kotlinx.coroutines.yield()

        val aliceId =
            aliceAuthRepo.authState.value.let { (it as com.ruirui.findme.repository.AuthState.Authenticated).userId }
        val bobId =
            bobAuthRepo.authState.value.let { (it as com.ruirui.findme.repository.AuthState.Authenticated).userId }

        // We no longer need to re-create clients, since HttpClientFactory's Auth plugin dynamically injects the tokens!
        val aliceFriendRepoReal = aliceFriendRepo
        val aliceLocationRepoReal = aliceLocationRepo
        val bobFriendRepoReal = bobFriendRepo
        val bobLocationRepoReal = bobLocationRepo

        // 5. Friending Process
        aliceFriendRepoReal.sendFriendRequest("bob").getOrThrow()
        bobFriendRepoReal.syncFriends().getOrThrow()

        // Bob accepts Alice's request
        bobFriendRepoReal.acceptFriendRequest("alice", aliceId).getOrThrow()

        // Alice syncs to see Bob is a friend
        aliceFriendRepoReal.syncFriends().getOrThrow()

        // 6. Alice sends first location (Triggers X3DH)
        aliceLocationRepoReal.submitLocalLocation(lat = 12.34, lng = 56.78)

        // 7. Bob syncs his inbox
        bobLocationRepoReal.syncInbox()

        // 8. Assertions
        val bobMap = bobLocationRepoReal.friendLocations.value
        assertTrue(bobMap.containsKey(aliceId), "Bob should have Alice's location")

        val aliceLocation = bobMap[aliceId]
        assertNotNull(aliceLocation)
        assertEquals(12.34, aliceLocation.lat)
        assertEquals(56.78, aliceLocation.lng)
    }
}
