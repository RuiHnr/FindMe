package com.ruirui.orbit.models

/**
 * Thrown when an incoming or fetched Identity Key differs from the pinned key in local storage.
 * Indicates a potential Man-In-The-Middle (MITM) attack or a friend device reinstall.
 */
class IdentityKeyMismatchException(
    val friendUserId: String,
    val keyType: String, // "DH" or "SIGN"
    val pinnedKeyBase64: String,
    val receivedKeyBase64: String
) : Exception(
    "Identity key mismatch for $friendUserId ($keyType)! " +
    "Pinned: $pinnedKeyBase64, Received: $receivedKeyBase64"
)

/**
 * Represents the identity verification state of a friend for UI consumption.
 */
data class IdentityPinInfo(
    val userId: String,
    val identityKeyDhBase64: String,
    val identityKeySignBase64: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
    val isTrusted: Boolean
)
