use crate::models::location::InboxMessage;
use sqlx::PgPool;
use uuid::Uuid;

/// Saves a new location package into DB
pub async fn insert_location(
    pool: &PgPool,
    sender_id: &Uuid,
    receiver_id: &Uuid,
    encrypted_blob: &String,
) -> Result<(), sqlx::Error> {
    let new_entry_id = Uuid::new_v4();

    sqlx::query(
        "INSERT INTO location_inbox (id, sender_id, receiver_id, encrypted_payload)
             VALUES ($1, $2, $3, $4);",
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
    receiver_id: Uuid,
) -> Result<Vec<InboxMessage>, sqlx::Error> {
    let messages = sqlx::query_as::<_, InboxMessage>(
        "DELETE FROM location_inbox
             WHERE receiver_id = $1
             RETURNING sender_id, encrypted_payload;",
    )
    .bind(receiver_id)
    .fetch_all(pool)
    .await?;

    Ok(messages)
}
