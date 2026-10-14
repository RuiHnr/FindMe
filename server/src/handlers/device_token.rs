use axum::{debug_handler, Extension, Json};
use axum::extract::State;
use axum::http::StatusCode;
use uuid::Uuid;
use crate::{db, AppState};
use crate::error::IntoStatusCode;
use crate::models::device_token::DeviceToken;

/// Inserts / Updates device tokens for a user.
#[debug_handler]
pub(crate) async fn post_device_token(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Json(payload): Json<DeviceToken>,
) -> Result<StatusCode, StatusCode> {
    db::upsert_device_token(
        &state.db,
        authenticated_user,
        payload.fcm_token,
        payload.apns_token)
        .await
        .or_500()?;

    Ok(StatusCode::OK)
}