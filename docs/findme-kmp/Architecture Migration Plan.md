# FindMe Architecture Migration: KMP → Dedicated Android App Module

## Overview

This document outlines the planned migration of Android-specific components (foreground services,
push notification handlers, background workers) from the shared Kotlin Multiplatform module (
`findme-kmp`) into a dedicated Android application module. This architectural evolution will improve
separation of concerns, testability, and maintainability.

## Current Architecture (Hybrid Approach)

```
FindMe/
├── findme-kmp/                    # Current setup
│   ├── src/
│   │   ├── commonMain/            # Business logic, crypto, repositories
│   │   ├── androidMain/           # Android implementations + services
│   │   │   ├── location/          # LocationSharingService.kt, FindMeMessagingService.kt
│   │   │   └── app/FindMeApp.kt    # Android Application subclass
│   │   └── iosMain/               # iOS implementations
│   └── ...
└── ...
```

## Target Architecture (Dedicated Android App Module)

```
FindMe/
├── findme-kmp/                    # Pure business logic core
│   ├── src/
│   │   ├── commonMain/            # Domain models, crypto, repositories, APIs
│   │   │   # PURE business logic - NO Android dependencies
│   │   ├── androidMain/           # Android-specific implementations only
│   │   │   # Android drivers, helpers (SQLDelight, Keystore, etc.)
│   │   └── iosMain/               # iOS-specific implementations only
│   └── ...
├── findme-android-app/            # New dedicated Android module
│   ├── src/
│   │   ├── main/java/com/ruirui/findme/
│   │   │   ├── FindMeMessagingService.kt     # Android push receiver
│   │   │   ├── LocationSharingService.kt     # Android foreground service
│   │   │   ├── InboxSyncWorker.kt            # WorkManager worker
│   │   │   ├── FindMeApp.kt                  # Android Application subclass
│   │   │   └── AndroidManifest.xml            # Platform declarations
│   │   └── ...
│   └── ...
└── ...
```

## What Stays in KMP (`findme-kmp`)

### Core Business Logic (commonMain)

- **Domain Models:** Data classes for `InboxMessage`, `Friend`, `LocationPayload`, etc.
- **Crypto Core:** Double Ratchet implementation, X3DH, key exchange protocols
- **Repositories:** `LocationRepository`, `FriendRepository`, `AuthRepository`
- **Synchronization:** `SyncOrchestrator`, `PresenceManager`
- **Network APIs:** `LocationApi`, `FriendsApi`, `KeysApi`, `PresenceApi`
- **Use Cases:** Business operations, validation logic

### Platform-Specific Implementations (androidMain/iosMain)

- **Database Drivers:** SQLDelight drivers (`androidMain`, `iosMain`)
- **Platform Helpers:** Android Keystore, iOS Keychain integrations
- **Platform Adapters:** Platform-specific crypto implementations

## What Moves to Dedicated Android App Module

### Android Entry Points

- **Foreground Service:** `LocationSharingService.kt`
    - Manages GPS high/low accuracy switching
    - Coordinates with KMP repositories
    - Handles Android notification channels

- **Push Notification Handler:** `FindMeMessagingService.kt`
    - Receives FCM data messages
    - Wakes up app for HIGH mode
    - Triggers Android-specific wake-up logic

- **Background Worker:** `InboxSyncWorker.kt`
    - Periodic fallback sync (~15 minutes)
    - Android WorkManager integration
    - Retries failed sync attempts

### Android Application Lifecycle

- **Application Subclass:** `FindMeApp.kt`
    - Provides Android context to KMP services
    - Manages KMP dependency injection
    - Handles WorkManager setup

### Platform Declarations

- **AndroidManifest.xml**
    - Permissions: `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`
    - Component declarations: Services, Receivers
    - Application class declaration

## Benefits of the New Architecture

### 1. Separation of Concerns

- **Pure Core:** `findme-kmp` contains no Android SDK dependencies
- **Clean Boundaries:** Each module has a single responsibility
- **Platform Isolation:** Android concerns are contained in one place

### 2. Improved Testability

- **Unit Testing:** Can test KMP business logic without Android
- **Integration Testing:** Can mock Android dependencies
- **Faster Tests:** No need to spin up Android emulator for unit tests

### 3. Better Developer Experience

- **Cleaner Build:** Faster Android app compilation
- **IDE Support:** Better plugin support for multi-module projects
- **Scalability:** Easier to onboard new Android developers

