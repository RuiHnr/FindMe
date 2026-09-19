package com.ruirui.findme.repository

import com.ruirui.findme.crypto.CryptoKotlinAdapter
import com.ruirui.findme.fakes.FakeBackend
import com.ruirui.findme.network.api.AuthApi
import com.ruirui.findme.network.api.KeysApi
import com.ruirui.findme.storage.InMemorySecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertNotNull

class AuthRepositoryTest {

    @Test
    fun testRegistrationGeneratesAndUploadsKeys() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        val client = com.ruirui.findme.network.HttpClientFactory.create(backend.engine, storage, "http://localhost:8080")
        val crypto = CryptoKotlinAdapter()

        val preKeyManager = com.ruirui.findme.crypto.PreKeyManagerImpl(crypto, storage, KeysApi(client))
        val authRepo = AuthRepositoryImpl(
            AuthApi(client), preKeyManager, storage, crypto, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        )

        authRepo.register("testuser").getOrThrow()

        // Verify tokens saved
        assertNotNull(storage.getString(SecureStorageKeys.AUTH_TOKEN))
        assertNotNull(storage.getString(SecureStorageKeys.USER_ID))

        // Verify identity keys saved
        assertNotNull(storage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_DH))
        assertNotNull(storage.getString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH))
        assertNotNull(storage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_SIGN))
        assertNotNull(storage.getString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_SIGN))

        // Verify state is authenticated
        val state = authRepo.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertNotNull(state.userId)
    }
}
