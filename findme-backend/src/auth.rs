use axum::extract::{Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::IntoResponse;
use jsonwebtoken::{decode, DecodingKey, Validation};
use crate::AppState;
use crate::models::Claims;

pub async fn auth_middleware(
    State(state): State<AppState>,
    mut req: Request,
    next: Next
) -> Result<impl IntoResponse, StatusCode> {
    // 1. Get the auth header
    let auth_header = req.headers()
        .get("Authorization")
        .and_then(|h| h.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;

    // 2. Check its starting is correct
    if !auth_header.starts_with("Bearer ") {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let token = &auth_header[7..];

    // 3. Decode the token
    let decoding_key = DecodingKey::from_secret(state.jwt_secret.as_bytes());
    let token_data = decode::<Claims>(
        token,
        &decoding_key,
        &Validation::default(),
    ).map_err(|_| StatusCode::UNAUTHORIZED)?;

    // 4. Inject the user ID into request extensions
    req.extensions_mut().insert(token_data.claims.sub);

    // 5. Proceed to the next middleware or handler
    Ok(next.run(req).await)
}