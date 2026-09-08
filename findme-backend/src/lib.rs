pub mod auth;
pub mod db;
pub mod handlers;
pub mod models;

use crate::auth::auth_middleware;
use axum::middleware::from_fn_with_state;
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
        .route("/inbox/{receiver_id}", get(handlers::get_inbox))
        .route("/inbox", post(handlers::receive_location))
        .route("/friends/requests", post(handlers::request_friend))
        .route(
            "/friends/requests/{id}/accept",
            put(handlers::accept_friend),
        )
        .route("/keys", post(handlers::upload_keys))
        .route("/keys/count", get(handlers::get_key_count))
        .route("/keys/{user_id}", get(handlers::get_prekey_bundle))
        .route_layer(from_fn_with_state(state.clone(), auth_middleware))
        .route(
            "/",
            get(|| async { "Welcome to the FindMe Server! (Status: Online)" }),
        )
        .route("/users/register", post(handlers::register_user))
        .with_state(state)
}
