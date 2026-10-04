package com.ruirui.findme.fakes

import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.repository.FriendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeFriendRepository : FriendRepository {

    private val _friends = MutableStateFlow<List<FriendDto>>(emptyList())
    override val friends: StateFlow<List<FriendDto>> = _friends

    private val _friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    override val friendRequests: StateFlow<List<FriendRequest>> = _friendRequests

    private val friendsToAdd = mutableListOf<FriendDto>()
    private val requestsToAdd = mutableListOf<FriendRequest>()

    fun addFriend(friend: FriendDto) {
        friendsToAdd.add(friend)
    }

    fun addFriendRequest(request: FriendRequest) {
        requestsToAdd.add(request)
    }

    override suspend fun syncFriends(): Result<Unit> {
        _friends.value = friendsToAdd.toList()
        _friendRequests.value = requestsToAdd.toList()
        return Result.success(Unit)
    }

    override suspend fun sendFriendRequest(targetUsername: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun acceptFriendRequest(
        requestId: String,
        friendId: String
    ): Result<Unit> {
        return Result.success(Unit)
    }
}