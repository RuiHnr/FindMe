mod models;
pub mod handlers;
pub mod db;

use axum::{
    routing::{get, post},
    Router
};
use sqlx::{postgres::PgPoolOptions};

#[tokio::main]
async fn main() {
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

    // ===== API-SETUP =====

    // 1. Define Endpoints
    let app = Router::new()
        .route("/", get(|| async { "Welcome to the FindMe Server! (Status: Online)" }))

        .route("/users/register", post(handlers::register_user))

        .route("/inbox", post(handlers::receive_location))

        .route("/inbox/{receiver_id}", get(handlers::fetch_inbox))

        .with_state(pool);

    // 2. On which port does the server listen?
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();

    println!("Starting FindMe Server on http://localhost:3000");

    // 3. Start the actual Axum-Server
    axum::serve(listener, app).await.unwrap();
}
