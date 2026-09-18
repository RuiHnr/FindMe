package com.ruirui.findme.repository

import com.ruirui.findme.crypto.Crypto
import com.ruirui.findme.crypto.initX3DH
import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.models.MessageHeader
import com.ruirui.findme.models.PreKeySignalMessage
import com.ruirui.findme.models.SubmitMessageRequest
import com.ruirui.findme.network.api.FriendsApi
import com.ruirui.findme.network.api.KeysApi
import com.ruirui.findme.network.api.LocationApi
import com.ruirui.findme.storage.SecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.Base64

interface FriendRepository {
    val friends: StateFlow<List<FriendDto>>
    val friendRequests: StateFlow<List<FriendRequest>>

    suspend fun syncFriends(): Result<Unit>
    suspend fun sendFriendRequest(targetUsername: String): Result<Unit>
    suspend fun acceptFriendRequest(requestId: String, friendId: String): Result<Unit>
}

class FriendRepositoryImpl(
    private val friendsApi: FriendsApi,
    private val keysApi: KeysApi,
    private val locationApi: LocationApi,
    private val crypto: Crypto,
    private val secureStorage: SecureStorage
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
        // 1. Accept the friend request
        friendsApi.accept(requestId)

        // 2. Initiate Cryptographic Session
        // Download friend's Prekey bundle to initialize the ratchet session
        val preKeyBundle = keysApi.getPreKeyBundle(friendId).getOrThrow()

        // Verify friend's signature is valid
        crypto.verify(
            preKeyBundle.signedPreKey.publicKey.let { Base64.decode(it) },
            preKeyBundle.identityKeySign.let { Base64.decode(it) },
            preKeyBundle.signedPreKey.signature.let { Base64.decode(it) }
        )

        // Fetch our own keys
        val identityPrivateKeyBase64 =
            secureStorage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_DH)
                ?: throw Exception("Identity private key not found")

        val identityPublicKeyBase64 =
            secureStorage.getString((SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH))
                ?: throw Exception("Identity public key not found")

        val baseKey = crypto.generateX25519KeyPair()

        // Perform X3DH and derive secret key
        val masterSecret = initX3DH(
            crypto,
            identityPrivateKeyBase64.let { Base64.decode(it) },
            baseKey.privateKey,
            preKeyBundle.identityKeyDh.let { Base64.decode(it) },
            preKeyBundle.signedPreKey.publicKey.let { Base64.decode(it) },
            preKeyBundle.oneTimePreKey?.publicKey?.let { Base64.decode(it) }
        )

        // Save the master secret
        secureStorage.putString(
            SecureStorageKeys.ratchetState(friendId),
            Base64.encode(masterSecret)
        )

        // Send our public keys to the friend in PreKeySignalMessage
        val preKeyMessage = PreKeySignalMessage(
            header = MessageHeader(
                aliceIdentityKeyDh = identityPublicKeyBase64,
                aliceBaseKeyDh = Base64.encode(baseKey.publicKey),
                bobSignedPreKeyId = preKeyBundle.signedPreKey.keyId,
                bobOneTimePreKeyId = preKeyBundle.oneTimePreKey?.keyId
            ),
            ciphertext = "" // TODO: The first encrypted location payload goes here eventually
        )

        locationApi.submitMessage(
            SubmitMessageRequest(
                receiverId = friendId,
                // We must serialize the object to a JSON string, NOT .toString()!
                encryptedBlob = kotlinx.serialization.json.Json.encodeToString(
                    PreKeySignalMessage.serializer(),
                    preKeyMessage
                )
            )
        ).getOrThrow()

        // 3. Update UI state
        syncFriends()
    }


}