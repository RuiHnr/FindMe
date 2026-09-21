pub mod auth;
pub mod db;
pub mod handlers;
pub mod models;

use crate::auth::auth_middleware;
use axum::middleware::from_fn_with_state;
use axum::routing::delete;
use axum::{
    Router,
    routing::{get, post, put},
};

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::PgPool,
    pub jwt_secret: String,
}

/// Defines all Endpoints and returns the router.
pub fn build_router(state: AppState) -> Router {
    Router::new()
        // Location
        .route("/inbox", get(handlers::location::get_inbox))
        .route("/inbox", post(handlers::location::receive_location))
        // Friends
        .route("/friends", get(handlers::friends::get_friends))
        .route(
            "/friends/requests",
            get(handlers::friends::get_friend_requests),
        )
        .route("/friends/requests", post(handlers::friends::request_friend))
        .route(
            "/friends/requests/{id}/accept",
            put(handlers::friends::accept_friend),
        )
        // Keys
        .route("/keys", post(handlers::keys::upload_keys))
        .route("/keys/count", get(handlers::keys::get_key_count))
        .route("/keys/{user_id}", get(handlers::keys::get_prekey_bundle))
        .route_layer(from_fn_with_state(state.clone(), auth_middleware))
        // Presence
        .route("/presence", post(handlers::presence::heartbeat))
        .route("/presence", delete(handlers::presence::remove))
        // All endpoints below are not authenticated via JWT
        .route(
            "/",
            get(|| async { "Welcome to the FindMe Server! (Status: Online)" }),
        )
        .route("/users/register", post(handlers::auth::register_user))
        .with_state(state)
}
