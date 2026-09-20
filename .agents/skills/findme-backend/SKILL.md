---
name: findme-backend
description: Detailed guidelines, endpoints, database schema, security procedures, and development workflows for the FindMe Rust backend.
---

# FindMe Backend Skill & Guide

Context and instructions for developing, testing, and expanding the **FindMe** Rust backend
located in `findme-backend/`.

## Framework & Dependencies

- **Axum 0.8**: HTTP framework. Uses `Router::new().route(...)` with handler functions.
- **sqlx 0.7**: Async PostgreSQL (`PgPool`). Raw SQL queries, no ORM.
- **Tokio 1.36**: Multi-threaded async runtime (`#[tokio::main]`).
- **serde / serde_json**: JSON serialization for all request/response types.
- **uuid**: V4 UUIDs for all primary keys.
- **jsonwebtoken**: JWT generation and validation for auth.
- **Rust Edition**: 2024.

## Source Map

```
findme-backend/src/
├── main.rs              # Entry point: connects to Postgres, calls init_db, starts server on :3000
├── lib.rs               # Router setup, middleware registration, AppState struct
├── auth.rs              # JWT auth_middleware (extracts Bearer token → Extension<Uuid>)
├── handlers/
│   ├── mod.rs           # Re-exports all handler submodules
│   ├── auth.rs          # POST /users/register, POST /users/login
│   ├── friends.rs       # POST /friends/requests, PUT /friends/requests/:id/accept, GET /friends, GET /friends/requests
│   ├── keys.rs          # POST /keys, GET /keys/:user_id, GET /keys/count
│   └── location.rs      # POST /inbox, GET /inbox
├── db/
│   ├── mod.rs           # Re-exports + init_db() that creates all tables
│   ├── auth.rs          # create_user, find_user_by_username, find_user_by_id
│   ├── friends.rs       # insert_friend_request, accept_friend_request, get_friends, get_friend_requests, are_friends
│   ├── keys.rs          # store_signed_prekey, store_onetime_prekeys, get_prekey_bundle, consume_onetime_prekey, count_onetime_prekeys
│   └── location.rs      # insert_location, fetch_inbox (DELETE...RETURNING)
└── models/
    ├── mod.rs           # Re-exports all model submodules
    ├── auth.rs          # RegisterRequest, LoginRequest, RegisterResponse, LoginResponse
    ├── friends.rs       # FriendRequestPayload, FriendDto, FriendRequest (DB row)
    ├── keys.rs          # UploadKeysRequest, PreKeyBundleResponse, SignedPreKeyRow, OneTimePreKeyRow
    └── location.rs      # SubmitLocationRequest, InboxMessage
```

## API Endpoints (Complete)

All endpoints except registration and login require JWT Bearer token authentication via
`auth_middleware`. The middleware injects the authenticated user's UUID as `Extension<Uuid>`.

### Public (No Auth)

| Method | Path               | Handler                  | Description                              |
|--------|--------------------|--------------------------|------------------------------------------|
| GET    | `/`                | health check             | Returns 200                              |
| POST   | `/users/register`  | `handlers::auth::register` | Creates user, returns UUID + JWT token |
| POST   | `/users/login`     | `handlers::auth::login`  | Validates credentials, returns JWT token |

### Authenticated

| Method | Path                                | Handler                            | Description                                                          |
|--------|-------------------------------------|------------------------------------|----------------------------------------------------------------------|
| POST   | `/friends/requests`                 | `handlers::friends::send_request`  | Send friend request (body: `target_username`)                        |
| PUT    | `/friends/requests/:id/accept`      | `handlers::friends::accept`        | Accept a pending friend request                                      |
| GET    | `/friends`                          | `handlers::friends::get_friends`   | List accepted friends                                                |
| GET    | `/friends/requests`                 | `handlers::friends::get_requests`  | List pending friend requests                                         |
| POST   | `/keys`                             | `handlers::keys::upload_keys`      | Upload signed prekey + batch of one-time prekeys                     |
| GET    | `/keys/:user_id`                    | `handlers::keys::get_bundle`       | Fetch a user's prekey bundle (signed + one OTPK consumed)            |
| GET    | `/keys/count`                       | `handlers::keys::get_count`        | Get remaining OTPK count for the authenticated user                  |
| POST   | `/inbox`                            | `handlers::location::receive_location` | Submit encrypted location blob to a friend's inbox             |
| GET    | `/inbox`                            | `handlers::location::get_inbox`    | Fetch and DELETE all pending inbox messages. Returns `X-Remaining-PreKeys` header |

## Database Schema (PostgreSQL)

Reference: `docs/architecture/schema.dbml`

| Table              | Key Columns                                                  | Notes                                              |
|--------------------|--------------------------------------------------------------|----------------------------------------------------|
| `users`            | `id (UUID PK)`, `username`, `password_hash`, `public_key_dh`, `public_key_sign`, `created_at` | Long-term identity public keys stored at registration |
| `friendships`      | `id (UUID PK)`, `requester_id`, `addressee_id`, `status`    | Status: `'pending'` or `'accepted'`                |
| `signed_prekeys`   | `id (UUID PK)`, `user_id`, `key_id (INT)`, `public_key`, `signature` | One active signed prekey per user          |
| `onetime_prekeys`  | `id (UUID PK)`, `user_id`, `key_id (INT)`, `public_key`     | Consumed (deleted) on fetch via `get_prekey_bundle`|
| `location_inbox`   | `id (UUID PK)`, `sender_id`, `receiver_id`, `encrypted_payload`, `created_at` | Deleted on fetch via `fetch_inbox` |

## Auth Flow

1. Client calls `POST /users/register` with `username`, `password`, `public_key_dh`, `public_key_sign`.
2. Backend hashes password, inserts user, generates JWT with `user_id` claim, returns `{ user_id, token }`.
3. All subsequent requests include `Authorization: Bearer <token>`.
4. `auth_middleware` in `auth.rs` validates JWT, extracts `user_id`, injects as `Extension<Uuid>`.

## Key Development Rules

1. **Server is a dumb relay.** Never parse, decrypt, or inspect `encrypted_payload` / `encrypted_blob`. They are opaque strings.
2. **Friendship gate on inbox.** `receive_location` checks `are_friends()` before inserting. Returns `403 FORBIDDEN` if not friends.
3. **Explicit HTTP status codes.** Use `StatusCode::BAD_REQUEST`, `StatusCode::UNAUTHORIZED`, etc. Never panic or swallow errors.
4. **Schema sync.** Any changes to `db/*.rs` table creation must be reflected in `docs/architecture/schema.dbml`.
5. **Fire-and-forget patterns.** Use `tokio::spawn` for non-critical side-effects (like push dispatch) so they don't block the HTTP response.

## Local Development

```bash
# Start PostgreSQL (Docker)
docker run -d --name findme-pg -e POSTGRES_USER=findme -e POSTGRES_PASSWORD=supersecret -e POSTGRES_DB=findme_db -p 5432:5432 postgres:16

# Run server
cd findme-backend && cargo run
# Server starts on http://localhost:3000

# Check compilation
cargo check

# Format
cargo fmt

# Run tests
cargo test
```
