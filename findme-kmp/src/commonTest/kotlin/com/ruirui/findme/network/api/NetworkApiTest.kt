package com.ruirui.findme.network.api

import com.ruirui.findme.models.RegisterRequest
import com.ruirui.findme.models.SubmitMessageRequest
import com.ruirui.findme.network.HttpClientFactory
import com.ruirui.findme.storage.InMemorySecureStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkApiTest {

    private val baseUrl = "https://api.findme.test"
    private val secureStorage = InMemorySecureStorage()

    @Test
    fun testAuthApiRegister() = runTest {
        secureStorage.putString(com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN, "test_mock_token_123")

        val mockEngine = MockEngine { request ->
            assertEquals("/users/register", request.url.encodedPath)
            assertEquals("POST", request.method.value)

            // Return a valid JSON response matching RegisterResponse
            respond(
                content = """{"user_id": "test-uuid-001", "token": "mock_jwt_token_xyz"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClientFactory.create(mockEngine, secureStorage, baseUrl)
        val authApi = AuthApi(client)

        val result = authApi.register(RegisterRequest("testUser", "pubKey123_dh", "pubKey123_sign"))

        assertTrue(result.isSuccess)
        assertEquals("test-uuid-001", result.getOrNull()?.userId)
    }

    @Test
    fun testLocationApiSubmitAndGet() = runTest {
        secureStorage.putString(com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN, "test_mock_token_123")

        val mockEngine = MockEngine { request ->
            // Verify JWT Token was automatically injected
            assertEquals("Bearer test_mock_token_123", request.headers[HttpHeaders.Authorization])

            when (request.url.encodedPath) {
                "/inbox" -> {
                    if (request.method.value == "POST") {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf()
                        )
                    } else {
                        assertEquals("GET", request.method.value)
                        respond(
                            content = """[{"sender_id": "alice", "encrypted_payload": {"type": "normal_message", "ciphertext": {"ratchetKey": [1,2,3], "msgNumber": 0, "previousChainLength": 0, "ciphertext": [1,2,3]}}}]""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                "X-Remaining-PreKeys" to listOf("99")
                            )
                        )
                    }
                }

                else -> error("Unhandled request: ${request.url.encodedPath}")
            }
        }

        val client = HttpClientFactory.create(mockEngine, secureStorage, baseUrl)
        val locationApi = LocationApi(client)

        // Test Submit
        val submitResult =
            locationApi.submitMessage(SubmitMessageRequest("friend-123", "secretData"))
        assertTrue(submitResult.isSuccess, "submitMessage failed: ${submitResult.exceptionOrNull()?.message}")
        assertEquals(Unit, submitResult.getOrNull())

        // Test Get Inbox
        val inboxResult = locationApi.getInbox()
        assertTrue(inboxResult.isSuccess)

        val inbox = inboxResult.getOrNull()
        assertEquals(1, inbox?.messages?.size)
        assertEquals("alice", inbox?.messages?.first()?.senderId)
        assertEquals(99, inbox?.remainingPreKeys)
    }

    @Test
    fun testKeysApiUploadAndGet() = runTest {
        secureStorage.putString(com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN, "test_mock_token_123")

        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/keys/count" -> {
                    assertEquals("GET", request.method.value)
                    respond(
                        content = """{"remaining_one_time_prekeys": 42}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                else -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }

        val client = HttpClientFactory.create(mockEngine, secureStorage, baseUrl)
        val keysApi = KeysApi(client)

        val countResult = keysApi.getKeyCount()
        assertTrue(countResult.isSuccess)
        assertEquals(42L, countResult.getOrNull()?.remainingOneTimePrekeys)
    }
}
