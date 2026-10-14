use serde::{Deserialize, Serialize};
use uuid::Uuid;

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
    pub identity_key_dh: String,
    pub identity_key_sign: String,
    pub signed_prekey: SignedPreKeyDto,
    pub one_time_prekey: Option<OneTimePreKeyDto>,
}

/// Status response for remaining one-time prekeys count
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct KeyCountResponse {
    pub remaining_one_time_prekeys: i64,
}
