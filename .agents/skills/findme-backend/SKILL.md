---
name: findme-backend
description: Detailed guidelines, endpoints, database schema, security procedures, and development workflows for the FindMe Rust backend.
---

# FindMe Backend Skill & Guide

This skill provides context and instructions for developing, testing, and expanding the **FindMe** Rust backend located in `findme-backend/`.

## Framework & Dependencies
- **Axum 0.8**: Uses modern Axum routing syntax (e.g. `Router::new().route(...)`).
- **sqlx 0.7**: Asynchronous PostgreSQL pool (`PgPool`). DB initialization currently runs in `db::init_db(&pool)` at startup.
- **Tokio**: Asynchronous multi-threaded runtime (`#[tokio::main]`).
- **UUID v4**: Used for unique IDs across users, inbox items, and friendships.

## Codebase Modules
- `src/main.rs`: Entry point. Connects to PostgreSQL, initializes DB schema, configures Axum router, starts TCP listener on port 3000.
- `src/db.rs`: Database layer functions (`init_db`, `create_user`, `insert_location`, `get_inbox`).
- `src/handlers.rs`: HTTP request handlers converting HTTP JSON inputs/parameters into DB actions and returning Axum HTTP responses.
- `src/models.rs`: Serde DTOs (`RegisterRequest`, `RegisterResponse`, `LocationPayload`, `InboxMessage`).

## Immediate Tasks & Roadmap
1. **Authentication Middleware**:
   - Implement authentication tokens (e.g., JWT using `jsonwebtoken` crate or session tokens) upon user registration/login.
   - Create an Axum extractor or middleware (`axum::middleware::from_fn`) to validate Authorization headers.
   - Restrict `GET /inbox/{receiver_id}` so users can only retrieve their own inbox messages.
2. **Friendship System**:
   - Create DB table `friendships` (`user_id_a`, `user_id_b`, `status` ['pending', 'accepted'], `created_at`).
   - Implement `POST /friends/request` and `PUT /friends/accept` endpoints.
   - Enforce check in `insert_location` so location packages can only be sent to confirmed friends.
3. **Containerization & Deployment**:
   - Write multi-stage `Dockerfile` targeting slim Alpine/Debian base images.
   - Set up `tracing` / `tracing-subscriber` for structured JSON logging.
   - Configure sqlx migration files (`migrations/`) for production schema management.

## Testing & Local Execution
- **Run server locally**: `cargo run` inside `findme-backend/`. Requires PostgreSQL container running at `postgres://findme:supersecret@localhost:5432/findme_db`.
- **Check compilation**: `cargo check` inside `findme-backend/`.
