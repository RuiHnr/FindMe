mod helpers;
use axum::http::StatusCode;
use findme_backend::models::InboxMessage;

#[sqlx::test]
async fn test_complete_friend_and_location_lifecycle(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;

    // 1. Register users
    let alice = app.create_user("alice", "alice_key").await;
    let bob = app.create_user("bob", "bob_key").await;

    // 2. Alice attempts to send location before friendship -> Fails
    let res = app.send_location(&alice.token, bob.user_id, "early_secret").await;
    assert_eq!(res.status_code(), StatusCode::FORBIDDEN);

    // 3. Alice requests Bob as friend
    let res = app.send_friend_request(&alice.token, "bob").await;
    assert_eq!(res.status_code(), StatusCode::OK);

    // 4. Bob accepts Alice
    let res = app.accept_friend_request(&bob.token, alice.user_id).await;
    assert_eq!(res.status_code(), StatusCode::OK);

    // 5. Alice sends location to Bob -> Succeeds
    let res = app.send_location(&alice.token, bob.user_id, "real_secret_location").await;
    assert_eq!(res.status_code(), StatusCode::OK);

    // 6. Bob reads inbox -> gets Alice's message
    let res = app.get_inbox(&bob.token, bob.user_id).await;
    assert_eq!(res.status_code(), StatusCode::OK);

    let messages: Vec<InboxMessage> = res.json();
    assert_eq!(messages.len(), 1);
    assert_eq!(messages[0].sender_id, alice.user_id);
    assert_eq!(messages[0].encrypted_payload, "real_secret_location");

    // 7. Bob reads inbox again -> should be empty (consumed)
    let res2 = app.get_inbox(&bob.token, bob.user_id).await;
    assert_eq!(res2.status_code(), StatusCode::OK);
    
    let empty_messages: Vec<InboxMessage> = res2.json();
    assert_eq!(empty_messages.len(), 0);
}
