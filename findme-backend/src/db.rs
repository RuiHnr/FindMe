use sqlx::PgPool;
use uuid::Uuid;
use crate::models::{InboxMessage, LocationPayload, RegisterRequest};

/// Function to initialize all DB Tables (to be changed later)
pub async fn init_db(pool: &PgPool) {
    // Users Table
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS users (
                id UUID PRIMARY KEY,
                username VARCHAR(255) UNIQUE NOT NULL,
                public_key TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );"
    )
        .execute(pool)
        .await
        .expect("Couldn't create Inbox Table!");
    
    // Inbox Table
    sqlx::query(
    "CREATE TABLE IF NOT EXISTS location_inbox (
            id UUID PRIMARY KEY,
            sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            receiver_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            encrypted_payload TEXT NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );"
    )
        .execute(pool)
        .await
        .expect("Couldn't create Inbox Table!");
}

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

/// Saves a new location package into DB
pub async fn insert_location(
    pool: &PgPool,
    payload: &LocationPayload
) -> Result<(), sqlx::Error> {
    let new_entry_id = Uuid::new_v4();

    // run sqlx query
    sqlx::query(
        "INSERT INTO location_inbox (id, sender_id, receiver_id, encrypted_payload)
             VALUES ($1, $2, $3, $4);"
    )
    .bind(new_entry_id)
    .bind(payload.sender_id)
    .bind(payload.receiver_id)
    .bind(&payload.encrypted_blob)
    .execute(pool)
    .await?;

    Ok(())
}

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