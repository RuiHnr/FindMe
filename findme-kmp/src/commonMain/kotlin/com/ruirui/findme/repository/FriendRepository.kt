package com.ruirui.findme.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ruirui.findme.db.FindMeDatabase
import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.models.RequestDirection
import com.ruirui.findme.network.api.FriendsApi
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Manages the user's social graph, handling fetching friends, sending requests,
 * and accepting inbound requests.
 */
interface FriendRepository {
    /**
     * A real-time reactive flow of the user's accepted friends.
     */
    val friends: StateFlow<List<FriendDto>>

    /**
     * A real-time reactive flow of the user's pending inbound friend requests.
     */
    val friendRequests: StateFlow<List<FriendRequest>>

    /**
     * Fetches the latest friends list and pending requests from the backend
     * and updates the local state flows.
     */
    suspend fun syncFriends(): Result<Unit>

    /**
     * Submits an outbound friend request to another user by their exact username.
     */
    suspend fun sendFriendRequest(targetUsername: String): Result<Unit>

    /**
     * Accepts a pending inbound friend request.
     * Note: Cryptographic sessions are initialized lazily upon sending the first location,
     * not immediately upon acceptance.
     */
    suspend fun acceptFriendRequest(requestId: String, friendId: String): Result<Unit>
}

class FriendRepositoryImpl(
    private val friendsApi: FriendsApi,
    private val db: FindMeDatabase,
    scope: CoroutineScope
) : FriendRepository {

    override val friends: StateFlow<List<FriendDto>> =
        db.friendQueries.getAllFriends()
            .asFlow()
            .mapToList(scope.coroutineContext)
            .map { rows -> rows.map { FriendDto(it.user_id, it.username) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val friendRequests: StateFlow<List<FriendRequest>> =
        db.friendQueries.getAllFriendRequests()
            .asFlow()
            .mapToList(scope.coroutineContext)
            .map { rows ->
                rows.map {
                    val direction = if (it.direction.equals("outbound", ignoreCase = true)) {
                        RequestDirection.OUTBOUND
                    } else {
                        RequestDirection.INBOUND
                    }
                    FriendRequest(it.target_username, direction)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun syncFriends(): Result<Unit> = runCatching {
        val friendsList = friendsApi.getFriends().getOrThrow()
        val requestsList = friendsApi.getFriendRequests().getOrThrow()

        db.transaction {
            db.friendQueries.deleteAllFriends()
            friendsList.forEach { friend ->
                db.friendQueries.insertFriend(
                    user_id = friend.userId,
                    username = friend.username.ifEmpty { friend.userId },
                    added_at = getTimeMillis()
                )
            }
            db.friendQueries.deleteAllFriendRequests()
            requestsList.forEach { request ->
                db.friendQueries.insertFriendRequest(
                    target_username = request.targetUsername,
                    direction = request.direction.name.lowercase(),
                    created_at = getTimeMillis()
                )
            }
        }
    }

    override suspend fun sendFriendRequest(targetUsername: String): Result<Unit> = runCatching {
        friendsApi.request(FriendRequest(targetUsername, RequestDirection.OUTBOUND)).getOrThrow()
        syncFriends()
    }

    override suspend fun acceptFriendRequest(
        requestId: String,
        friendId: String
    ): Result<Unit> = runCatching {
        // 1. Accept the friend request on the backend
        friendsApi.accept(requestId).getOrThrow()

        // 2. Update UI state
        syncFriends()
    }
}