use axum_test::{TestServer, TestResponse};
use sqlx::PgPool;
use findme_backend::{AppState, build_router};
use findme_backend::models::{RegisterRequest, RegisterResponse, FriendRequest, SubmitLocationRequest};
use uuid::Uuid;

pub struct TestApp {
    pub server: TestServer,
    pub db: PgPool,
}

pub async fn spawn_app(pool: PgPool) -> TestApp {
    let state = AppState {
        db: pool.clone(),
        jwt_secret: "test_secret_key".to_string(),
    };

    let app = build_router(state);

    TestApp {
        server: TestServer::new(app),
        db: pool,
    }
}

impl TestApp {
    /// Helper to register a new user and extract the response
    pub async fn create_user(&self, username: &str, pub_key: &str) -> RegisterResponse {
        self.server
            .post("/users/register")
            .json(&RegisterRequest {
                username: username.to_string(),
                pub_key: pub_key.to_string(),
            })
            .await
            .json()
    }

    /// Helper to send a friend request
    pub async fn send_friend_request(&self, token: &str, target_username: &str) -> TestResponse {
        self.server
            .post("/friends/requests")
            .add_header("Authorization", format!("Bearer {}", token))
            .json(&FriendRequest {
                target_username: target_username.to_string(),
            })
            .await
    }

    /// Helper to accept a friend request
    pub async fn accept_friend_request(&self, token: &str, requester_id: Uuid) -> TestResponse {
        self.server
            .put(&format!("/friends/requests/{}/accept", requester_id))
            .add_header("Authorization", format!("Bearer {}", token))
            .await
    }

    /// Helper to send a location payload to another user
    pub async fn send_location(&self, token: &str, receiver_id: Uuid, payload: &str) -> TestResponse {
        self.server
            .post("/inbox")
            .add_header("Authorization", format!("Bearer {}", token))
            .json(&SubmitLocationRequest {
                receiver_id,
                encrypted_blob: payload.to_string(),
            })
            .await
    }

    /// Helper to fetch the inbox for a specific user
    pub async fn get_inbox(&self, token: &str, receiver_id: Uuid) -> TestResponse {
        self.server
            .get(&format!("/inbox/{}", receiver_id))
            .add_header("Authorization", format!("Bearer {}", token))
            .await
    }
}