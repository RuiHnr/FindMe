mod helpers;
use axum::http::StatusCode;

#[sqlx::test]
async fn test_non_friend_cannot_send_location(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a").await;
    let bob = app.create_user("bob", "key_b").await;

    // Try to send without being friends
    let res = app
        .send_location(&alice.token, bob.user_id, "secret_blob")
        .await;

    // Should be rejected
    assert_eq!(res.status_code(), StatusCode::FORBIDDEN);
}

#[sqlx::test]
async fn test_cannot_read_others_inbox(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a").await;
    let bob = app.create_user("bob", "key_b").await;

    // Alice tries to read Bob's inbox
    let res = app.get_inbox(&alice.token, bob.user_id).await;
    assert_eq!(res.status_code(), StatusCode::FORBIDDEN);
}
