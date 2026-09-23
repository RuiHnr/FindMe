use axum::http::StatusCode;
use std::fmt::Display;

/// A simple extension trait to easily convert any Error into a 500 Internal Server Error StatusCode.
pub trait IntoStatusCode<T> {
    /// Maps the error to `StatusCode::INTERNAL_SERVER_ERROR` and optionally prints it.
    fn or_500(self) -> Result<T, StatusCode>;
}

impl<T, E: Display> IntoStatusCode<T> for Result<T, E> {
    fn or_500(self) -> Result<T, StatusCode> {
        self.map_err(|e| {
            eprintln!("Internal Error: {}", e);
            StatusCode::INTERNAL_SERVER_ERROR
        })
    }
}
