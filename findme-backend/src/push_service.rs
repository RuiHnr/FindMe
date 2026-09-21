use a2::{
    Client as ApnsClient, ClientConfig, DefaultNotificationBuilder, Endpoint, NotificationBuilder,
    NotificationOptions, Priority, PushType,
};
use reqwest::Client as HttpClient;
use serde_json::json;
use std::env;
use std::fs::File;

/// Manages HTTP/2 connection pools to Google FCM and Apple APNs.
/// This should be instantiated once at startup and wrapped in an Arc
pub struct PushService {
    fcm_client: HttpClient,
    apns_client: ApnsClient,
    fcm_project_id: String,
    fcm_access_token: String, // TODO: use `gcp_auth` crate to auto-refresh this token
}

impl PushService {
    /// Initializes the push service, reading credentials from env
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        // Read APNs credentials
        let team_id = env::var("APNS_TEAM_ID").expect("APNS_TEAM_ID missing");
        let key_id = env::var("APNS_KEY_ID").expect("APNS_KEY_ID missing");
        let p8_path = env::var("APNS_KEY_PATH").unwrap_or_else(|_| "AuthKey.p8".to_string());

        // Read FCM credentials
        let fcm_project_id = env::var("FCM_PROJECT_ID").expect("FCM_PROJECT_ID missing");
        let fcm_access_token = env::var("FCM_OAUTH_TOKEN").unwrap_or_default();

        let apns_file = File::open(&p8_path)
            .map_err(|e| format!("Failed to open APNs key file at {}: {}", p8_path, e))?;

        // Initialize Apple APNs Client. This creates the HTTP/2 connection pool.
        let apns_client = ApnsClient::token(
            apns_file,
            &team_id,
            &key_id,
            ClientConfig {
                endpoint: Endpoint::Production,
                ..ClientConfig::default()
            },
        )?;

        Ok(Self {
            fcm_client: HttpClient::new(),
            apns_client,
            fcm_project_id,
            fcm_access_token,
        })
    }

    pub async fn send_fcm_wake_up(&self, fcm_token: &str) -> Result<(), reqwest::Error> {
        let url = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            self.fcm_project_id
        );

        let payload = json!({
            "message": {
                "token": fcm_token,
                "data": { "type": "wake_up" },
                "android": { "priority": "high" },
                "apns": {
                    "headers": { "apns-push-type": "background", "apns-priority": "5" },
                    "payload": { "aps": { "content-available": 1 } }
                }
            }
        });

        self.fcm_client
            .post(&url)
            .bearer_auth(&self.fcm_access_token)
            .json(&payload)
            .send()
            .await?
            .error_for_status()?;
        Ok(())
    }

    pub async fn send_apns_wake_up(
        &self,
        apns_token: &str,
    ) -> Result<(), Box<dyn std::error::Error>> {
        // Apple strictly requires Priority::Normal and PushType::Background for silent pushes
        let options = NotificationOptions {
            apns_push_type: Some(PushType::Background),
            apns_priority: Some(Priority::Normal),
            ..Default::default()
        };

        // Setting content-available to 1 is required for a background wake-up
        let mut payload = DefaultNotificationBuilder::new()
            .set_content_available()
            .build(apns_token, options);
        // Add custom payload data
        payload.add_custom_data("type", &"wake_up")?;

        self.apns_client.send(payload).await?;
        Ok(())
    }
}
