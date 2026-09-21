use serde::{Deserialize, Serialize};

/// Push Notification sent to client,
/// telling them how many active watchers they have
#[derive(Debug, Serialize, Deserialize)]
pub struct PresenceNotification {
    pub active_watchers: i64,
}
