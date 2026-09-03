use crate::models::{Claims, FriendRequest, RegisterRequest, RegisterResponse};
use crate::{AppState, db, models::SubmitLocationRequest};
use axum::{
    Extension, Json,
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
};
use chrono::{Duration, Utc};
use jsonwebtoken::{EncodingKey, Header, encode};
use uuid::Uuid;

/// Registers a new user, saving their public key and returning a JWT token for authentication.
pub(crate) async fn register_user(
    State(state): State<AppState>,
    Json(payload): Json<RegisterRequest>,
) -> Result<impl IntoResponse, StatusCode> {
    let user_id=  db::create_user(&state.db, &payload)
        .await
        .map_err(|e| {
        eprintln!("DB-Error while registering: {}", e);
        StatusCode::BAD_REQUEST
    })?;

    println!("Successfully registered user: {} (ID: {})", payload.username, user_id);

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

/// Receives an encrypted location payload and queues it in the receiver's inbox.
/// Only allowed if the sender and receiver are confirmed friends.
pub(crate) async fn receive_location(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Json(payload): Json<SubmitLocationRequest>,
) -> Result<impl IntoResponse, StatusCode> {
    if !db::are_friends(&state.db, authenticated_user, payload.receiver_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    {
        return Err(StatusCode::FORBIDDEN);
    }

    db::insert_location(
        &state.db,
        &authenticated_user,
        &payload.receiver_id,
        &payload.encrypted_blob,
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(StatusCode::OK)
}

/// Retrieves and consumes all pending location messages for the authenticated user.
/// Messages are permanently deleted from the server once fetched.
pub(crate) async fn get_inbox(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Path(receiver_id): Path<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    // Only the owner can fetch their inbox
    if receiver_id != authenticated_user {
        return Err(StatusCode::FORBIDDEN);
    }

    match db::fetch_inbox(&state.db, receiver_id).await {
        Ok(messages) => Ok((StatusCode::OK, Json(messages)).into_response()),
        Err(e) => {
            eprintln!("DB-Error fetching inbox: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

/// Sends a friend request to a target user by username.
pub(crate) async fn request_friend(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Json(payload): Json<FriendRequest>,
) -> Result<impl IntoResponse, StatusCode> {
    let target_id = db::get_user_id_by_name(&state.db, &payload.target_username)
        .await
        .map_err(|_| StatusCode::BAD_REQUEST)?
        .ok_or(StatusCode::NOT_FOUND)?;

    db::send_friend_request(&state.db, authenticated_user, target_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(StatusCode::OK)
}

/// Accepts a pending friend request from another user.
pub(crate) async fn accept_friend(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Path(requester_id): Path<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    db::accept_friend_request(&state.db, authenticated_user, requester_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(StatusCode::OK)
}
