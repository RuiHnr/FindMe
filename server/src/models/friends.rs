use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Payload sent by a user to initiate a friend request by username.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct FriendRequest {
    pub target_username: String,
}

/// Payload sent by a user to accept a specific friend request.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct FriendAcceptPayload {
    pub requester_id: Uuid,
}

/// DTO representing a friend
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct FriendDto {
    pub user_id: Uuid,
    pub username: String,
    // Sending the static public keys here so the client can cache
    // them and doesn't need to make a separate API call just to verify identity
    pub identity_key_dh: String,
    pub identity_key_sign: String,
}
