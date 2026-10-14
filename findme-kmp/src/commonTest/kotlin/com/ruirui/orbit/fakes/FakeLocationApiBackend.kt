package com.ruirui.orbit.fakes

import com.ruirui.orbit.models.InboxMessage
import com.ruirui.orbit.models.SubmitMessageRequest

class FakeLocationApiBackend {
    
    // Maps receiverId -> List of pending InboxMessages
    private val inboxes = mutableMapOf<String, MutableList<InboxMessage>>()
    
    fun submitMessage(senderId: String, request: SubmitMessageRequest) {
        val list = inboxes.getOrPut(request.receiverId) { mutableListOf() }
        val envelope = kotlinx.serialization.json.Json.decodeFromString(
            com.ruirui.orbit.models.SignalMessageEnvelope.serializer(),
            request.encryptedBlob
        )
        list.add(InboxMessage(senderId, envelope))
    }
    
    fun getInbox(receiverId: String): List<InboxMessage> {
        val messages = inboxes[receiverId]?.toList() ?: emptyList()
        inboxes[receiverId]?.clear()
        return messages
    }
}
