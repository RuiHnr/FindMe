use sqlx::PgPool;
use uuid::Uuid;

/// Upserts a heartbeat for the given user, marking them as actively watching
pub async fn upsert_presence(pool: &PgPool, user_id: Uuid) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO active_sessions (user_id, last_heartbeat)
            VALUES ($1, now())
            ON CONFLICT (user_id) DO UPDATE SET last_heartbeat = now();",
    )
    .bind(user_id)
    .execute(pool)
    .await?;
    Ok(())
}

/// Removes a user's active session (e.g. on background app or logout)
pub async fn delete_presence(pool: &PgPool, user_id: Uuid) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM active_sessions WHERE user_id = $1;")
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}

/// Counts how many of the given user's friends are actively watching.
/// Only counts sessions with a heartbeat within the last 60 seconds.
pub async fn count_active_watchers(pool: &PgPool, user_id: Uuid) -> Result<i64, sqlx::Error> {
    let row = sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM active_sessions a
        JOIN friendships f ON (
            (f.user_id_a = $1 AND f.user_id_b = a.user_id)
            OR (f.user_id_b = $1 AND f.user_id_a = a.user_id)
        )
        WHERE f.status = 'accepted'
        AND a.last_heartbeat > now() - INTERVAL '60 seconds';",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await?;
    Ok(row)
}
