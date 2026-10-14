package com.ruirui.orbit.crypto

import com.ruirui.orbit.db.createTestDatabase
import com.ruirui.orbit.fakes.FakeBackend
import com.ruirui.orbit.models.IdentityKeyMismatchException
import com.ruirui.orbit.models.SignedPreKeyDto
import com.ruirui.orbit.models.UploadKeysRequest
import com.ruirui.orbit.network.HttpClientFactory
import com.ruirui.orbit.network.api.KeysApi
import com.ruirui.orbit.network.api.LocationApi
import com.ruirui.orbit.repository.LocationRepositoryImpl
import com.ruirui.orbit.storage.InMemorySecureStorage
import com.ruirui.orbit.storage.SecureStorageKeys
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityPinningTest {

    @Test
    fun testSenderDetectsMitmSwappedKey() = runTest {
        val backend = FakeBackend()
        val storage = InMemorySecureStorage()
        val crypto = CryptoKotlinAdapter()
        val db = createTestDatabase()

        // Local user setup
        val keyPairDh = crypto.generateX25519KeyPair()
        storage.putString(SecureStorageKeys.AUTH_TOKEN, "token_alice_id")
        storage.putString(SecureStorageKeys.USER_ID, "alice_id")
        storage.putString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_DH, Base64.encode(keyPairDh.privateKey))
        storage.putString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH, Base64.encode(keyPairDh.publicKey))

        val client = HttpClientFactory.create(backend.engine, storage, "http://localhost:8080")
        val preKeyManager = PreKeyManagerImpl(crypto, storage, KeysApi(client), db)
        val sessionStore = SessionStoreImpl(storage, crypto)

        val locationRepo = LocationRepositoryImpl(
            LocationApi(client),
            KeysApi(client),
            crypto,
            storage,
            sessionStore,
            preKeyManager,
            db,
            backgroundScope
        )

        // 1. Friend "bob_id" in database
        db.friendQueries.insertFriend("bob_id", "bob", getTimeMillis())

        // 2. Pre-seed DB with Bob's GENUINE pinned key
        val now = getTimeMillis()
        db.identityPinQueries.upsertPin(
            user_id = "bob_id",
            identity_key_dh = "GENUINE_BOB_DH_KEY",
            identity_key_sign = "GENUINE_BOB_SIGN_KEY",
            first_seen_at = now,
            last_verified_at = now,
            is_trusted = 1L
        )

        // 3. Fake backend returns an ATTACKER-SWAPPED key bundle
        backend.keysBackend.identityKeysDh["bob_id"] = "ATTACKER_SWAPPED_DH_KEY"
        backend.keysBackend.identityKeysSign["bob_id"] = "ATTACKER_SWAPPED_SIGN_KEY"
        backend.keysBackend.uploadKeys(
            "bob_id",
            UploadKeysRequest(
                signedPreKey = SignedPreKeyDto(1, Base64.encode(keyPairDh.publicKey), "sig")
            )
        )

        // 4. Attempting to send location to Bob must fail with IdentityKeyMismatchException
        val result = locationRepo.submitLocalLocation(12.34, 56.78)

        assertTrue(result.isFailure, "Location submission must fail when backend returns swapped key")
        val exception = result.exceptionOrNull()
        assertTrue(exception is IdentityKeyMismatchException, "Exception must be IdentityKeyMismatchException but was $exception")
        assertEquals("bob_id", exception.friendUserId)
        assertEquals("DH", exception.keyType)
        assertEquals("GENUINE_BOB_DH_KEY", exception.pinnedKeyBase64)
        assertEquals("ATTACKER_SWAPPED_DH_KEY", exception.receivedKeyBase64)
    }
}
