use serde::Serialize;

#[derive(Serialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct DeviceToken {
    pub fcm_token: Option<String>,
    pub apns_token: Option<String>,
}
