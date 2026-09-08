package com.ruirui.findme.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelSerializationTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    @Test
    fun testRegisterRequestSerialization() {
        val request = RegisterRequest(username = "alice", pubKey = "test_key_base64")
        val jsonString = json.encodeToString(request)
        
        // Assert field name serialization
        assertEquals("""{"username":"alice","pub_key":"test_key_base64"}""", jsonString)

        val parsed = json.decodeFromString<RegisterRequest>(jsonString)
        assertEquals(request, parsed)
    }

    @Test
    fun testPreKeyBundleResponseDeserialization() {
        val rawJson = """
            {
                "user_id": "123e4567-e89b-12d3-a456-426614174000",
                "identity_key": "alice_id_key",
                "signed_prekey": {
                    "key_id": 1,
                    "public_key": "alice_spk_key",
                    "signature": "alice_spk_sig"
                },
                "one_time_prekey": {
                    "key_id": 101,
                    "public_key": "alice_otpk_key"
                }
            }
        """.trimIndent()

        val bundle = json.decodeFromString<PreKeyBundleResponse>(rawJson)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", bundle.userId)
        assertEquals("alice_id_key", bundle.identityKey)
        assertEquals(1, bundle.signedPreKey.keyId)
        assertNotNull(bundle.oneTimePreKey)
        assertEquals(101, bundle.oneTimePreKey.keyId)
    }

    @Test
    fun testPreKeyBundleResponseWithoutOneTimePreKey() {
        val rawJson = """
            {
                "user_id": "123e4567-e89b-12d3-a456-426614174000",
                "identity_key": "alice_id_key",
                "signed_prekey": {
                    "key_id": 1,
                    "public_key": "alice_spk_key",
                    "signature": "alice_spk_sig"
                },
                "one_time_prekey": null
            }
        """.trimIndent()

        val bundle = json.decodeFromString<PreKeyBundleResponse>(rawJson)
        assertNull(bundle.oneTimePreKey)
    }
}
