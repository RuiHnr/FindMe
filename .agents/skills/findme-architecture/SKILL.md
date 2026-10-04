---
name: findme-architecture
description: Overview of FindMe system architecture, End-to-End Encryption (E2EE) protocol, mobile client strategy (KMP + Native UI), and phase execution plan.
---

# FindMe Architecture Skill

High-level architecture, design decisions, and component interaction models for **FindMe**.

## 1. System Vision

FindMe is an open-source, battery-efficient, privacy-focused alternative for continuous location
sharing among friends and family. The core principle: **the server never sees plaintext location
data.**

## 2. Component Overview

```
┌──────────────────────────┐
│    iOS App (SwiftUI)     │──┐
└──────────────────────────┘  │
                              ├── findme-kmp (Kotlin Multiplatform)
┌──────────────────────────┐  │   ├── Ktor HTTP Client
│  Android App (Compose)   │──┘   ├── Signal Protocol (X3DH + Double Ratchet)
└──────────────────────────┘      ├── SQLDelight Local DB
                                  ├── SecureStorage (Keystore / Keychain)
                                  └── Repositories (StateFlow → UI)
                                           │
                                           │ HTTPS (JWT Bearer Auth)
                                           ▼
                              ┌──────────────────────────┐
                              │  findme-backend (Rust)    │
                              │  Axum + Tokio + sqlx      │
                              │  PostgreSQL               │
                              └──────────────────────────┘
```

## 3. Security & E2EE Protocol

FindMe implements a simplified version of the **Signal Protocol**:

### Key Hierarchy

| Key                  | Algorithm   | Lifetime        | Storage Location       |
|----------------------|-------------|-----------------|------------------------|
| Identity Key (DH)    | X25519      | Permanent       | SecureStorage (client) + public on server |
| Identity Key (Sign)  | Ed25519     | Permanent       | SecureStorage (client) + public on server |
| Signed PreKey (SPK)  | X25519      | Rotatable       | SecureStorage (client) + public on server |
| One-Time PreKeys     | X25519      | Single-use      | SQLDelight DB (client) + public on server |
| Ratchet Keys         | X25519      | Per-message      | Ephemeral, derived     |
| Chain Keys           | HKDF-SHA256 | Per-chain        | Inside DoubleRatchetState (SecureStorage) |

### Protocol Flow

1. **Registration:** Client generates Identity Key pair (X25519 + Ed25519), Signed PreKey (signed
   by Ed25519 Identity Key), and batch of One-Time PreKeys. Public keys uploaded to server.
2. **Session Establishment (X3DH):** When Alice wants to message Bob for the first time:
   - Fetches Bob's PreKey Bundle from server (identity key, signed prekey, one OTPK).
   - Performs Extended Triple Diffie-Hellman to derive a shared secret.
   - Initializes a Double Ratchet session.
   - Sends a `PreKeySignalEnvelope` containing X3DH public parameters + first encrypted message.
3. **Ongoing Messages (Double Ratchet):** Subsequent messages use `NormalSignalEnvelope` with
   the ratcheting session providing forward secrecy and break-in recovery.
4. **Server Role:** The backend stores encrypted blobs in `location_inbox`, delivers them to
   the recipient, then deletes them (`DELETE ... RETURNING`). It never decrypts.

### TOFU Identity Pinning

On first contact with a friend, their identity public key is stored in the local
`identity_pin` SQLDelight table. On subsequent sessions, the key is compared — a mismatch
indicates a potential MITM attack (similar to Signal's "Safety Number changed" warning).

## 4. Mobile Client Strategy

- **Shared Logic (KMP):** All business logic, cryptography, networking, and local DB access lives
  in `findme-kmp`. Native apps are thin UI shells.
- **Native UI:** Jetpack Compose (Android), SwiftUI (iOS). No shared UI code.
- **Background Location:**
  - Android: `FusedLocationProviderClient` with `ForegroundService` (prototype in
    `spikes/androidlocationspike/`). `PRIORITY_BALANCED_POWER_ACCURACY`, 10s interval.
  - iOS: `CLLocationManager` with Significant-Change Location Service.

## 5. Repository Structure

```
FindMe/
├── AGENTS.md                                # Root agent context (always loaded)
├── docs/
│   ├── progress.md                          # Current progress & immediate next steps
│   ├── architecture/
│   │   ├── schema.dbml                      # Backend PostgreSQL schema (DBML format)
│   │   └── cryptography.md                  # E2EE protocol design notes
│   └── findme-kmp/
│       ├── plan.md                          # KMP implementation progress & roadmap checklist
│       ├── Signal Implementation Guide.md   # Step-by-step Signal Protocol implementation guide
│       ├── Repository Layer Guide.md        # Repository pattern and reactive state guide
│       ├── Local DB Migration Guide.md      # SQLDelight migration from SecureStorage guide
│       └── Background Sync Guide.md         # FCM/APNs push-driven sync guide
├── findme-backend/                          # Rust Axum backend
│   ├── Cargo.toml
│   ├── backend-plan.md
│   └── src/                                 # Modular: handlers/, db/, models/ subdirectories
├── findme-kmp/                              # Kotlin Multiplatform shared core
│   ├── build.gradle.kts
│   └── src/                                 # commonMain, androidMain, iosMain, commonTest, etc.
├── spikes/
│   └── androidlocationspike/                # Android foreground service location prototype
└── gradle/
    └── libs.versions.toml                   # Version catalog (AGP, Kotlin, Ktor, SQLDelight, etc.)
```

## 6. Implementation Progress

For the authoritative checklist of completed vs. pending steps, see
[docs/findme-kmp/plan.md](file:///c:/Users/lauri/Documents/OwnProjects/FindMe/docs/findme-kmp/plan.md).

**Completed:** KMP project setup, shared models, secure storage, Ktor HTTP client, Signal
Protocol (X3DH + Double Ratchet), repository layer, SQLDelight local DB migration, OTPK
management, TOFU identity pinning.

**In Progress:** Background Sync (FCM/APNs push notifications).

**Upcoming:** Session Healing (auto-reset on repeated decrypt failures).
