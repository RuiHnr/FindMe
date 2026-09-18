use crate::models::SubmitLocationRequest;
use crate::{AppState, db};
use axum::{
    Extension, Json,
    extract::State,
    http::StatusCode,
    response::IntoResponse,
};
use uuid::Uuid;

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
) -> Result<impl IntoResponse, StatusCode> {
    match db::fetch_inbox(&state.db, authenticated_user).await {
        Ok(messages) => Ok((StatusCode::OK, Json(messages)).into_response()),
        Err(e) => {
            eprintln!("DB-Error fetching inbox: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}
