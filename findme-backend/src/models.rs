use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// location_inbox Data Entity
#[derive(Deserialize, Debug)]
pub struct LocationPayload {
    pub sender_id: Uuid,
    pub receiver_id: Uuid,
    pub encrypted_blob: String
}
/// Location data a user receives in his/her inbox
#[derive(Serialize, sqlx::FromRow)]
pub struct InboxMessage {
    pub sender_id: Uuid,
    pub encrypted_payload: String
}

/// Register Request sent from user to Server
#[derive(Deserialize, Debug)]
pub struct RegisterRequest {
    pub username: String,
    pub pub_key: String
}

/// The Register Response sent from the server
#[derive(Serialize, Debug)]
pub struct RegisterResponse {
    pub user_id: Uuid
}