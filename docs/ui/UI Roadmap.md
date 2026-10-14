# FindMe Mobile UI & Application Integration Roadmap

Yes! With the core cryptography engine, repository layer, SQLDelight persistence, TOFU identity
pinning, and background sync logic completely implemented in `findme-kmp`, the project is **100%
ready** to begin UI development.

---

## Architecture Overview for UI Integration

The UI layer follows a clean **MVI / MVVM architecture** where native UIs (Jetpack Compose for
Android, SwiftUI for iOS) are thin, declarative presentation layers consuming reactive `StateFlow`
streams from `findme-kmp` repositories.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Native Presentation Layer                        │
│                                                                        │
│   Android (Jetpack Compose + M3)        iOS (SwiftUI)                  │
│   ├── AuthScreen / Onboarding           ├── AuthView                   │
│   ├── MainMapScreen (Google Maps)       ├── MapView (MapKit)           │
│   ├── FriendsListScreen                 ├── FriendsListView            │
│   └── SecurityVerificationScreen        └── SecurityVerificationView   │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                           ViewModels / StateFlow
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        findme-kmp Shared Core                          │
│                                                                        │
│   AuthRepository      FriendRepository      LocationRepository         │
│   (Auth state)        (Friends & Requests)  (Decrypted Locations)      │
│                                                     │                  │
│   SecurityRepository                        SQLDelight Local DB        │
│   (Safety Numbers & Pins)                   (Persisted Friends & Pins) │
└────────────────────────────────────────────────────────────────────────┘
```

---

## UI Roadmap & Execution Phases

### Phase 1: ViewModel & State Infrastructure (KMP - Android/iOS Bridge)

- **1.1 Screen State Models**: Define immutable UI State data classes for screens:
    - `AuthUiState` (`Unauthenticated`, `Registering`, `Authenticated`)
    - `MapUiState` (`Loading`, `Success(friendLocations: Map<String, LocationInfo>)`, `Error`)
    - `FriendsUiState` (`friendsList`, `pendingRequests`, `isSyncing`)
    - `SecurityUiState` (`IdentityPinInfo`, `safetyNumberFormatted`, `isMitmWarning`)
- **1.2 ViewModel Bridges**:
    - Implement Compose-compatible `ViewModel`s on Android using `androidx.lifecycle.ViewModel` and
      `viewModelScope`.
    - Expose `StateFlow<UiState>` and handle user actions (`UiIntent`).

---

### Phase 2: Android UI Implementation (Jetpack Compose + Material 3)

#### 2.1 Authentication & Registration Screen

- Clean Material 3 screen allowing new users to choose a username.
- Integrates with `AuthRepository.register(username)`.
- Displays initial Signal key pair generation progress indicator.

#### 2.2 Main Screen: Interactive Map

- Integrate **Google Maps Compose** (or **MapLibre Compose**).
- Render custom map markers for each friend with their avatar/initials, last updated timestamp ("2m
  ago"), and status.
- Clicking a friend's marker opens a bottom sheet with location details and direct access to their
  Safety Number.
- Shows current user's live location dot.

#### 2.3 Friends & Request Management Screen

- **Friends Tab**: List of active accepted friends with online status / last location update time.
- **Requests Tab**: Badge indicator for incoming friend requests with **Accept** and **Decline**
  buttons.
- **Add Friend Sheet**: Dialog/BottomSheet to search and send outbound friend requests by username.

#### 2.4 Security & Safety Number Screen

- Visual display of the **60-digit Safety Number** formatted in 12 blocks of 5 digits (
  `12345 67890 ...`).
- **QR Code Generator & Scanner**: Allows scanning a friend's phone in person to automatically
  verify their Identity Key.
- **Identity Mismatch Warning Screen**: Triggers if `IdentityKeyMismatchException` occurs,
  displaying an alert with options to **Verify Safety Number** or **Re-trust Key**.

#### 2.5 Foreground Location Service Integration

- Wire the prototype `LocationService` from `spikes/androidlocationspike/` to
  `LocationRepository.submitLocalLocation()`.
- Periodic GPS tracking using `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`.

---

### Phase 3: iOS UI Implementation (SwiftUI)

#### 3.1 KMP to Swift Async/Combine Adapters

- Use `KMP-NativeCoroutines` or Swift `AsyncSequence` adapters to expose `StateFlow` streams as
  `@Published` properties or `AsyncSequence` in SwiftUI.

#### 3.2 SwiftUI Screen Parity

- **`AuthView`**: Username registration screen.
- **`MainMapView`**: MapKit `Map` view with `Annotation` markers for friends.
- **`FriendsListView`**: List of friends and pending inbound requests.
- **`SafetyNumberView`**: Safety number display and QR code scanner.

#### 3.3 CoreLocation Integration

- Background location updates via `CLLocationManager` (Significant Location Change service) feeding
  into `LocationRepository`.

---

### Phase 4: UX Polish, Edge Cases & Verification

- **4.1 Identity Mismatch User Alerting**:
    - Distinct banner on friend row/map marker when `is_trusted == 0` or key mismatch is flagged.
- **4.2 Permissions Onboarding Flow**:
    - User-friendly permission requests for Location (Always Allow), Notifications, and Camera (for
      QR scanning).
- **4.3 Offline Queueing & Retry**:
    - Visual status indicator when offline (e.g. "Location updates queued locally").

---

## Suggested Next Immediate Task

We can begin with **Phase 2.1 & 2.2 (Android Jetpack Compose UI)**:

1. Add Compose UI & Google Maps dependencies to `:app` / `spikes/androidlocationspike` or create the
   main `:androidApp` target.
2. Build the **Registration Screen** and **Main Map Screen**.
