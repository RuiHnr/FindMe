use crate::models::friends::FriendDto;
use sqlx::PgPool;
use uuid::Uuid;

/// Creates a pending friendship request from a sender to a target user.
/// Ignores the request if it already exists to prevent duplicate key errors.
pub async fn send_friend_request(
    pool: &PgPool,
    sender: Uuid,
    target: Uuid,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO friendships (user_id_a, user_id_b, status)
        VALUES ($1, $2, $3)
        ON CONFLICT (user_id_a, user_id_b) DO NOTHING;",
    )
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
    requester: Uuid,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE friendships SET status = $1
           WHERE user_id_a = $2 AND user_id_b = $3;",
    )
        .bind("accepted")
        .bind(requester)
        .bind(user)
        .execute(pool)
        .await?;

    Ok(())
}

/// Checks if two users have an active, accepted friendship in either direction.
pub async fn are_friends(pool: &PgPool, user1: Uuid, user2: Uuid) -> Result<bool, sqlx::Error> {
    let is_friend = sqlx::query_scalar(
        "SELECT EXISTS (
        SELECT 1 FROM friendships
        WHERE status = 'accepted'
        AND ((user_id_a = $1 AND user_id_b = $2) OR
            (user_id_a = $2 AND user_id_b = $1))
        );",
    )
        .bind(user1)
        .bind(user2)
        .fetch_one(pool)
        .await?;

    Ok(is_friend)
}

pub async fn get_friends_by_id(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Vec<FriendDto>, sqlx::Error> {
    let friends = sqlx::query_as::<_, FriendDto>(
        "SELECT id AS user_id, username, identity_key_dh, identity_key_sign
            FROM users
            WHERE id != $1 AND exists(
                SELECT 1 FROM friendships
                 WHERE ((user_id_a = $1 AND user_id_b = users.id) OR (user_id_b = $1 AND user_id_a = users.id))
                 AND status = 'accepted'
            )"
    )
        .bind(user_id)
        .fetch_all(pool)
        .await?;

    Ok(friends)
}

pub async fn get_friend_requests_by_id(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Vec<FriendDto>, sqlx::Error> {
    let friends = sqlx::query_as::<_, FriendDto>(
        "SELECT id AS user_id, username, identity_key_dh, identity_key_sign
            FROM users
            WHERE id != $1 AND exists(
                SELECT 1 FROM friendships
                 WHERE user_id_a = users.id AND user_id_b = $1
                 AND status = 'pending'
            )"
    )
        .bind(user_id)
        .fetch_all(pool)
        .await?;
    Ok(friends)
}
