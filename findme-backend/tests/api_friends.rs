mod helpers;
use axum::http::StatusCode;

#[sqlx::test]
async fn test_send_friend_request_success(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a").await;
    app.create_user("bob", "key_b").await;

    let res = app.send_friend_request(&alice.token, "bob").await;
    assert_eq!(res.status_code(), StatusCode::OK);
}

#[sqlx::test]
async fn test_send_friend_request_not_found(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a").await;

    // Send request to non-existent user
    let res = app.send_friend_request(&alice.token, "ghost").await;
    assert_eq!(res.status_code(), StatusCode::NOT_FOUND);
}

#[sqlx::test]
async fn test_accept_friend_request_success(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a").await;
    let bob = app.create_user("bob", "key_b").await;

    app.send_friend_request(&alice.token, "bob").await;

    let res = app.accept_friend_request(&bob.token, alice.user_id).await;
    assert_eq!(res.status_code(), StatusCode::OK);
}

#[sqlx::test]
async fn test_unauthenticated_request(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    app.create_user("bob", "key_b").await;

    // Try without a valid token
    let res = app.send_friend_request("invalid_token_123", "bob").await;
    
    // We expect the auth_middleware to block this
    assert_eq!(res.status_code(), StatusCode::UNAUTHORIZED);
}
