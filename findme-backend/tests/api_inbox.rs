mod helpers;
use axum::http::StatusCode;

#[sqlx::test]
async fn test_non_friend_cannot_send_location(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a", "key_a").await;
    let bob = app.create_user("bob", "key_b", "key_b").await;

    // Try to send without being friends
    let res = app
        .send_location(&alice.token, vec![bob.user_id], "secret_blob")
        .await;

    // Should be rejected (0 accepted payloads)
    assert_eq!(res.status_code(), StatusCode::OK);
    let json: findme_backend::models::SubmitLocationResponse = res.json();
    assert_eq!(json.accepted, 0);
}
