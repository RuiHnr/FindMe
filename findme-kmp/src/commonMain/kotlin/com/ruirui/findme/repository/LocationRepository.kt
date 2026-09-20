package com.ruirui.findme.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ruirui.findme.crypto.Crypto
import com.ruirui.findme.crypto.DoubleRatchetSession
import com.ruirui.findme.crypto.KeyPair
import com.ruirui.findme.crypto.PreKeyManager
import com.ruirui.findme.crypto.SessionStore
import com.ruirui.findme.crypto.initX3DH
import com.ruirui.findme.crypto.receiveX3DH
import com.ruirui.findme.db.FindMeDatabase
import com.ruirui.findme.models.FriendDto
import com.ruirui.findme.models.LocationPayload
import com.ruirui.findme.models.MessageHeader
import com.ruirui.findme.models.NormalSignalEnvelope
import com.ruirui.findme.models.PreKeySignalEnvelope
import com.ruirui.findme.models.SignalMessageEnvelope
import com.ruirui.findme.models.SubmitMessageRequest
import com.ruirui.findme.network.api.KeysApi
import com.ruirui.findme.network.api.LocationApi
import com.ruirui.findme.storage.SecureStorage
import com.ruirui.findme.storage.SecureStorageKeys
import io.ktor.util.date.getTimeMillis
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Handles the core cryptographic message exchange and location data flow.
 * Responsible for running the X3DH handshake, Double Ratchet encryption/decryption,
 * and maintaining the reactive state of friends' locations.
 */
interface LocationRepository {
    /**
     * A real-time reactive map representing the latest known coordinates of all friends.
     * The map key is the friend's user UUID, and the value is the decrypted [LocationPayload].
     */
    val friendLocations: StateFlow<Map<String, LocationPayload>>

    /**
     * Serializes, encrypts, and uploads the user's current location to all friends.
     * If a cryptographic session doesn't exist for a friend, it fetches their prekeys, 
     * performs the X3DH handshake, and prepends the new session headers (PreKeySignalMessage).
     */
    suspend fun submitLocalLocation(lat: Double, lng: Double): Result<Unit>

    /**
     * Fetches all queued encrypted messages from the server's inbox, decrypts them 
     * using the Double Ratchet (and initializes sessions via X3DH if necessary), 
     * updates the [friendLocations] state flow, and deletes the messages from the server.
     */
    suspend fun syncInbox(): Result<Unit>

    suspend fun getLocationHistoryForFriend(
        friendId: String,
        limit: Long = 50
    ): Result<List<LocationPayload>>
}

