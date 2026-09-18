use crate::models::keys::{OneTimePreKeyDto, PreKeyBundleResponse, SignedPreKeyDto};
use sqlx::PgPool;
use uuid::Uuid;

/// Upserts a user's signed prekey
pub async fn upsert_signed_prekey(
    pool: &PgPool,
    user_id: Uuid,
    prekey: &SignedPreKeyDto,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO signed_prekeys (user_id, key_id, public_key, signature)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (user_id) DO UPDATE SET
             key_id = EXCLUDED.key_id,
             public_key = EXCLUDED.public_key,
             signature = EXCLUDED.signature,
             created_at = CURRENT_TIMESTAMP;",
    )
    .bind(user_id)
    .bind(prekey.key_id)
    .bind(&prekey.public_key)
    .bind(&prekey.signature)
    .execute(pool)
    .await?;

    Ok(())
}

/// Bulk inserts one-time prekeys, ignoring duplicates
pub async fn insert_onetime_prekeys(
    pool: &PgPool,
    user_id: Uuid,
    prekeys: &[OneTimePreKeyDto],
) -> Result<(), sqlx::Error> {
    if prekeys.is_empty() {
        return Ok(());
    }

    let mut tx = pool.begin().await?;
    for key in prekeys {
        sqlx::query(
            "INSERT INTO onetime_prekeys (user_id, key_id, public_key)
             VALUES ($1, $2, $3)
             ON CONFLICT (user_id, key_id) DO NOTHING;",
        )
        .bind(user_id)
        .bind(key.key_id)
        .bind(&key.public_key)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;

    Ok(())
}

/// Retrieves the count of available one-time prekeys for a user
pub async fn count_onetime_prekeys(pool: &PgPool, user_id: Uuid) -> Result<i64, sqlx::Error> {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM onetime_prekeys WHERE user_id = $1;")
        .bind(user_id)
        .fetch_one(pool)
        .await?;

    Ok(count)
}

/// Fetches a PreKey bundle for X3DH session initiation, popping one one-time prekey atomically.
pub async fn fetch_prekey_bundle(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Option<PreKeyBundleResponse>, sqlx::Error> {
    // 1. Fetch user identity keys
    let identity_keys: Option<(String, String)> =
        sqlx::query_as("SELECT identity_key_dh, identity_key_sign FROM users WHERE id = $1;")
            .bind(user_id)
            .fetch_optional(pool)
            .await?;

    let (identity_key_dh, identity_key_sign) = match identity_keys {
        Some(k) => k,
        None => return Ok(None),
    };

    // 2. Fetch signed prekey
    let signed_prekey = sqlx::query_as::<_, SignedPreKeyDto>(
        "SELECT key_id, public_key, signature FROM signed_prekeys WHERE user_id = $1;",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;

    let signed_prekey = match signed_prekey {
        Some(spk) => spk,
        None => return Ok(None), // Cannot establish X3DH without signed prekey
    };

    // 3. Atomically consume and pop one one-time prekey if available
    let one_time_prekey = sqlx::query_as::<_, OneTimePreKeyDto>(
        "DELETE FROM onetime_prekeys
         WHERE (user_id, key_id) IN (
             SELECT user_id, key_id
             FROM onetime_prekeys
             WHERE user_id = $1
             ORDER BY created_at ASC
             LIMIT 1
             FOR UPDATE SKIP LOCKED
         )
         RETURNING key_id, public_key;",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;

    Ok(Some(PreKeyBundleResponse {
        user_id,
        identity_key_dh,
        identity_key_sign,
        signed_prekey,
        one_time_prekey,
    }))
}
