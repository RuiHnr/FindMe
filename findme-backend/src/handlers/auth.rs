use crate::models::{Claims, RegisterRequest, RegisterResponse};
use crate::{AppState, db};
use axum::{Json, extract::State, http::StatusCode, response::IntoResponse};
use chrono::{Duration, Utc};
use jsonwebtoken::{EncodingKey, Header, encode};

/// Registers a new user, saving their public key and returning a JWT token for authentication.
pub(crate) async fn register_user(
    State(state): State<AppState>,
    Json(payload): Json<RegisterRequest>,
) -> Result<impl IntoResponse, StatusCode> {
    let user_id = db::create_user(&state.db, &payload).await.map_err(|e| {
        eprintln!("DB-Error while registering: {}", e);
        StatusCode::BAD_REQUEST
    })?;

    println!(
        "Successfully registered user: {} (ID: {})",
        payload.username, user_id
    );

    // 1. Set expiration
    let expiration = Utc::now()
        .checked_add_signed(Duration::days(30))
        .expect("valid timestamp")
        .timestamp() as usize;

    let claims = Claims {
        sub: user_id,
        exp: expiration,
    };

    // 2. Encode the token
    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(state.jwt_secret.as_bytes()),
    )
    .map_err(|e| {
        eprintln!("Error encoding token: {}", e);
        StatusCode::INTERNAL_SERVER_ERROR
    })?;

    Ok((StatusCode::OK, Json(RegisterResponse { user_id, token })).into_response())
}
