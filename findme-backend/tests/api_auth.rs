mod helpers;
use axum::http::StatusCode;
use findme_backend::models::RegisterRequest;

#[sqlx::test]
async fn test_register_success(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    let response = app.create_user("alice", "pub_key_123").await;
    
    assert!(!response.token.is_empty(), "Token should not be empty");
}

#[sqlx::test]
async fn test_register_duplicate_username(pool: sqlx::PgPool) {
    let app = helpers::spawn_app(pool).await;
    
    // Create first user
    app.create_user("alice", "pub_key_123").await;

    // Attempt to register same username
    let res = app.server
        .post("/users/register")
        .json(&RegisterRequest {
            username: "alice".to_string(),
            pub_key: "pub_key_456".to_string(),
        })
        .await;

    assert_eq!(res.status_code(), StatusCode::BAD_REQUEST);
}