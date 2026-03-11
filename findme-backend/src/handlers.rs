use axum::{
    extract::{Path, State},
    http::StatusCode, Json,
    response::{IntoResponse, Response}
};
use sqlx::PgPool;
use uuid::Uuid;
use crate::{db, models::{InboxMessage, LocationPayload}};
use crate::models::{RegisterRequest, RegisterResponse};

pub(crate) async fn register_user(
    State(pool): State<PgPool>,
    Json(payload): Json<RegisterRequest>
) -> Response {
    println!("Trying to register user {}", payload.username);

    match db::create_user(&pool, &payload).await {
        Ok(user_id) => {
            println!("Successfully registered user {} (ID: {})", payload.username, user_id);
            (StatusCode::OK, Json(RegisterResponse { user_id })).into_response()
        },
        Err(e) => {
            eprintln!("DB-Error while registering: {}", e);
            (StatusCode::BAD_REQUEST, "Username already exists / DB-Error").into_response()
        }
    }
}

/// **POST** Endpoint to receive and save location data
pub(crate) async fn receive_location(
    State(pool): State<PgPool>,
    Json(payload): Json<LocationPayload>
) -> String {
    println!("Trying to save package from {} to {}...", payload.sender_id, payload.receiver_id);

    match db::insert_location(&pool, &payload).await {
        Ok(_) => format!("Server: Package for {} secured!", payload.receiver_id),
        Err(e) => {
            eprintln!("DB-Error: {}", e);
            "DB Error!".to_string()
        }
    }
}


/// **GET** Endpoint to retrieve all messages a user has in his inbox and delete them from the server
///
pub(crate) async fn fetch_inbox(
    State(pool): State<PgPool>,
    Path(receiver_id): Path<Uuid>
) -> Json<Vec<InboxMessage>> {
    println!("User {} is reading his inbox...", receiver_id);

    match db::fetch_inbox(&pool, receiver_id).await {
        Ok(messages) => {
            println!("{} Package found and deleted from DB", messages.len());
            Json(messages)
        },
        Err(e) => {
            println!("DB-Error: {}", e);
            Json(vec![])
        }
    }
}