class LocationRepositoryImpl(
    private val locationApi: LocationApi,
    private val keysApi: KeysApi,
    private val crypto: Crypto,
    private val secureStorage: SecureStorage,
    private val sessionStore: SessionStore,
    private val preKeyManager: PreKeyManager,
    private val db: FindMeDatabase,
    scope: CoroutineScope
) : LocationRepository {

    override val friendLocations = db.locationQueries.getLatestFriendsLocations()
        .asFlow()
        .mapToList(scope.coroutineContext)
        .map { rows ->
            rows.associate {
                it.friend_user_id to LocationPayload(
                    it.lat,
                    it.lng,
                    it.timestamp
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun submitLocalLocation(lat: Double, lng: Double): Result<Unit> = runCatching {
        val timestamp = getTimeMillis()
        val jsonPayload = Json.encodeToString(
            LocationPayload(lat, lng, timestamp)
        )

        val friends = db.friendQueries.getAllFriends().executeAsList()
            .map { FriendDto(it.user_id, it.username) }

        coroutineScope {
            // Process all friends concurrently
            friends.map { friend ->
                async {
                    val envelope: SignalMessageEnvelope
                    // 1. Load session for this friend
                    var session = sessionStore.loadSession(friend.userId)

                    if (session == null) {
                        // Lazy initialization: We have never messaged this friend before.

                        // 1a. Fetch friend's PreKey Bundle
                        val preKeyBundle = keysApi.getPreKeyBundle(friend.userId).getOrThrow()

                        // 1b. Verify signature
                        if (!crypto.verify(
                                Base64.decode(preKeyBundle.identityKeySign),
                                Base64.decode(preKeyBundle.signedPreKey.publicKey),
                                Base64.decode(preKeyBundle.signedPreKey.signature)
                            )
                        ) {
                            throw Exception("Invalid signature from ${friend.userId}")
                        }

                        // 1c. Fetch our own keys
                        val identityPrivateKeyBase64 =
                            secureStorage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_DH)
                                ?: throw Exception("Identity private key not found")
                        val identityPublicKeyBase64 =
                            secureStorage.getString(SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH)
                                ?: throw Exception("Identity public key not found")

                        val baseKey = crypto.generateX25519KeyPair()

                        // 1d. Perform X3DH
                        val sharedSecret = initX3DH(
                            crypto = crypto,
                            aliceIdentityPrivateKey = Base64.decode(identityPrivateKeyBase64),
                            aliceBasePrivateKey = baseKey.privateKey,
                            bobIdentityPublicKey = Base64.decode(preKeyBundle.identityKeyDh),
                            bobSignedPreKeyPublic = Base64.decode(preKeyBundle.signedPreKey.publicKey),
                            bobOneTimePreKeyPublic = preKeyBundle.oneTimePreKey?.publicKey?.let {
                                Base64.decode(
                                    it
                                )
                            }
                        )

                        // 1e. Initialize Session
                        session = DoubleRatchetSession.initAlice(
                            sharedSecret = sharedSecret,
                            bobSignedPreKeyPub = Base64.decode(preKeyBundle.signedPreKey.publicKey),
                            crypto = crypto
                        )

                        // 1f. Encrypt the location payload using the NEW session
                        val encrypted = session.encrypt(jsonPayload.toByteArray())

                        // 1g. Create the PreKeySignalEnvelope
                        envelope = PreKeySignalEnvelope(
                            header = MessageHeader(
                                aliceIdentityKeyDh = identityPublicKeyBase64,
                                aliceBaseKeyDh = Base64.encode(baseKey.publicKey),
                                bobSignedPreKeyId = preKeyBundle.signedPreKey.keyId,
                                bobOneTimePreKeyId = preKeyBundle.oneTimePreKey?.keyId
                            ),
                            ciphertext = encrypted
                        )
                    } else {
                        // Session already exists. Encrypt normally.
                        val encrypted = session.encrypt(jsonPayload.toByteArray())
                        envelope = NormalSignalEnvelope(encrypted)
                    }

                    // 2. Save the advanced state (whether new or existing)
                    sessionStore.saveSession(friend.userId, session)

                    // 3. Send to backend
                    locationApi.submitMessage(
                        SubmitMessageRequest(
                            receiverId = friend.userId,
                            // Use the sealed class serializer!
                            encryptedBlob = Json.encodeToString(envelope)
                        )
                    ).getOrThrow()
                }
            }.awaitAll()
        }

        // Save own location to DB
        db.locationQueries.insertOwnLocation(lat, lng, timestamp)
    }

    override suspend fun syncInbox(): Result<Unit> = runCatching {
        val inboxResponse = locationApi.getInbox().getOrThrow()
        val inboxMessages = inboxResponse.messages

        inboxMessages.forEach { message ->
            val senderId = message.senderId
            val envelope = message.encryptedPayload

            // 1. Load existing session OR initialize a new one if missing
            val session = sessionStore.loadSession(senderId) ?: when (envelope) {
                is NormalSignalEnvelope -> throw Exception("Cannot decrypt normal message: No session exists for $senderId")

                is PreKeySignalEnvelope -> {
                    val header = envelope.header

                    // Fetch our own keys (Bob's perspective)
                    val bobIdentityPrivateKey =
                        secureStorage.getString(SecureStorageKeys.IDENTITY_PRIVATE_KEY_DH)
                            ?.let { Base64.decode(it) }
                            ?: throw Exception("Identity private key not found")

                    val bobSignedPreKeyPrivate =
                        secureStorage.getString(SecureStorageKeys.signedPreKeyPrivate(header.bobSignedPreKeyId))
                            ?.let { Base64.decode(it) }
                            ?: throw Exception("Signed pre key private not found")

                    val bobSignedPreKeyPublic =
                        secureStorage.getString(SecureStorageKeys.signedPreKeyPublic(header.bobSignedPreKeyId))
                            ?.let { Base64.decode(it) }
                            ?: throw Exception("Signed pre key public not found")

                    val bobOneTimePreKeyPrivate = header.bobOneTimePreKeyId?.let { oneTimeId ->
                        val key = db.oneTimePreKeyQueries.getPrivateKey(oneTimeId.toLong())
                            .executeAsOneOrNull()
                            ?.let { Base64.decode(it) }
                            ?: throw Exception("One-time pre key with ID $oneTimeId private not found")
                        // Permanently delete the one-time prekey immediately after use for Perfect Forward Secrecy
                        db.oneTimePreKeyQueries.deleteKey(oneTimeId.toLong())
                        key
                    }

                    // Perform X3DH
                    val sharedSecret = receiveX3DH(
                        crypto = crypto,
                        bobIdentityPrivateKey = bobIdentityPrivateKey,
                        bobSignedPreKeyPrivate = bobSignedPreKeyPrivate,
                        bobOneTimePreKeyPrivate = bobOneTimePreKeyPrivate,
                        aliceIdentityPublicKey = Base64.decode(header.aliceIdentityKeyDh),
                        aliceBasePublicKey = Base64.decode(header.aliceBaseKeyDh)
                    )

                    // Check for identity pin mismatch
                    val existingPin =
                        db.identityPinQueries.getPin(senderId).executeAsOneOrNull()
                    if (existingPin != null && existingPin.identity_key_dh != header.aliceIdentityKeyDh) {
                        // IDENTITY KEY CHANGED! Possible MITM or device reset.
                        // Signal shows a "Safety Number changed" warning here.
                        throw Exception("Identity key mismatch for $senderId! Expected ${existingPin.identity_key_dh}")
                    }
                    // Save the new pin
                    db.identityPinQueries.upsertPin(
                        user_id = senderId,
                        identity_key_dh = header.aliceIdentityKeyDh,
                        first_seen_at = existingPin?.first_seen_at ?: getTimeMillis(),
                        last_verified_at = getTimeMillis()
                    )

                    // Initialize and return the session
                    DoubleRatchetSession.initBob(
                        sharedSecret = sharedSecret,
                        bobRatchetKeyPair = KeyPair(
                            bobSignedPreKeyPublic,
                            bobSignedPreKeyPrivate
                        ),
                        crypto = crypto
                    )
                }
            }

            // 2. Decrypt message using Ratchet
            val encrypted = envelope.ciphertext

            val decryptedBytes = session.decrypt(encrypted)
            val jsonString = decryptedBytes.decodeToString()

            // 3. Save the advanced state
            sessionStore.saveSession(senderId, session)

            // 4. Parse coordinates, save to DB (which updates UI)
            val location: LocationPayload = Json.decodeFromString(jsonString)

            db.locationQueries.insertLocationForFriend(
                friend_user_id = senderId,
                lat = location.lat,
                lng = location.lng,
                timestamp = location.timestamp,
                received_at = getTimeMillis()
            )
        }

        // 5. Check and replenish One-Time PreKeys if they were consumed
        val remainingPreKeys = inboxResponse.remainingPreKeys
        if (remainingPreKeys != null && remainingPreKeys < PreKeyManager.OTPK_REPLENISH_THRESHOLD) {
            preKeyManager.replenishOneTimePreKeysIfNeeded()
        } else if (remainingPreKeys == null && inboxMessages.isNotEmpty()) {
            // Fallback for mock backend or older server versions
            preKeyManager.replenishOneTimePreKeysIfNeeded()
        }
    }

    override suspend fun getLocationHistoryForFriend(
        friendId: String,
        limit: Long
    ): Result<List<LocationPayload>> = runCatching {
        db.locationQueries.getLocationHistoryForFriend(friendId, limit)
            .executeAsList()
            .map { LocationPayload(it.lat, it.lng, it.timestamp) }
    }
}