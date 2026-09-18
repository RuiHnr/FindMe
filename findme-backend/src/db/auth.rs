use crate::models::auth::RegisterRequest;
use sqlx::PgPool;
use uuid::Uuid;

/// Insert a new user into users Table, returning the user's ID
pub async fn create_user(pool: &PgPool, req: &RegisterRequest) -> Result<Uuid, sqlx::Error> {
    let new_user_id = Uuid::new_v4();

    sqlx::query(
        "INSERT INTO users (id, username, identity_key_dh, identity_key_sign)
         VALUES($1, $2, $3, $4) RETURNING id;",
    )
        .bind(new_user_id)
        .bind(&req.username)
        .bind(&req.identity_key_dh)
        .bind(&req.identity_key_sign)
        .execute(pool)
        .await?;

    Ok(new_user_id)
}

/// Fetches a user's UUID by their exact username, returning None if not found.
pub async fn get_user_id_by_name(
    pool: &PgPool,
    username: &str,
) -> Result<Option<Uuid>, sqlx::Error> {
    sqlx::query_scalar("SELECT id FROM users WHERE username = $1;")
        .bind(username)
        .fetch_optional(pool)
        .await
}
