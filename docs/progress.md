# Project Progress & Roadmap: FindMe

**Last Updated:** September 2026

---

## 1. Executive Summary

FindMe is currently in **Phase 4: Implementation** of its roadmap.

- **Backend Infrastructure:** The core Rust (Axum + PostgreSQL) backend is setup and functional with modular separation
  (`main.rs`, `handlers.rs`, `db.rs`, `models.rs`).
- **Data Models & Endpoints:** Endpoints for user registration (`POST /users/register`), location package submission
  (`POST /inbox`), and payload consumption (`GET /inbox/{receiver_id}`) are implemented.
- **Spikes & Prototypes:** Android background location prototype (`spikes/androidlocationspike`) exists using Jetpack
  Compose + Fused Location Provider.

---

## 2. Completed Milestones

- [x] **Architecture & E2EE Design:** Defined End-to-End Encryption strategy where server acts as an opaque relay
  (`location_inbox`).
- [x] **Database Schema Definition:** Created DBML schema (`docs/architecture/schema.dbml`) defining `users`,
  `friendships`, and `location_inbox` tables.
- [x] **Rust Backend Initialization:** Setup Axum 0.8 server, Tokio runtime, and `sqlx` database pool.
- [x] **Modular Refactoring:** Refactored backend codebase into decoupled handlers, database queries, and Serde data
  models.

---

## 3. Immediate Next Steps (Action Items)

### Priority 1: Backend Security & Authentication

- [x] **JWT Authentication Implementation:**
    - Add `jsonwebtoken` dependency to `findme-backend/Cargo.toml`.
    - Issue signed JWT Bearer tokens upon user registration (`POST /users/register`).
    - Create Axum auth middleware (`axum::middleware::from_fn`) to validate Bearer tokens on protected endpoints.
- [x] **Close Inbox DoS Vulnerability:**
    - Restrict `GET /inbox/{receiver_id}` so that only the authenticated user matching `receiver_id` can fetch and
      delete their queued messages.

### Priority 2: Database Schema & Migration Tooling

- [ ] **`sqlx` Migrations Setup:**
    - Transition from dynamic runtime initialization in `db::init_db()` to managed `sqlx` migration files
      (`findme-backend/migrations/`).
    - Create baseline migration `0001_init_schema.sql` based on `schema.dbml`.
- [ ] **Friendship System Implementation:**
    - Implement DB table `friendships` (`user_id_a`, `user_id_b`, `status` ['pending', 'accepted'], `created_at`).
    - Create HTTP endpoints:
        - `POST /friends/request`
        - `PUT /friends/accept`
    - Enforce friendship checks in `POST /inbox` so location payloads can only be sent to accepted friends.

### Priority 3: Mobile Client Integration (KMP)

- [ ] **Kotlin Multiplatform Core Module:**
    - Set up `findme-kmp` module for shared client-side cryptography (Diffie-Hellman key pair generation, payload
      encryption/decryption) and networking.
- [ ] **UI Integration:**
    - Connect Android Jetpack Compose UI and background `LocationService` to the KMP networking layer.

---

## 4. Phase Status Overview

| Phase       | Description                                        | Status          |
|:------------|:---------------------------------------------------|:----------------|
| **Phase 1** | Architecture & Cryptography Design                 | **Completed**   |
| **Phase 2** | UX & Permission Flows                              | **In Progress** |
| **Phase 3** | Technical Spike (Android Background Location)      | **Completed**   |
| **Phase 4** | Implementation (Rust Backend, KMP Core, Native UI) | **In Progress** |
| **Phase 5** | Compliance & App Store Release                     | **Pending**     |
