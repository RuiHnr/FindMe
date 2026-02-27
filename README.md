# Project Architecture & Implementation Plan: FindMe

## 1. Project Overview
**FindMe** is an open-source, cross-platform mobile application (iOS & Android) designed for continuous background location sharing among friends and family. 
**Core Philosophy:**
1. Uncompromising Privacy (End-to-End Encryption).
2. Extreme Battery Efficiency (Native OS integration).
3. Open-Source with a potential "Managed Hosting" business model.

## 2. Minimum Viable Product (MVP) Scope
The version 1.0 release is strictly limited to the following core user stories to ensure high-quality execution of the underlying infrastructure:
* **User Story 1:** A user can add another user to their friend list.
* **User Story 2:** A user can view the real-time/latest known location of their friends on an interactive map.

## 3. Tech Stack
To achieve the performance and privacy constraints, the project rejects heavy cross-platform UI frameworks in favor of a native-hybrid approach.

### Frontend (Mobile)
* **Core Logic:** Kotlin Multiplatform (KMP) - Handles networking, cryptography, and local database.
* **UI Layer:** Native Swift (iOS) & Jetpack Compose (Android) - Ensures fluid performance and native look & feel.
* **Location Services:** Pure Native APIs (e.g., iOS Significant-Change Location Service, Android Fused Location Provider) for maximum battery preservation.

### Backend (Server)
* **Language/Framework:** Rust (Axum/Actix-web) - Chosen for memory safety and minimal resource footprint, enabling cheap self-hosting.
* **Database:** PostgreSQL with PostGIS extension for highly efficient spatial data handling.
* **Architecture:** The server acts purely as a dumb relay for encrypted packets. It cannot read location data.

## 4. Phased Execution Strategy

### Phase 1: Architecture & Cryptography Design (Current)
* Define the End-to-End Encryption (E2EE) concept.
* Design the public-key cryptography protocol for secure location sharing without server-side decryption.
* Design database schemas (users, encrypted location blobs, friendship relations).

### Phase 2: UX & Permission Flows
* Wireframe the core map and friend list screens.
* Design a highly transparent onboarding flow to explain to users *why* "Always Allow" location permissions are required, mitigating App Store rejection risks.

### Phase 3: Technical Spike (Feasibility Test)
* Build a bare-bones native prototype (no UI, just logs).
* **Goal:** Successfully read background location every 10-15 minutes, encrypt it, and send it to a local test server while maintaining negligible battery drain.
* *Do not proceed to Phase 4 until this spike is successful.*

### Phase 4: Implementation
* Set up the Rust Backend and PostgreSQL database.
* Implement the KMP shared logic (Cryptography & Networking).
* Build the native UI layers and integrate the interactive map (e.g., Mapbox or native Apple/Google Maps).

### Phase 5: Compliance & Release Preparation
* Draft GDPR-compliant Privacy Policy (heavily relying on the E2EE architecture).
* Prepare App Store / Google Play Store review documentation, specifically justifying background location usage.
