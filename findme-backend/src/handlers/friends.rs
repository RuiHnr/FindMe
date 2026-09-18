use crate::{AppState, db};
use crate::models::FriendRequest;
use axum::{
    Extension, Json,
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
};
use uuid::Uuid;

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

/// Retrieves all friends of a user
pub(crate) async fn get_friends(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    let friends = db::get_friends_by_id(&state.db, authenticated_user)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok((StatusCode::OK, Json(friends)).into_response())
}

/// Retrieves all pending/incoming friend requests for a user
pub(crate) async fn get_friend_requests(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    let friend_requests = db::get_friend_requests_by_id(&state.db, authenticated_user)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok((StatusCode::OK, Json(friend_requests)).into_response())
}
