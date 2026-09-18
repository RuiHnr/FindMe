# FindMe Backend

This is the lightweight, privacy-first Rust backend for the **FindMe** mobile application. It acts purely as a dumb relay for End-to-End Encrypted (E2EE) location data. The server cannot decrypt or read the contents of any location payload, guaranteeing complete privacy for users.

## 🛠 Tech Stack

* **Framework:** Axum (`v0.8.8`)
* **Async Runtime:** Tokio (`v1.36`)
* **Database:** PostgreSQL via `sqlx` (`v0.9.0`)
* **Authentication:** JSON Web Tokens (JWT) via `jsonwebtoken`
* **Rust Edition:** 2024

## 📁 Architecture

The codebase is highly modular, mirroring the entity-based architecture of the FindMe KMP client.

```
findme-backend/
├── src/
│   ├── models/    # Data models & request/response DTOs (auth, friends, keys, location)
│   ├── db/        # SQL queries and database interaction logic
│   ├── handlers/  # Axum HTTP route handlers
│   ├── auth.rs    # JWT Authentication middleware
│   ├── lib.rs     # Router definition & App state
│   └── main.rs    # Server entry point
├── tests/         # Comprehensive integration tests using axum-test
└── Cargo.toml     # Dependencies
```

## 🔒 Security Principles
1. **Uncompromising Privacy (E2EE):** Location payloads are encrypted on the client side using public-key cryptography (Signal X3DH Protocol) before transmission. The server sees only opaque `encrypted_blob` strings.
2. **Authenticated Access:** All endpoints (except registration) require a valid JWT token. Users can only access their own inboxes and can only receive location payloads from confirmed friends.
3. **No Centralized Tracking:** Location messages are delivered once. The `GET /inbox/{receiver_id}` endpoint automatically deletes messages from the database upon retrieval.

## 🚀 Getting Started

### Prerequisites
* Rust toolchain (stable)
* PostgreSQL database

### Environment Variables
Create a `.env` file in the `findme-backend` directory with the following variables:
```env
DATABASE_URL=postgres://username:password@localhost:5432/findme_db
JWT_SECRET=your_super_secret_jwt_key
```

### Running the Server
Start the backend using cargo:
```bash
cargo run
```
The server will start and listen on `http://0.0.0.0:3000`.

### Running Tests
The project includes a comprehensive suite of integration tests that verify database logic and endpoint authentication.
```bash
cargo test
```

## 📡 Core API Endpoints

### Authentication
* `POST /users/register` - Registers a new user and returns a JWT token.

### Friends
* `POST /friends/requests` - Send a friend request.
* `PUT /friends/requests/{id}/accept` - Accept a pending friend request.
* `GET /friends` - Get a list of all accepted friends.
* `GET /friends/requests` - Get a list of all pending friend requests.

### Location Inbox
* `POST /inbox` - Submit an encrypted location payload to a friend.
* `GET /inbox/{receiver_id}` - Fetch and consume (delete) all pending location messages for the authenticated user.

### Signal Protocol Keys (E2EE)
* `POST /keys` - Upload Signed PreKeys and One-Time PreKeys.
* `GET /keys/{user_id}` - Fetch a PreKey bundle to initiate an X3DH secure session.
* `GET /keys/count` - Get the count of remaining One-Time PreKeys for the authenticated user.
