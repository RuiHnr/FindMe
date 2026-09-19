package com.ruirui.findme.repository

import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.network.api.FriendsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) : FriendRepository {

    private val _friends = MutableStateFlow<List<FriendDto>>(emptyList())
    override val friends = _friends.asStateFlow()

    private val _friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    override val friendRequests = _friendRequests.asStateFlow()

    override suspend fun syncFriends(): Result<Unit> = runCatching {
        // Fetch from API
        val friendsList = friendsApi.getFriends().getOrThrow()
        val requestsList = friendsApi.getFriendRequests().getOrThrow()

        _friends.value = friendsList
        _friendRequests.value = requestsList
    }

    override suspend fun sendFriendRequest(targetUsername: String): Result<Unit> = runCatching {
        friendsApi.request(FriendRequest(targetUsername)).getOrThrow()
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