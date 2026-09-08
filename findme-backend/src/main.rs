use findme_backend::{AppState, build_router};
use sqlx::postgres::PgPoolOptions;

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();

    // ===== SECRET KEY SETUP =====
    let jwt_secret =
        std::env::var("JWT_SECRET").expect("JWT_SECRET must be set in your .env file!");

    // ===== DB-SETUP =====

    // Build connection to DB (Connection Pool)
    let db_url =
        std::env::var("DATABASE_URL").expect("DATABASE_URL must be set in your .env file!");

    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&db_url)
        .await
        .expect("Couldn't connect to DB! Is the Docker Container running?");

    println!("✅ Successfully connected to PostgresSQL!");

    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .expect("Failed to run database migrations");

    // Define App State
    let state = AppState {
        db: pool,
        jwt_secret,
    };

    // ===== API-SETUP =====

    // 1. Define Endpoints
    let app = build_router(state);

    // 2. On which port does the server listen?
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();

    println!("Starting FindMe Server on http://localhost:3000");

    // 3. Start the actual Axum-Server
    axum::serve(listener, app).await.unwrap();
}
