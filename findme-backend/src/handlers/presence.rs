use crate::{AppState, db};
use axum::Extension;
use axum::extract::State;
use axum::http::StatusCode;
use uuid::Uuid;

/// Marks the authenticated user as actively watching.
/// Call this when the app opens, then repeat every ~30s as a heartbeat.
pub(crate) async fn heartbeat(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    db::upsert_presence(&state.db, authenticated_user)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    // TODO: Send Push Notification to user's friends with PresenceNotification
    Ok(StatusCode::OK)
}

/// Removes the authenticated user's active session.
/// Call when the app backgrounds or the user logs out.
pub(crate) async fn remove(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    db::delete_presence(&state.db, authenticated_user)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(StatusCode::OK)
}
