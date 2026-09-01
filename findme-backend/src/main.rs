mod models;
pub mod handlers;
pub mod db;
pub mod auth;

use axum::{
    routing::{get, post},
    Router,
};
use axum::middleware::from_fn_with_state;
use sqlx::{postgres::PgPoolOptions};
use crate::auth::auth_middleware;

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::PgPool,
    pub jwt_secret: String,
}

#[tokio::main]
async fn main() {
    // ===== SECRET KEY SETUP =====
    let jwt_secret = std::env::var("JWT_SECRET").unwrap_or_else(|_|
    "super_secret_dev_key".to_string());

    // ===== DB-SETUP =====

    // Build connection to DB (Connection Pool)
    let db_url = "postgres://findme:supersecret@localhost:5432/findme_db";
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(db_url)
        .await
        .expect("Couldn't connect to DB! Is the Docker Container running?");

    println!("✅ Successfully connected to PostgresSQL!");

    db::init_db(&pool).await;

    let state = AppState {
        db: pool,
        jwt_secret,
    };

    // ===== API-SETUP =====

    // 1. Define Endpoints
    let app = Router::new()

        .route("/inbox/{receiver_id}", get(handlers::get_inbox))

        .route_layer(from_fn_with_state(state.clone(), auth_middleware))

        .route("/", get(|| async { "Welcome to the FindMe Server! (Status: Online)" }))

        .route("/users/register", post(handlers::register_user))

        .route("/inbox", post(handlers::receive_location))

        .with_state(state);

    // 2. On which port does the server listen?
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();

    println!("Starting FindMe Server on http://localhost:3000");

    // 3. Start the actual Axum-Server
    axum::serve(listener, app).await.unwrap();
}
