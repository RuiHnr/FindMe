---
name: findme-kmp
description: Detailed guidelines, architecture patterns, cryptography rules, testing requirements, and iOS compilation constraints for the FindMe Kotlin Multiplatform (KMP) core module.
---

# FindMe KMP Core Skill & Guide

This skill provides critical context, instructions, and known constraints for developing, testing, and modifying the **FindMe** Kotlin Multiplatform core library located in `findme-kmp/`.

## Architecture & Responsibilities

The `findme-kmp` module acts as the shared brain for both Android and iOS clients. It is strictly responsible for:
1. **Network Communication (Ktor):** Talking to the Rust backend APIs.
2. **End-to-End Encryption (E2EE):** Implementing the Signal Protocol (X3DH, Double Ratchet).
3. **Secure Persistence:** Safely storing keys and tokens (Android Keystore, iOS Keychain).
4. **Reactive State Management:** Exposing data to native UIs via Kotlin `StateFlow`.

## Frameworks & Dependencies
- **Networking:** Ktor 3.x Client (`HttpClientFactory` with JSON ContentNegotiation & Auth plugins).
- **Serialization:** `kotlinx.serialization` (JSON). Uses `@Serializable` and polymorphic sealed classes (e.g. `@SerialName` discriminators for `SignalMessageEnvelope`).
- **Cryptography:** `dev.whyoleg.cryptography` for primitives (X25519, Ed25519, AES-256-GCM, HKDF-SHA256).
- **Concurrency:** Kotlin Coroutines (`suspend`, `StateFlow`).

## Cryptography & E2EE Rules
- **Lazy Initialization:** X3DH sessions are NOT established when a friend request is accepted. They are initialized lazily exactly when the user attempts to send their first location payload to that friend.
- **Identity Key Signature:** The `SignedPreKey` must be signed by the long-term Ed25519 **Identity Private Key**.
- **Double Ratchet:** Sessions must serialize their state to `SessionStore` (as JSON) after every turn of the ratchet to ensure keys are not lost if the app is killed.

## Testing & Mocking Constraints
We do not use MockK or Mockito. Tests run across native platforms and rely on Fakes and Ktor's `MockEngine`.
1. **HttpClientFactory:** Tests must instantiate clients using `HttpClientFactory.create(engine, secureStorage, baseUrl)` to ensure production plugins (like `Auth`) are applied.
2. **Auth Token Injection:** Because the Ktor `Auth` plugin evaluates `loadTokens { ... }` internally, you MUST manually inject a mock token into `InMemorySecureStorage` *before* instantiating the Ktor client in tests, or requests will fail with 401 Unauthorized.
3. **JSON Discriminators:** When faking JSON strings for sealed classes in MockEngine, ensure the discriminator field exactly matches the `@SerialName` annotation (e.g. `"type": "normal_message"`).

## Known iOS Compilation Quirks (CRITICAL)
- **Do not use fully qualified package names for Kotlin extension functions.** 
  - ❌ BAD: `io.ktor.serialization.kotlinx.json.json(Json { ... })`
  - ✅ GOOD: Import `io.ktor.serialization.kotlinx.json.json` at the top of the file, and call `json(Json { ... })` directly.
  - *Why?* Fully-qualified extension function calls will crash the Kotlin Native / iOS compiler during linkage.

## Local Execution
- **Run all KMP tests:** `./gradlew :findme-kmp:check`
- **Test Specific Targets:** `./gradlew :findme-kmp:iosSimulatorArm64Test` or `./gradlew :findme-kmp:testAndroidHostTest`
