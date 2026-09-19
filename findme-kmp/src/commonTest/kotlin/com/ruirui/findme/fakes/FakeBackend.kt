package com.ruirui.findme.fakes

import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.models.RegisterRequest
import com.ruirui.findme.models.RegisterResponse
import com.ruirui.findme.models.SubmitMessageRequest
import com.ruirui.findme.models.UploadKeysRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

/**
 * Simulates the backend server.
 * Returns an HttpClient built with Ktor's MockEngine.
 */
class FakeBackend {
    val locationBackend = FakeLocationApiBackend()
    val keysBackend = FakeKeysApiBackend()
    val friendsBackend = FakeFriendsApiBackend()

    // Mock Database for users
    private val users = mutableMapOf<String, String>() // username -> password
    private val userIds = mutableMapOf<String, String>() // username -> userId

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val method = request.method.value

        // Extract authenticatedUserId from token if available
        val authHeader = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        val dynamicUserId = authHeader?.removePrefix("token_") ?: "fake_uuid_fallback"
        // Wait, the MockEngine is defined outside createClient, so it doesn't have access to authenticatedUserId.
        // Actually, we can check a custom header or just let the caller set the token.

        val responseHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        when {
            // AUTH
            path == "/users/register" && method == "POST" -> {
                val bodyText = request.body.let {
                    if (it is io.ktor.http.content.OutgoingContent.ByteArrayContent) {
                        it.bytes().decodeToString()
                    } else {
                        it.toString()
                    }
                }
                val body = Json.decodeFromString<RegisterRequest>(bodyText)
                val userId = "fake_uuid_" + body.username
                val fakeToken = "token_$userId"
                users[body.username] = "" // fallback
                userIds[body.username] = userId
                keysBackend.identityKeysDh[userId] = body.identityKeyDh
                keysBackend.identityKeysSign[userId] = body.identityKeySign

                respond(
                    Json.encodeToString(RegisterResponse(userId, fakeToken)),
                    HttpStatusCode.OK,
                    responseHeaders
                )
            }

            // KEYS
            path == "/keys" && method == "POST" -> {
                val bodyText = request.body.let {
                    if (it is io.ktor.http.content.OutgoingContent.ByteArrayContent) {
                        it.bytes().decodeToString()
                    } else {
                        it.toString()
                    }
                }
                val body = Json.decodeFromString<UploadKeysRequest>(bodyText)
                keysBackend.uploadKeys(dynamicUserId, body)
                respond("", HttpStatusCode.OK)
            }

            path.startsWith("/keys/") && method == "GET" -> {
                val targetId = path.substringAfterLast("/")
                val bundle = keysBackend.getPreKeyBundle(targetId)
                respond(Json.encodeToString(bundle), HttpStatusCode.OK, responseHeaders)
            }

            // FRIENDS
            path == "/friends" && method == "GET" -> {
                val friendsList = friendsBackend.getFriends(dynamicUserId)
                respond(Json.encodeToString(friendsList), HttpStatusCode.OK, responseHeaders)
            }

            path == "/friends/requests" && method == "GET" -> {
                val requests = friendsBackend.getFriendRequests(dynamicUserId)
                respond(Json.encodeToString(requests), HttpStatusCode.OK, responseHeaders)
            }

            path == "/friends/requests" && method == "POST" -> {
                val bodyText = request.body.let {
                    if (it is io.ktor.http.content.OutgoingContent.ByteArrayContent) {
                        it.bytes().decodeToString()
                    } else {
                        it.toString()
                    }
                }
                val body = Json.decodeFromString<FriendRequest>(bodyText)
                val targetId = userIds[body.targetUsername] ?: body.targetUsername
                friendsBackend.requestFriend(dynamicUserId, targetId)
                respond("", HttpStatusCode.OK)
            }

            path.startsWith("/friends/requests/") && path.endsWith("/accept") && method == "PUT" -> {
                val requestId = path.removePrefix("/friends/requests/").removeSuffix("/accept")
                val realRequestId = userIds[requestId] ?: requestId
                friendsBackend.acceptFriend(dynamicUserId, realRequestId)
                respond("", HttpStatusCode.OK)
            }

            // LOCATION / INBOX
            path == "/inbox" && method == "POST" -> {
                val bodyText = request.body.let {
                    if (it is io.ktor.http.content.OutgoingContent.ByteArrayContent) {
                        it.bytes().decodeToString()
                    } else {
                        it.toString()
                    }
                }
                val body = Json.decodeFromString<SubmitMessageRequest>(bodyText)
                locationBackend.submitMessage(dynamicUserId, body)
                respond("", HttpStatusCode.OK)
            }

            path == "/inbox" && method == "GET" -> {
                val messages = locationBackend.getInbox(dynamicUserId)
                respond(Json.encodeToString(messages), HttpStatusCode.OK, responseHeaders)
            }

            else -> error("Unhandled mock request: $method ${request.url} ${request.url.encodedPath} body=${request.body::class}")
        }
    }
}
