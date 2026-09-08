use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// location_inbox Data Entity
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SubmitLocationRequest {
    pub receiver_id: Uuid,
    pub encrypted_blob: String,
}
/// Location data a user receives in his/her inbox
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct InboxMessage {
    pub sender_id: Uuid,
    pub encrypted_payload: String,
}

/// Register Request sent from user to Server
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct RegisterRequest {
    pub username: String,
    pub pub_key: String,
}

/// The Register Response sent from the server
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct RegisterResponse {
    pub user_id: Uuid,
    pub token: String,
}

/// Data payload inside JWT token
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct Claims {
    pub sub: Uuid,  // Subject: The user's UUID
    pub exp: usize, // Expiration time (Unix timestamp)
}

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

/// DTO representing a Signed PreKey in the Signal Protocol
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct SignedPreKeyDto {
    pub key_id: i32,
    pub public_key: String,
    pub signature: String,
}

/// DTO representing a One-Time PreKey in the Signal Protocol
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct OneTimePreKeyDto {
    pub key_id: i32,
    pub public_key: String,
}

/// Request payload for uploading PreKeys to the server
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct UploadKeysRequest {
    pub signed_prekey: Option<SignedPreKeyDto>,
    pub one_time_prekeys: Option<Vec<OneTimePreKeyDto>>,
}

/// PreKey bundle fetched by a client to initiate an X3DH session
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct PreKeyBundleResponse {
    pub user_id: Uuid,
    pub identity_key: String,
    pub signed_prekey: SignedPreKeyDto,
    pub one_time_prekey: Option<OneTimePreKeyDto>,
}

/// Status response for remaining one-time prekeys count
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct KeyCountResponse {
    pub remaining_one_time_prekeys: i64,
}