### 4. Enhanced Reusability

- **Cross-Platform:** KMP core can be used in other platforms (React Native, Flutter)
- **Shared Libraries:** Core business logic can be published as library
- **Prototype:** Simplifies future platform experimentation

## Migration Path

### Phase 1: Extraction (Next 2-3 sprints)

1. **Extract commonMain business logic** into `core-domain` module
2. **Create Android app module** with all services
3. **Update gradle dependencies** between modules
4. **Add dependency injection** (Koin/Hilt) for service communication
5. **Migrate services** one by one, maintaining compatibility

### Phase 2: Refactoring (Following sprints)

1. **Remove Android dependencies** from KMP's `androidMain`
2. **Simplify KMP structure** to only essential platform glue
3. **Add comprehensive tests** for both modules
4. **Update build configurations** for optimal performance
5. **Add monitoring and debugging** for cross-module communication

### Phase 3: Optimization (Long-term)

1. **Split KMP into separate modules** (crypto, network, data, domain)
2. **Implement plugin architecture** for Android customization
3. **Add CI/CD pipelines** for both modules
4. **Document migration decisions** for future reference

## Technical Considerations

### Dependency Management

```kotlin
// KMP depends on Android app module (for services)
commonMain.implementation(project(":findme-android-app"))

// Android app module depends on KMP core
androidMain.implementation(project(":findme-kmp"))
```

### Communication Between Modules

- **Dependency Injection:** Koin/Hilt for seamless service injection
- **Coroutines:** `viewModelScope` for background communication
- **Message Buses:** Event channels for cross-module events
- **Direct Calls:** For critical paths requiring minimal latency

### Compatibility Strategy

- **Maintain current API** during migration
- **Add bridge layer** for gradual transition
- **Version control** for parallel module support
- **Rollback plan** if migration issues arise

## Files to Update

### In KMP Module (`findme-kmp`)

1. **`build.gradle.kts`** - Remove Android service dependencies
2. **`src/commonMain/`** - Move business logic to core module
3. **`src/androidMain/`** - Keep only platform glue
4. **`src/commonTest/`** - Add integration tests

### In Android App Module (`findme-android-app`)

1. **`build.gradle.kts`** - Add KMP core dependency
2. **`src/main/java/com/ruirui/findme/`** - All Android services
3. **`AndroidManifest.xml`** - All platform declarations
4. **`res/`** - Android-specific resources
5. **`proguard-rules.pro`** - Android-specific optimizations

## Timeline

| Phase   | Duration    | Key Milestones                       |
|---------|-------------|--------------------------------------|
| Phase 1 | 2-3 sprints | Core extraction, new module creation |
| Phase 2 | 2-3 sprints | Platform cleanup, refactoring        |
| Phase 3 | Ongoing     | Optimization, documentation          |

## Risk Mitigation

### Technical Risks

- **Breaking Changes:** Maintain backward compatibility with bridge layer
- **Testing Complexity:** Add comprehensive test coverage before migration
- **Build Performance:** Optimize gradle configuration for multiple modules

### Project Risks

- **Timeline:** Break down into manageable chunks
- **Team Knowledge:** Provide training on new architecture
- **Documentation:** Keep documentation up-to-date throughout migration

## Decision Criteria

### When to Proceed with Migration

- Codebase is stable and well-tested
- Team understands the new architecture patterns
- Performance requirements are met in current setup
- There are clear benefits for maintainability and testability

### When to Delay Migration

- Critical bugs in current implementation
- Team is at capacity with existing work
- External dependencies require current structure
- Risk of disrupting production stability

## Conclusion

This migration will significantly improve FindMe's architecture by creating clear separation between
business logic (pure KMP core) and platform-specific implementation (dedicated Android app). While
requiring upfront investment, the long-term benefits in maintainability, testability, and developer
experience make this a worthwhile architectural evolution.

The migration should be approached incrementally, with thorough testing at each phase to ensure
stability and minimize risk to the production codebase.

---

**Last Updated:** [Current Date]
**Status:** Planned Migration
**Priority:** Medium

## Related Documentation

- [Background Sync Guide](../Background%20Sync%20Guide.md)
- [Repository Layer Guide](./Repository Layer Guide.md)
- [Local DB Migration Guide](./Local DB Migration Guide.md)
- [Architecture Documentation](./architecture/cryptography.md)