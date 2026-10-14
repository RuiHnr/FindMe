use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize, Debug, Clone, PartialEq, sqlx::FromRow)]
pub struct DeviceToken {
    pub fcm_token: Option<String>,
    pub apns_token: Option<String>,
}
