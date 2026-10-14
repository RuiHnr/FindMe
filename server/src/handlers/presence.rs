use crate::error::IntoStatusCode;
use crate::push_service::SyncMode;
use crate::{AppState, db};
use axum::Extension;
use axum::extract::State;
use axum::http::StatusCode;
use uuid::Uuid;

async fn notify_friends_of_presence(
    state: &AppState,
    user_id: Uuid,
    mode: SyncMode,
) -> Result<(), StatusCode> {
    let friend_tokens = db::get_friends_device_tokens(&state.db, user_id)
        .await
        .or_500()?;

    if let Some(push_service) = &state.push_service {
        for tokens in friend_tokens {
            if let Some(fcm) = tokens.fcm_token
                && let Err(e) = push_service.send_fcm_sync_action(&fcm, mode).await
            {
                eprintln!("FCM push failed: {}", e);
            }
            if let Some(apns) = tokens.apns_token
                && let Err(e) = push_service.send_apns_sync_action(&apns, mode).await
            {
                eprintln!("APNS push failed: {}", e);
            }
        }
    }
    Ok(())
}

/// Marks the authenticated user as actively watching and sends a high mode push action.
/// Call this when the app opens, then repeat every ~30s as a heartbeat.
pub(crate) async fn heartbeat(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    db::upsert_presence(&state.db, authenticated_user)
        .await
        .or_500()?;
    notify_friends_of_presence(&state, authenticated_user, SyncMode::High).await?;
    Ok(StatusCode::OK)
}

/// Removes the authenticated user's active session and sends a low mode push action.
/// Call when the app backgrounds or the user logs out.
pub(crate) async fn remove(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<StatusCode, StatusCode> {
    db::delete_presence(&state.db, authenticated_user)
        .await
        .or_500()?;
    notify_friends_of_presence(&state, authenticated_user, SyncMode::Low).await?;
    Ok(StatusCode::OK)
}
