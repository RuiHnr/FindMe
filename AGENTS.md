# FindMe - Agent Context & Guidelines

## 1. Project Overview
**FindMe** is an open-source, cross-platform mobile application (iOS & Android) designed for continuous, privacy-first background location sharing among friends and family.

### Key Architecture Principles
1. **Uncompromising Privacy (E2EE):** The server acts strictly as an encrypted data relay (`location_inbox`). Location payloads are encrypted on the client side using public-key cryptography (Diffie-Hellman / public key exchange) before transmission. The server cannot decrypt location data.
2. **Extreme Battery Efficiency:** Native OS location services (Android Fused Location Provider, iOS Significant-Change Location Service) integrated via Kotlin Multiplatform (KMP) & native UI layers (Jetpack Compose / Swift UI).
3. **Lightweight Rust Backend:** Built using Axum, Tokio, sqlx, and PostgreSQL for minimal footprint and simple self-hosting.

---

## 2. Repository Structure

```
FindMe/
├── README.md                      # Project architecture & phased execution plan
├── AGENTS.md                      # Project guidelines & agent context (this file)
├── docs/
│   ├── progress.md                # Overview of current progress & immediate next steps
│   └── architecture/
│       └── schema.dbml            # DBML Database schema definition
├── findme-backend/                # Rust Axum Backend
│   ├── Cargo.toml                 # Dependencies (axum 0.8, tokio 1.36, sqlx 0.7, serde, uuid)
│   ├── backend-plan.md            # Backend completion roadmap & tasks
│   └── src/
│       ├── main.rs                # Axum server setup & route definitions
│       ├── db.rs                  # Database table init & SQL query execution
│       ├── handlers.rs            # Axum HTTP request handler functions
│       └── models.rs              # Data models, request/response structs
└── spikes/
    └── androidlocationspike/      # Android background location prototype (Jetpack Compose + Kotlin)
```

---

## 2.1 Progress & Next Steps
For a detailed breakdown of completed milestones, immediate action items, and phased status, see [docs/progress.md](file:///C:/Users/lauri/Documents/OwnProjects/FindMe/docs/progress.md).

---

## 3. Rust Backend Architecture (`findme-backend`)

### Tech Stack
- **Framework:** Axum (`v0.8.8`)
- **Async Runtime:** Tokio (`v1.36`)
- **Database:** PostgreSQL via `sqlx` (`v0.7`) with `PgPool`
- **Data Serialization:** `serde` & `uuid`

### Current Endpoints
- `GET /`: Health check endpoint.
- `POST /users/register`: Registers a user (`username`, `pub_key`), inserts into `users` table, returns generated `user_id` UUID.
- `POST /inbox`: Accepts an encrypted location payload (`sender_id`, `receiver_id`, `encrypted_blob`) and inserts it into `location_inbox`.
- `GET /inbox/{receiver_id}`: Fetches and deletes all queued messages for `receiver_id` from `location_inbox`.

### Security & Known Issues
- **Inbox DoS Vulnerability:** Currently, `GET /inbox/{receiver_id}` is unauthenticated. Anyone knowing a user's UUID can fetch and delete their incoming location messages. **Priority Fix:** Implement JWT / session token authentication via Axum middleware to ensure only the authenticated owner can fetch their inbox.

---

## 4. Key Development Rules & Workflows

1. **Keep Server Dumb:** Never add logic or fields to the backend that attempt to parse or decrypt location data. All location payloads are opaque strings (`encrypted_blob`).
2. **Error Handling in Backend:** Use explicit Axum response types and HTTP status codes (`StatusCode::UNAUTHORIZED`, `StatusCode::BAD_REQUEST`, etc.) rather than panicking or swallowing errors silently.
3. **Database Schema Sync:** Ensure any changes to `src/db.rs` queries align with `docs/architecture/schema.dbml`.
4. **Rust Edition:** Uses Rust 2024 edition (`Cargo.toml`). Ensure code is formatted with standard `cargo fmt` conventions.
