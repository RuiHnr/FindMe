use axum::http::StatusCode;
use findme_backend::db;
use sqlx::PgPool;

mod helpers;

#[sqlx::test]
async fn test_presence_heartbeat_succeeds_with_friend_tokens(pool: PgPool) {
    let app = helpers::spawn_app(pool.clone()).await;

    // 1. Create User A (the one watching)
    let user_a = app.create_user("watcher", "dh1", "sign1").await;

    // 2. Create User B (the one being watched, who will receive the push)
    let user_b = app.create_user("watched", "dh2", "sign2").await;

    // 3. Make them friends
    app.send_friend_request(&user_a.token, "watched").await;
    app.accept_friend_request(&user_b.token, user_a.user_id)
        .await;

    // 4. Directly inject a device token for User B into the database using our new helper

    db::upsert_device_token(
        &pool,
        user_b.user_id,
        Some("fake-fcm-token".to_string()),
        Some("fake-apns-token".to_string()),
    )
    .await
    .expect("Failed to insert device token");

    // 5. Hit POST /presence as User A
    let response = app
        .server
        .post("/presence")
        .add_header("Authorization", format!("Bearer {}", user_a.token))
        .await;

    // It should succeed (200 OK). This proves the SQL JOIN query in
    // `get_friends_device_tokens` executes flawlessly without throwing a 500 error,
    // handling symmetric relationships and missing push services correctly.
    assert_eq!(response.status_code(), StatusCode::OK);

    // 6. Test DELETE /presence
    let remove_response = app
        .server
        .delete("/presence")
        .add_header("Authorization", format!("Bearer {}", user_a.token))
        .await;

    assert_eq!(remove_response.status_code(), StatusCode::OK);
}
