use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// location_inbox Data Entity
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SubmitLocationRequest {
    pub receiver_id: Uuid,
    pub encrypted_blob: String,
}

/// Response when submitting location(s) to the inbox.
/// Includes the number of accepted payloads.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct SubmitLocationResponse {
    pub accepted: usize,
}

/// Location data a user receives in his/her inbox
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct InboxMessage {
    pub sender_id: Uuid,
    pub encrypted_payload: String,
}
