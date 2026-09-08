use sqlx::PgPool;
use uuid::Uuid;
use crate::models::{InboxMessage, OneTimePreKeyDto, PreKeyBundleResponse, RegisterRequest, SignedPreKeyDto};

/// Insert a new user into users Table, returning the user's ID
pub async fn create_user(
    pool: &PgPool,
    req: &RegisterRequest
) -> Result<Uuid, sqlx::Error> {
    let new_user_id = Uuid::new_v4();
    
    sqlx::query(
    "INSERT INTO users (id, username, public_key)
         VALUES($1, $2, $3) RETURNING id;"
    )
        .bind(new_user_id)
        .bind(&req.username)
        .bind(&req.pub_key)
        .execute(pool)
        .await?;
    
    Ok(new_user_id)
}

/// Fetches a user's UUID by their exact username, returning None if not found.
pub async fn get_user_id_by_name(
    pool: &PgPool,
    username: &str
) -> Result<Option<Uuid>, sqlx::Error> {
    sqlx::query_scalar("SELECT id FROM users WHERE username = $1;")
        .bind(username)
    .fetch_optional(pool)
    .await
}

/// Saves a new location package into DB
pub async fn insert_location(
    pool: &PgPool,
    sender_id: &Uuid,
    receiver_id: &Uuid,
    encrypted_blob: &String
) -> Result<(), sqlx::Error> {
    let new_entry_id = Uuid::new_v4();

    sqlx::query(
        "INSERT INTO location_inbox (id, sender_id, receiver_id, encrypted_payload)
             VALUES ($1, $2, $3, $4);"
    )
    .bind(new_entry_id)
    .bind(sender_id)
    .bind(receiver_id)
    .bind(encrypted_blob)
    .execute(pool)
    .await?;

    Ok(())
}

/// Retrieves and deletes all queued location messages for a specific user.
pub async fn fetch_inbox(
    pool: &PgPool,
    receiver_id: Uuid
) -> Result<Vec<InboxMessage>, sqlx::Error> {

    let messages = sqlx::query_as::<_, InboxMessage>(
        "DELETE FROM location_inbox
             WHERE receiver_id = $1
             RETURNING sender_id, encrypted_payload;"
    )
    .bind(receiver_id)
    .fetch_all(pool)
    .await?;

    Ok(messages)
}

/// Creates a pending friendship request from a sender to a target user.
/// Ignores the request if it already exists to prevent duplicate key errors.
pub async fn send_friend_request(
    pool: &PgPool,
    sender: Uuid,
    target: Uuid,
) -> Result<(), sqlx::Error> {
    sqlx::query("INSERT INTO friendships (user_id_a, user_id_b, status)
        VALUES ($1, $2, $3)
        ON CONFLICT (user_id_a, user_id_b) DO NOTHING;")
    .bind(sender)
    .bind(target)
    .bind("pending")
    .execute(pool)
    .await?;

    Ok(())
}

/// Updates a pending friendship request to 'accepted'.
pub async fn accept_friend_request(
    pool: &PgPool,
    user: Uuid,
    requester: Uuid
) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE friendships SET status = $1
           WHERE user_id_a = $2 AND user_id_b = $3;")
    .bind("accepted")
    .bind(requester)
    .bind(user)
    .execute(pool)
    .await?;

    Ok(())
}

/// Checks if two users have an active, accepted friendship in either direction.
pub async fn are_friends(
    pool: &PgPool,
    user1: Uuid,
    user2: Uuid
) -> Result<bool, sqlx::Error> {
    let is_friend = sqlx::query_scalar("SELECT EXISTS (
        SELECT 1 FROM friendships
        WHERE status = 'accepted'
        AND ((user_id_a = $1 AND user_id_b = $2) OR
            (user_id_a = $2 AND user_id_b = $1))
        );"
    )
    .bind(user1)
    .bind(user2)
    .fetch_one(pool)
    .await?;

    Ok(is_friend)
}

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
             created_at = CURRENT_TIMESTAMP;"
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
             ON CONFLICT (user_id, key_id) DO NOTHING;"
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
pub async fn count_onetime_prekeys(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<i64, sqlx::Error> {
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM onetime_prekeys WHERE user_id = $1;"
    )
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
    // 1. Fetch user identity key
    let identity_key: Option<String> = sqlx::query_scalar(
        "SELECT public_key FROM users WHERE id = $1;"
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;

    let identity_key = match identity_key {
        Some(k) => k,
        None => return Ok(None),
    };

    // 2. Fetch signed prekey
    let signed_prekey = sqlx::query_as::<_, SignedPreKeyDto>(
        "SELECT key_id, public_key, signature FROM signed_prekeys WHERE user_id = $1;"
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
         RETURNING key_id, public_key;"
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?;

    Ok(Some(PreKeyBundleResponse {
        user_id,
        identity_key,
        signed_prekey,
        one_time_prekey,
    }))
}