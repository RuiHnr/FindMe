package com.ruirui.findme.fakes

import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.FriendRequest

class FakeFriendsApiBackend {
    // Maps userId -> List of friends
    private val friends = mutableMapOf<String, MutableList<FriendDto>>()

    // Maps userId -> List of incoming requests
    private val requests = mutableMapOf<String, MutableList<FriendRequest>>()

    fun getFriends(userId: String): List<FriendDto> = friends[userId]?.toList() ?: emptyList()

    fun getFriendRequests(userId: String): List<FriendRequest> =
        requests[userId]?.toList() ?: emptyList()

    fun requestFriend(senderUsername: String, targetUsername: String) {
        // In a real backend, we'd look up the targetId by username.
        // For testing, we assume targetUsername == targetId
        val targetId = targetUsername
        val list = requests.getOrPut(targetId) { mutableListOf() }
        list.add(FriendRequest(senderUsername))
    }

    fun acceptFriend(userId: String, requestId: String) {
        val senderId = requestId

        // Add to user's friends
        friends.getOrPut(userId) { mutableListOf() }.add(FriendDto(senderId))

        // Add to sender's friends
        friends.getOrPut(senderId) { mutableListOf() }.add(FriendDto(userId))

        // Remove request
        requests[userId]?.removeAll { it.targetUsername == requestId }
    }
}
