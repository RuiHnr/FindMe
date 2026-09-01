use axum::{extract::{Path, State}, http::StatusCode, Json, response::IntoResponse, Extension};
use chrono::{Utc, Duration};
use jsonwebtoken::{encode, Header, EncodingKey};
use uuid::Uuid;
use crate::{db, models::LocationPayload, AppState};
use crate::models::{Claims, RegisterRequest, RegisterResponse};

pub(crate) async fn register_user(
    State(state): State<AppState>,
    Json(payload): Json<RegisterRequest>
) -> Result<impl IntoResponse, StatusCode> {
    println!("Trying to register user {}", payload.username);

    match db::create_user(&state.db, &payload).await {
        Ok(user_id) => {
            println!("Successfully registered user {} (ID: {})", payload.username, user_id);
            
            // 1. Set expiration
            let expiration = Utc::now()
                .checked_add_signed(Duration::days(30))
                .expect("valid timestamp")
                .timestamp() as usize;
            
            let claims = Claims {
                sub: user_id,
                exp: expiration
            };
            
            // 2. Encode the token
            let token = encode(
                &Header::default(),
                &claims,
                &EncodingKey::from_secret(state.jwt_secret.as_bytes()),
            ).map_err(|e| {
                eprintln!("Error encoding token: {}", e);
                StatusCode::INTERNAL_SERVER_ERROR
            })?;
            
            Ok((StatusCode::OK, Json(RegisterResponse { user_id, token })).into_response())
        },
        Err(e) => {
            eprintln!("DB-Error while registering: {}", e);
            Err(StatusCode::BAD_REQUEST)
        }
    }
}

/// **POST** Endpoint to receive and save location data
pub(crate) async fn receive_location(
    State(state): State<AppState>,
    Json(payload): Json<LocationPayload>
) -> String {
    println!("Trying to save package from {} to {}...", payload.sender_id, payload.receiver_id);

    match db::insert_location(&state.db, &payload).await {
        Ok(_) => format!("Server: Package for {} secured!", payload.receiver_id),
        Err(e) => {
            eprintln!("DB-Error: {}", e);
            "DB Error!".to_string()
        }
    }
}


/// **GET** Endpoint to retrieve all messages a user has in his inbox and delete them from the server
///
pub(crate) async fn get_inbox(
    State(state): State<AppState>,
    Path(receiver_id): Path<Uuid>,
    Extension(authenticated_user): Extension<Uuid>
) -> Result<impl IntoResponse, StatusCode> {
    // Only the owner can fetch their inbox
    if receiver_id != authenticated_user {
        return Err(StatusCode::FORBIDDEN);
    }
    
    // fetch from DB
    println!("User {} is reading his inbox...", receiver_id);

    match db::fetch_inbox(&state.db, receiver_id).await {
        Ok(messages) => {
            println!("{} Package found and deleted from DB", messages.len());
            Ok((StatusCode::OK, Json(messages)).into_response())
        },
        Err(e) => {
            println!("DB-Error: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}