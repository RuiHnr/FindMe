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
