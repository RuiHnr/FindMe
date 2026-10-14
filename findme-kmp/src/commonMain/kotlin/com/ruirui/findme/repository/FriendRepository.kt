package com.ruirui.findme.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ruirui.findme.db.FindMeDatabase
import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.models.RequestDirection
import com.ruirui.findme.network.api.FriendsApi
import com.ruirui.findme.network.api.KeysApi
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
    val friendRequests: StateFlow<List<FriendDto>>

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
     * not immediately upon acceptance, but friend's identity pin is saved directly.
     */
    suspend fun acceptFriendRequest(friendId: String): Result<Unit>


}

class FriendRepositoryImpl(
    private val friendsApi: FriendsApi,
    private val keysApi: KeysApi,
    private val db: FindMeDatabase,
    scope: CoroutineScope
) : FriendRepository {

    override val friends: StateFlow<List<FriendDto>> =
        db.friendQueries.getAllFriends()
            .asFlow()
            .mapToList(scope.coroutineContext)
            .map { rows ->
                rows.map {
                    FriendDto(
                        userId = it.user_id,
                        username = it.username
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val friendRequests: StateFlow<List<FriendDto>> =
        db.friendQueries.getAllFriendRequests()
            .asFlow()
            .mapToList(scope.coroutineContext)
            .map { rows ->
                rows.map {
                    FriendDto(
                        userId = it.user_id,
                        username = it.username,
                        identityKeyDh = it.identity_key_dh,
                        identityKeySign = it.identity_key_sign
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun syncFriends(): Result<Unit> = runCatching {
        val friendsList = friendsApi.getFriends().getOrThrow()
        val requestsList = friendsApi.getFriendRequests().getOrThrow()

        val now = getTimeMillis()
        db.transaction {
            db.friendQueries.deleteAllFriends()
            friendsList.forEach { friend ->
                db.friendQueries.insertFriend(
                    user_id = friend.userId,
                    username = friend.username.ifEmpty { friend.userId },
                    added_at = now
                )
                if (friend.identityKeyDh.isNotEmpty()) {
                    db.identityPinQueries.upsertPin(
                        user_id = friend.userId,
                        identity_key_dh = friend.identityKeyDh,
                        identity_key_sign = friend.identityKeySign,
                        first_seen_at = now,
                        last_verified_at = now,
                        is_trusted = 1L
                    )
                }
            }
            db.friendQueries.deleteAllFriendRequests()
            requestsList.forEach { request ->
                db.friendQueries.insertFriendRequest(
                    userId = request.userId,
                    username = request.username.ifEmpty { request.userId },
                    identityKeyDh = request.identityKeyDh,
                    identityKeySign = request.identityKeySign,
                    direction = "inbound",
                    createdAt = now
                )
                if (request.identityKeyDh.isNotEmpty()) {
                    db.identityPinQueries.upsertPin(
                        user_id = request.userId,
                        identity_key_dh = request.identityKeyDh,
                        identity_key_sign = request.identityKeySign,
                        first_seen_at = now,
                        last_verified_at = now,
                        is_trusted = 1L
                    )
                }
            }
        }
    }

    override suspend fun sendFriendRequest(targetUsername: String): Result<Unit> = runCatching {
        friendsApi.request(FriendRequest(targetUsername, RequestDirection.OUTBOUND)).getOrThrow()
        syncFriends()
    }

    override suspend fun acceptFriendRequest(
        friendId: String
    ): Result<Unit> = runCatching {
        // 1. Accept the friend request on the backend
        friendsApi.accept(friendId).getOrThrow()

        // 2. Pin Identity
        val preKeyBundle = keysApi.getPreKeyBundle(friendId).getOrThrow()
        val now = getTimeMillis()
        db.identityPinQueries.upsertPin(
            user_id = friendId,
            identity_key_dh = preKeyBundle.identityKeyDh,
            identity_key_sign = preKeyBundle.identityKeySign,
            first_seen_at = now,
            last_verified_at = now,
            is_trusted = 1L
        )

        // 3. Sync local state
        syncFriends()
    }
}