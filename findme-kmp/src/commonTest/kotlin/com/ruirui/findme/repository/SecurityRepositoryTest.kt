package com.ruirui.findme.repository

import com.ruirui.findme.crypto.CryptoKotlinAdapter
import com.ruirui.findme.crypto.DoubleRatchetSession
import com.ruirui.findme.crypto.SessionStoreImpl
import com.ruirui.findme.db.createTestDatabase
import com.ruirui.findme.storage.InMemorySecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityRepositoryTest {

    @Test
    fun testGetIdentityPinWhenExists() = runTest {
        val db = createTestDatabase()
        val storage = InMemorySecureStorage()
        val crypto = CryptoKotlinAdapter()
        val sessionStore = SessionStoreImpl(storage, crypto)
        val securityRepo = SecurityRepositoryImpl(db, storage, sessionStore)

        val now = getTimeMillis()
        db.identityPinQueries.upsertPin(
            user_id = "bob_id",
            identity_key_dh = "bob_pub_dh",
            identity_key_sign = "bob_pub_sign",
            first_seen_at = now,
            last_verified_at = now,
            is_trusted = 1L
        )

        val pin = securityRepo.getIdentityPin("bob_id")
        assertNotNull(pin)
        assertEquals("bob_id", pin.userId)
        assertEquals("bob_pub_dh", pin.identityKeyDhBase64)
        assertEquals("bob_pub_sign", pin.identityKeySignBase64)
        assertTrue(pin.isTrusted)
    }

    @Test
    fun testGetIdentityPinWhenMissing() = runTest {
        val db = createTestDatabase()
        val storage = InMemorySecureStorage()
        val crypto = CryptoKotlinAdapter()
        val sessionStore = SessionStoreImpl(storage, crypto)
        val securityRepo = SecurityRepositoryImpl(db, storage, sessionStore)

        val pin = securityRepo.getIdentityPin("nonexistent_id")
        assertNull(pin)
    }

    @Test
    fun testGetSafetyNumber() = runTest {
        val db = createTestDatabase()
        val storage = InMemorySecureStorage()
        val crypto = CryptoKotlinAdapter()
        val sessionStore = SessionStoreImpl(storage, crypto)
        val securityRepo = SecurityRepositoryImpl(db, storage, sessionStore)

        // Store local user identity
        storage.putString(SecureStorageKeys.USER_ID, "alice_id")
        storage.putString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH, "alice_dh_pub_key")

        // Store friend identity pin
        val now = getTimeMillis()
        db.identityPinQueries.upsertPin(
            user_id = "bob_id",
            identity_key_dh = "bob_dh_pub_key",
            identity_key_sign = "bob_sign_pub_key",
            first_seen_at = now,
            last_verified_at = now,
            is_trusted = 1L
        )

        val result = securityRepo.getSafetyNumber("bob_id")
        assertTrue(result.isSuccess)
        val safetyNumber = result.getOrThrow()
        assertEquals(12, safetyNumber.split(" ").size)
    }

    @Test
    fun testTrustNewIdentityKeyOverwritesPinAndClearsSession() = runTest {
        val db = createTestDatabase()
        val storage = InMemorySecureStorage()
        val crypto = CryptoKotlinAdapter()
        val sessionStore = SessionStoreImpl(storage, crypto)
        val securityRepo = SecurityRepositoryImpl(db, storage, sessionStore)

        // 1. Initial pin
        val now = getTimeMillis()
        db.identityPinQueries.upsertPin(
            user_id = "bob_id",
            identity_key_dh = "old_dh_key",
            identity_key_sign = "old_sign_key",
            first_seen_at = now,
            last_verified_at = now,
            is_trusted = 1L
        )

        // 2. Save a dummy active ratchet session
        val dummySession = DoubleRatchetSession.initAlice(
            sharedSecret = ByteArray(32) { 1 },
            bobSignedPreKeyPub = crypto.generateX25519KeyPair().publicKey,
            crypto = crypto
        )
        sessionStore.saveSession("bob_id", dummySession)
        assertNotNull(sessionStore.loadSession("bob_id"))

        // 3. Re-trust new identity key following device reset
        val result = securityRepo.trustNewIdentityKey(
            friendId = "bob_id",
            newIdentityKeyDh = "new_dh_key",
            newIdentityKeySign = "new_sign_key"
        )
        assertTrue(result.isSuccess)

        // 4. Verify pin updated
        val updatedPin = db.identityPinQueries.getPin("bob_id").executeAsOneOrNull()
        assertNotNull(updatedPin)
        assertEquals("new_dh_key", updatedPin.identity_key_dh)
        assertEquals("new_sign_key", updatedPin.identity_key_sign)

        // 5. Verify old session state cleared
        assertNull(sessionStore.loadSession("bob_id"), "Session state should be deleted to force fresh X3DH")
    }
}
