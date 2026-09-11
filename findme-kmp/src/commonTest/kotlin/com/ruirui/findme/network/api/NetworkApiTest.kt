package com.ruirui.findme.network.api

import com.ruirui.findme.models.RegisterRequest
import com.ruirui.findme.models.SubmitLocationRequest
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
        secureStorage.putString("jwt_token", "test_mock_token_123")

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

        val result = authApi.register(RegisterRequest("testUser", "pubKey123"))

        assertTrue(result.isSuccess)
        assertEquals("test-uuid-001", result.getOrNull()?.userId)
    }

    @Test
    fun testLocationApiSubmitAndGet() = runTest {
        secureStorage.putString("jwt_token", "test_mock_token_123")

        val mockEngine = MockEngine { request ->
            // Verify JWT Token was automatically injected
            assertEquals("Bearer test_mock_token_123", request.headers[HttpHeaders.Authorization])

            when (request.url.encodedPath) {
                "/inbox" -> {
                    assertEquals("POST", request.method.value)
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                "/inbox/friend-123" -> {
                    assertEquals("GET", request.method.value)
                    respond(
                        content = """[{"sender_id": "alice", "encrypted_payload": "secretData"}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                else -> error("Unhandled request: ${request.url.encodedPath}")
            }
        }

        val client = HttpClientFactory.create(mockEngine, secureStorage, baseUrl)
        val locationApi = LocationApi(client)

        // Test Submit
        val submitResult =
            locationApi.submitLocation(SubmitLocationRequest("friend-123", "secretData"))
        assertTrue(submitResult.isSuccess)
        assertEquals(HttpStatusCode.OK, submitResult.getOrNull())

        // Test Get Inbox
        val inboxResult = locationApi.getInbox("friend-123")
        assertTrue(inboxResult.isSuccess)

        val inbox = inboxResult.getOrNull()
        assertEquals(1, inbox?.size)
        assertEquals("alice", inbox?.first()?.senderId)
    }

    @Test
    fun testKeysApiUploadAndGet() = runTest {
        secureStorage.putString("jwt_token", "test_mock_token_123")

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
