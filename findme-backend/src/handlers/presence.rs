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

    let friends = db::get_friends_by_id(&state.db, authenticated_user)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    for friend in friends {
        let device_token = db::get_device_token(&state.db, friend.user_id)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        // Send silent push notification if we have a push_service and a token
        if let (Some(push_service), Some(tokens)) = (&state.push_service, device_token) {
            if let Some(fcm) = tokens.fcm_token {
                let _ = push_service.send_fcm_wake_up(&fcm).await;
            } else if let Some(apns) = tokens.apns_token {
                let _ = push_service.send_apns_wake_up(&apns).await;
            }
        }
    }

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
