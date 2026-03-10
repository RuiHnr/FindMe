use axum::{
    extract::{Path, State},
    routing::{get, post},
    Router, Json
};
use serde::{Deserialize, Serialize};
use sqlx::{postgres::PgPoolOptions, PgPool};
use uuid::Uuid;

// location_inbox Data Entity
#[derive(Deserialize, Debug)]
pub struct LocationPayload {
    pub sender_id: Uuid,
    pub receiver_id: Uuid,
    pub encrypted_blob: String
}

#[derive(Serialize, sqlx::FromRow)]
pub struct InboxMessage {
    pub sender_id: Uuid,
    pub encrypted_payload: String
}

// Handler for POST-requests
async fn receive_location(
    State(pool): State<PgPool>,
    Json(payload): Json<LocationPayload>
) -> String {
    println!("Trying to save package from {} to {}...", payload.sender_id, payload.receiver_id);

    // generate random uuid for db-entry
    let new_entry_id = Uuid::new_v4();

    // run sqlx query
    let result = sqlx::query(
        "INSERT INTO location_inbox (id, sender_id, receiver_id, encrypted_payload)
             VALUES ($1, $2, $3, $4)"
        )
        .bind(new_entry_id)
        .bind(payload.sender_id)
        .bind(payload.receiver_id)
        .bind(payload.encrypted_blob)
        .execute(&pool)
        .await;

    match result {
        Ok(_) => format!("Server: Package for {} secured!", payload.receiver_id),
        Err(e) => format!("Error while saving into DB: {}", e)
    }
}

/// **GET** Endpoint to retrieve all messages a user has in his inbox and delete them from the server
///
async fn fetch_inbox(
    State(pool): State<PgPool>,
    Path(receiver_id): Path<Uuid>
) -> Json<Vec<InboxMessage>> {
    println!("User {} is reading his inbox...", receiver_id);

    let result = sqlx::query_as::<_, InboxMessage>(
        "DELETE FROM location_inbox
             WHERE receiver_id = $1
             RETURNING sender_id, encrypted_payload"
    )
        .bind(receiver_id)
        .fetch_all(&pool)
        .await;

    match result {
        Ok(messages) => {
            println!("✅ {} Package found, sent and deleted from DB", messages.len());

            Json(messages)
        },
        Err(e) => {
            println!("❌ DB-Error: {}", e);
            // return empty array
            Json(vec![])
        }
    }
}

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

    // Create Table
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS location_inbox (
                id UUID PRIMARY KEY,
                sender_id UUID NOT NULL,
                receiver_id UUID NOT NULL,
                encrypted_payload TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )"
    )
        .execute(&pool)
        .await
        .expect("Couldn't create DB Table!");


    // ===== API-SETUP =====

    // 1. Define Endpoints
    let app = Router::new()
        .route("/", get(|| async { "Welcome to the FindMe Server! (Status: Online)" }))

        .route("/inbox", post(receive_location))

        .route("/inbox/{receiver_id}", get(fetch_inbox))

        .with_state(pool);

    // 2. On which port does the server listen?
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();

    println!("Starting FindMe Server on http://localhost:3000");

    // 3. Start the actual Axum-Server
    axum::serve(listener, app).await.unwrap();
}
