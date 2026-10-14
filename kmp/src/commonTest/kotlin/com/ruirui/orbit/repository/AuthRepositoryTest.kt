package com.ruirui.orbit.repository

import com.ruirui.orbit.crypto.CryptoKotlinAdapter
import com.ruirui.orbit.fakes.FakeBackend
import com.ruirui.orbit.network.api.AuthApi
import com.ruirui.orbit.network.api.KeysApi
import com.ruirui.orbit.storage.InMemorySecureStorage
import com.ruirui.orbit.storage.SecureStorageKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthRepositoryTest {

    @Test
    fun testRegistrationGeneratesAndUploadsKeys() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        val client = com.ruirui.orbit.network.HttpClientFactory.create(backend.engine, storage, "http://localhost:8080")
        val crypto = CryptoKotlinAdapter()
        val db = com.ruirui.orbit.db.createTestDatabase()

        val preKeyManager = com.ruirui.orbit.crypto.PreKeyManagerImpl(crypto, storage, KeysApi(client), db)
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
