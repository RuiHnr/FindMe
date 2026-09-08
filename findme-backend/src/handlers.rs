use crate::models::{
    Claims, FriendRequest, KeyCountResponse, RegisterRequest, RegisterResponse,
    UploadKeysRequest,
};
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

/// Uploads or refreshes a user's Signal Signed PreKey and/or pool of One-Time PreKeys.
pub(crate) async fn upload_keys(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Json(payload): Json<UploadKeysRequest>,
) -> Result<impl IntoResponse, StatusCode> {
    if let Some(signed_prekey) = &payload.signed_prekey {
        db::upsert_signed_prekey(&state.db, authenticated_user, signed_prekey)
            .await
            .map_err(|e| {
                eprintln!("DB-Error uploading signed prekey: {}", e);
                StatusCode::INTERNAL_SERVER_ERROR
            })?;
    }

    if let Some(one_time_prekeys) = &payload.one_time_prekeys {
        db::insert_onetime_prekeys(&state.db, authenticated_user, one_time_prekeys)
            .await
            .map_err(|e| {
                eprintln!("DB-Error uploading one-time prekeys: {}", e);
                StatusCode::INTERNAL_SERVER_ERROR
            })?;
    }

    Ok(StatusCode::OK)
}

/// Fetches a PreKey bundle for a target user to initiate a Signal X3DH session.
/// Atomically pops one One-Time PreKey to ensure forward secrecy and single use.
pub(crate) async fn get_prekey_bundle(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Path(target_user): Path<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    // Only friends (or the user themselves) can request prekey bundles to prevent exhaustion DoS attacks
    if authenticated_user != target_user {
        let friends = db::are_friends(&state.db, authenticated_user, target_user)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
        if !friends {
            return Err(StatusCode::FORBIDDEN);
        }
    }

    match db::fetch_prekey_bundle(&state.db, target_user).await {
        Ok(Some(bundle)) => Ok((StatusCode::OK, Json(bundle)).into_response()),
        Ok(None) => Err(StatusCode::NOT_FOUND),
        Err(e) => {
            eprintln!("DB-Error fetching prekey bundle: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

/// Returns the count of remaining one-time prekeys for the authenticated user so they know when to replenish.
pub(crate) async fn get_key_count(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    match db::count_onetime_prekeys(&state.db, authenticated_user).await {
        Ok(count) => Ok((StatusCode::OK, Json(KeyCountResponse { remaining_one_time_prekeys: count })).into_response()),
        Err(e) => {
            eprintln!("DB-Error counting prekeys: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

