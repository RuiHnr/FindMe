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

/// Returns the device tokens for all accepted friends of a given user.
pub async fn get_friends_device_tokens(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Vec<DeviceToken>, sqlx::Error> {
    let tokens = sqlx::query_as::<_, DeviceToken>(
        "SELECT dt.fcm_token, dt.apns_token 
         FROM device_tokens dt
         JOIN friendships f ON (f.user_id_a = $1 AND f.user_id_b = dt.user_id) 
                            OR (f.user_id_b = $1 AND f.user_id_a = dt.user_id)
         WHERE f.status = 'accepted'",
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    Ok(tokens)
}

/// Upserts device tokens for a given user.
/// Uses COALESCE to ensure that if a user updates their Android token (fcm),
/// it won't accidentally erase their existing iOS token (apns).
pub async fn upsert_device_token(
    pool: &PgPool,
    user_id: Uuid,
    fcm_token: Option<String>,
    apns_token: Option<String>,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO device_tokens (user_id, fcm_token, apns_token)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id) DO UPDATE SET
            fcm_token = COALESCE($2, device_tokens.fcm_token),
            apns_token = COALESCE($3, device_tokens.apns_token);",
    )
    .bind(user_id)
    .bind(fcm_token)
    .bind(apns_token)
    .execute(pool)
    .await?;

    Ok(())
}
