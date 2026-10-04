# FindMe - Agent Context & Guidelines

## 1. Project Overview
**FindMe** is an open-source, cross-platform mobile application (iOS & Android) designed for continuous, privacy-first background location sharing among friends and family.

### Key Architecture Principles
1. **Uncompromising Privacy (E2EE):** The server acts strictly as an encrypted data relay (`location_inbox`). Location payloads are encrypted on the client using the Signal Protocol (X3DH + Double Ratchet) before transmission. The server cannot decrypt location data.
2. **Extreme Battery Efficiency:** Native OS location services (Android Fused Location Provider, iOS Significant-Change Location Service) integrated via Kotlin Multiplatform (KMP) & native UI layers (Jetpack Compose / SwiftUI).
3. **Lightweight Rust Backend:** Built using Axum, Tokio, sqlx, and PostgreSQL for minimal footprint and simple self-hosting.

---

## 2. Repository Structure

```
FindMe/
├── AGENTS.md                      # Project guidelines & agent context (this file)
├── docs/
│   ├── progress.md                # Overview of current progress & immediate next steps
│   ├── architecture/
│   │   ├── schema.dbml            # Backend PostgreSQL schema definition (DBML)
│   │   └── cryptography.md        # E2EE protocol design notes
│   └── findme-kmp/
│       ├── plan.md                # KMP implementation roadmap & progress checklist
│       ├── Signal Implementation Guide.md
│       ├── Repository Layer Guide.md
│       ├── Local DB Migration Guide.md
│       └── Background Sync Guide.md
├── findme-backend/                # Rust Axum Backend
│   ├── Cargo.toml
│   ├── backend-plan.md
│   └── src/
│       ├── main.rs                # Entry point: Postgres connection, DB init, server start
│       ├── lib.rs                 # Router setup, middleware registration, AppState
│       ├── auth.rs                # JWT auth_middleware (Bearer → Extension<Uuid>)
│       ├── handlers/              # HTTP handlers (auth, friends, keys, location)
│       ├── db/                    # SQL query functions (auth, friends, keys, location)
│       └── models/                # Serde DTOs (auth, friends, keys, location)
├── findme-kmp/                    # Kotlin Multiplatform shared core
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/            # Models, Ktor Client, Signal Crypto, SQLDelight, Repositories
│       ├── androidMain/           # Android Keystore encryption, SQLite driver
│       ├── iosMain/               # iOS Keychain, NativeSqliteDriver
│       └── commonTest/            # Fakes (FakeBackend + MockEngine), unit & E2E tests
├── spikes/
│   └── androidlocationspike/      # Android foreground service location prototype
└── gradle/
    └── libs.versions.toml         # Version catalog (AGP 9.4, Kotlin 2.4, Ktor 3.6, SQLDelight 2.0)
```

---

## 2.1 Progress & Next Steps
For a detailed breakdown of completed milestones, immediate action items, and phased status, see [docs/findme-kmp/plan.md](file:///c:/Users/lauri/Documents/OwnProjects/FindMe/docs/findme-kmp/plan.md).

---

## 3. Rust Backend Architecture (`findme-backend`)

### Tech Stack
- **Framework:** Axum (`v0.8`)
- **Async Runtime:** Tokio (`v1.36`)
- **Database:** PostgreSQL via `sqlx` (`v0.7`) with `PgPool`
- **Auth:** JWT (jsonwebtoken crate) with Axum middleware
- **Data Serialization:** `serde`, `uuid`

### Current Endpoints

**Public:**
- `POST /users/register` — Creates user, returns UUID + JWT token
- `POST /users/login` — Validates credentials, returns JWT token

**Authenticated (JWT Bearer required):**
- `POST /friends/requests` — Send friend request
- `PUT /friends/requests/:id/accept` — Accept friend request
- `GET /friends` — List accepted friends
- `GET /friends/requests` — List pending requests
- `POST /keys` — Upload signed prekey + one-time prekeys
- `GET /keys/:user_id` — Fetch prekey bundle (consumes one OTPK)
- `GET /keys/count` — Get remaining OTPK count
- `POST /inbox` — Submit encrypted location to friend's inbox
- `GET /inbox` — Fetch & delete all queued inbox messages

---

## 4. Key Development Rules & Workflows

1. **Keep Server Dumb:** Never add logic or fields to the backend that attempt to parse or decrypt location data. All location payloads are opaque strings (`encrypted_blob` / `encrypted_payload`).
2. **Error Handling in Backend:** Use explicit Axum response types and HTTP status codes (`StatusCode::UNAUTHORIZED`, `StatusCode::BAD_REQUEST`, etc.) rather than panicking or swallowing errors silently.
3. **Database Schema Sync:** Ensure any changes to `db/*.rs` queries align with `docs/architecture/schema.dbml`.
4. **Rust Edition:** Uses Rust 2024 edition. Format with `cargo fmt`.
5. **No MockK/Mockito in KMP:** Tests use hand-written fakes and Ktor `MockEngine` for cross-platform compatibility.
6. **iOS Compilation:** Never use fully qualified Kotlin extension function calls — they crash the Kotlin Native compiler.

## 5. Detailed Skills

For deep-dive context on specific components, read the skills in `.agents/skills/`:

- **`findme-architecture`** — System architecture, E2EE protocol, component diagram
- **`findme-backend`** — Full source map, all endpoints, DB schema, auth flow
- **`findme-kmp`** — Full source map (49 files), data flows, crypto rules, storage split, testing patterns
