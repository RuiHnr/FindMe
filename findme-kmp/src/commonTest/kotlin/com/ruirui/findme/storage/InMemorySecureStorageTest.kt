package com.ruirui.findme.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemorySecureStorageTest {

    @Test
    fun testPutAndGetString() = runTest {
        val storage = InMemorySecureStorage()

        assertNull(storage.getString("token"))

        storage.putString("token", "secret_jwt_value")
        assertEquals("secret_jwt_value", storage.getString("token"))
    }

    @Test
    fun testOverwriteString() = runTest {
        val storage = InMemorySecureStorage()

        storage.putString("key", "first_value")
        assertEquals("first_value", storage.getString("key"))

        storage.putString("key", "second_value")
        assertEquals("second_value", storage.getString("key"))
    }

    @Test
    fun testRemoveKey() = runTest {
        val storage = InMemorySecureStorage()

        storage.putString("key", "to_delete")
        assertEquals("to_delete", storage.getString("key"))

        storage.remove("key")
        assertNull(storage.getString("key"))
    }
}
