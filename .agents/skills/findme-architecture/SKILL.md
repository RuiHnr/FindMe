---
name: findme-architecture
description: Overview of FindMe system architecture, End-to-End Encryption (E2EE) protocol, mobile client strategy (KMP + Native UI), and phase execution plan.
---

# FindMe Architecture Skill

This skill documents the high-level architecture, design decisions, and component interaction models for **FindMe**.

## 1. System Vision
FindMe is an open-source, battery-efficient, privacy-focused alternative for continuous location sharing among friends and family.

## 2. Security & E2EE Concept
- **Client-Side Cryptography**: Key pairs generated on client device during registration. Static public key stored on server for initial Diffie-Hellman (DH) key exchange.
- **Relay-Only Server**: The backend never decrypts or inspects location data. Location coordinates, timestamps, and metadata are encrypted into an opaque ciphertext string (`encrypted_blob`) on the sender's device.
- **Inbox Queue**: Location updates sit in `location_inbox` until pulled by the recipient client, after which they are deleted (`DELETE ... RETURNING`).

## 3. Mobile Strategy (Phase 3 & 4)
- **Shared Logic**: Kotlin Multiplatform (KMP) for cryptography, local database storage (SQLDelight/Room), and API client networking.
- **Native UI**: Jetpack Compose (Android) and SwiftUI (iOS).
- **Background Location**:
  - Android: `FusedLocationProviderClient` with background `ForegroundService` / `WorkManager` (prototype in `spikes/androidlocationspike`).
  - iOS: `CLLocationManager` with Significant-Change Location Service or visits API for minimal power drain.

## 4. Database Schema Summary (`docs/architecture/schema.dbml`)
- **users**: `id (UUID)`, `username (VARCHAR)`, `public_key (TEXT)`, `created_at`
- **friendships**: `user_id_a (UUID)`, `user_id_b (UUID)`, `status (VARCHAR)`, `created_at`
- **location_inbox**: `id (UUID)`, `sender_id (UUID)`, `receiver_id (UUID)`, `encrypted_payload (TEXT)`, `created_at`
