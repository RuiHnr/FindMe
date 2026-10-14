use crate::error::IntoStatusCode;
use crate::models::{KeyCountResponse, UploadKeysRequest};
use crate::{AppState, db};
use axum::{
    Extension, Json,
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
};
use uuid::Uuid;

/// Uploads or refreshes a user's Signal Signed PreKey and/or pool of One-Time PreKeys.
pub(crate) async fn upload_keys(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
    Json(payload): Json<UploadKeysRequest>,
) -> Result<impl IntoResponse, StatusCode> {
    if let Some(signed_prekey) = &payload.signed_prekey {
        db::upsert_signed_prekey(&state.db, authenticated_user, signed_prekey)
            .await
            .or_500()?;
    }

    if let Some(one_time_prekeys) = &payload.one_time_prekeys {
        db::insert_onetime_prekeys(&state.db, authenticated_user, one_time_prekeys)
            .await
            .or_500()?;
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
            .or_500()?;
        if !friends {
            return Err(StatusCode::FORBIDDEN);
        }
    }

    let key_bundle = db::fetch_prekey_bundle(&state.db, target_user)
        .await
        .or_500()?
        .ok_or(StatusCode::NOT_FOUND)?;
    Ok((StatusCode::OK, Json(key_bundle)).into_response())
}

/// Returns the count of remaining one-time prekeys for the authenticated user so they know when to replenish.
pub(crate) async fn get_key_count(
    State(state): State<AppState>,
    Extension(authenticated_user): Extension<Uuid>,
) -> Result<impl IntoResponse, StatusCode> {
    let count = db::count_onetime_prekeys(&state.db, authenticated_user)
        .await
        .or_500()?;

    Ok((
        StatusCode::OK,
        Json(KeyCountResponse {
            remaining_one_time_prekeys: count,
        }),
    )
        .into_response())
}
