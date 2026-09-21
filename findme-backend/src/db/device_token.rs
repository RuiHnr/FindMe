use crate::models::device_token::DeviceToken;
use sqlx::PgPool;
use uuid::Uuid;

/// Returns the device tokens for a given user.
pub async fn get_device_token(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Option<DeviceToken>, sqlx::Error> {
    let token = sqlx::query_as::<_, DeviceToken>(
        "SELECT fcm_token, apns_token FROM device_tokens WHERE user_id = $1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;
    Ok(token)
}
