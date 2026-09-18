mod helpers;
use axum::http::StatusCode;

#[sqlx::test]
async fn test_send_friend_request_success(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a", "key_a").await;
    app.create_user("bob", "key_b", "key_b").await;

    let res = app.send_friend_request(&alice.token, "bob").await;
    assert_eq!(res.status_code(), StatusCode::OK);
}

#[sqlx::test]
async fn test_send_friend_request_not_found(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a", "key_a").await;

    // Send request to non-existent user
    let res = app.send_friend_request(&alice.token, "ghost").await;
    assert_eq!(res.status_code(), StatusCode::NOT_FOUND);
}

#[sqlx::test]
async fn test_accept_friend_request_success(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a", "key_a").await;
    let bob = app.create_user("bob", "key_b", "key_b").await;

    app.send_friend_request(&alice.token, "bob").await;

    let res = app.accept_friend_request(&bob.token, alice.user_id).await;
    assert_eq!(res.status_code(), StatusCode::OK);
}

#[sqlx::test]
async fn test_unauthenticated_request(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    app.create_user("bob", "key_b", "key_b").await;

    // Try without a valid token
    let res = app.send_friend_request("invalid_token_123", "bob").await;

    // We expect the auth_middleware to block this
    assert_eq!(res.status_code(), StatusCode::UNAUTHORIZED);
}

#[sqlx::test]
async fn test_get_friends_and_requests(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let alice = app.create_user("alice", "key_a", "sign_a").await;
    let bob = app.create_user("bob", "key_b", "sign_b").await;
    let charlie = app.create_user("charlie", "key_c", "sign_c").await;

    // Alice sends request to Bob
    app.send_friend_request(&alice.token, "bob").await;

    // Charlie sends request to Bob
    app.send_friend_request(&charlie.token, "bob").await;

    // Bob checks pending requests
    let reqs_res = app.get_friend_requests(&bob.token).await;
    assert_eq!(reqs_res.status_code(), StatusCode::OK);
    let reqs: Vec<findme_backend::models::FriendDto> = reqs_res.json();
    assert_eq!(reqs.len(), 2);
    let mut names: Vec<String> = reqs.into_iter().map(|f| f.username).collect();
    names.sort();
    assert_eq!(names, vec!["alice", "charlie"]);

    // Bob accepts Alice
    app.accept_friend_request(&bob.token, alice.user_id).await;

    // Bob checks pending requests again (Charlie should still be there)
    let reqs_res = app.get_friend_requests(&bob.token).await;
    let reqs: Vec<findme_backend::models::FriendDto> = reqs_res.json();
    assert_eq!(reqs.len(), 1);
    assert_eq!(reqs[0].username, "charlie");

    // Bob checks friends (Alice should be there)
    let friends_res = app.get_friends(&bob.token).await;
    assert_eq!(friends_res.status_code(), StatusCode::OK);
    let friends: Vec<findme_backend::models::FriendDto> = friends_res.json();
    assert_eq!(friends.len(), 1);
    assert_eq!(friends[0].username, "alice");
    assert_eq!(friends[0].user_id, alice.user_id);
    assert_eq!(friends[0].identity_key_dh, "key_a");
    assert_eq!(friends[0].identity_key_sign, "sign_a");

    // Alice checks friends (Bob should be there)
    let alice_friends_res = app.get_friends(&alice.token).await;
    let alice_friends: Vec<findme_backend::models::FriendDto> = alice_friends_res.json();
    assert_eq!(alice_friends.len(), 1);
    assert_eq!(alice_friends[0].username, "bob");
}
