# Orbit Android: Auth Screen Implementation Guide

This guide provides a comprehensive, step-by-step walkthrough for implementing the **Authentication & Registration Screen** in the `androidApp` module using **Jetpack Compose**, **Material 3**, and the **`:kmp`** core.

---

## 1. Concepts & Architecture

### 1.1 What Happens During "Auth" in Orbit?
In conventional apps, authentication is just sending an email and password to a database. 

In **Orbit**, registration is the foundational **cryptographic ceremony** that establishes your End-to-End Encryption (E2EE) identity:
1. The user inputs their display username.
2. The client generates:
   - **Identity Keypair (Ed25519)**: Long-term signing key for cryptographic authenticity.
   - **Identity Keypair (X25519)**: Long-term Diffie-Hellman key for E2EE key agreement.
   - **Signed Prekey**: Rotated key signed by the Ed25519 identity key.
   - **One-Time Prekeys (OTPKs)**: A pool of single-use keys for asynchronous X3DH protocol handshakes.
3. The client submits the registration request to the Rust backend (`POST /users/register`) containing the username and public identity keys.
4. The backend stores the public keys, generates a user UUID, and returns a session JWT.
5. The client saves the JWT, UUID, and private keys into hardware-backed storage (`SecureStorage` via Android Keystore) and uploads the signed prekey bundle (`POST /keys`).

All of this heavy cryptographic lifting is already implemented and encapsulated inside **`AuthRepository`** in `:kmp`. The Android UI layer only needs to collect the username, trigger `authRepository.register(username)`, observe the resulting states, and display feedback.

---

### 1.2 Declarative UI & Unidirectional Data Flow (UDF)

In Jetpack Compose, the UI is a pure function of state:

$$\text{UI} = f(\text{State})$$

Instead of imperatively mutating views (`progressBar.visibility = View.GONE`), we follow **Unidirectional Data Flow**:

```
        ┌────────────────────────────────────────┐
        │               AuthScreen               │
        │   (Renders UI based on AuthUiState)    │
        └───────────────────┬────────────────────┘
                            │
               User clicks "Register"
                   (UiEvent / Action)
                            ▼
        ┌────────────────────────────────────────┐
        │             AuthViewModel              │
        │   (Executes business logic via KMP)    │
        └───────────────────┬────────────────────┘
                            │
                    Emits new state
                 via StateFlow<UiState>
                            ▼
        ┌────────────────────────────────────────┐
        │               AuthScreen               │
        │      (Recomposes with new State)       │
        └────────────────────────────────────────┘
```

1. **State flows DOWN**: The `AuthViewModel` exposes a single `StateFlow<AuthUiState>` containing everything the screen needs to render (text value, loading status, error messages).
2. **Events flow UP**: The `AuthScreen` is stateless where possible; user actions (typing a character, clicking the button) are passed up to the ViewModel as function calls or events.

---

### 1.3 Why Recomposition Matters
* When a composable reads a state value, Compose automatically subscribes that composable to that state.
* When the state changes, Compose re-executes only the composable functions that read that specific value (**Recomposition**).
* To prevent expensive work (like creating lambdas or objects) from re-running on every recomposition, we use **`remember { ... }`**.
* The **`ViewModel`** survives screen rotations and configuration changes, keeping state intact even when the activity is recreated.

---

## 2. Directory Structure

We will organize the UI layer under `androidApp/src/main/java/com/ruirui/orbit/android/`:

```
androidApp/src/main/java/com/ruirui/orbit/android/
├── MainActivity.kt                      # Single Activity hosting the Compose hierarchy
├── app/
│   ├── AppContainer.kt                  # Manual Dependency Injection container
│   └── OrbitApp.kt                      # Application class initializing the container
├── theme/
│   ├── Color.kt                         # Material 3 Color definitions
│   ├── Theme.kt                         # OrbitTheme wrapper (Light / Dark)
│   └── Type.kt                          # Typography
└── ui/
    └── auth/
        ├── AuthUiState.kt               # State & Event data structures
        ├── AuthViewModel.kt             # Bridges Compose to KMP AuthRepository
        └── AuthScreen.kt                # Material 3 Composable Screen
```

---

## 3. Step-by-Step Implementation

### Step 1: Material 3 Theme (`Color.kt`, `Type.kt`, `Theme.kt`)

Material 3 uses dynamic, cohesive color schemes and standard elevation/typography tokens.

#### `theme/Color.kt`
```kotlin
package com.ruirui.orbit.android.theme

import androidx.compose.ui.graphics.Color

// Orbit Brand Colors (Deep Navy, Slate & Electric Cyan)
val OrbitPrimary = Color(0xFF0066FF)
val OrbitOnPrimary = Color(0xFFFFFFFF)
val OrbitPrimaryContainer = Color(0xFFDCE2FF)
val OrbitOnPrimaryContainer = Color(0xFF001945)

val OrbitSecondary = Color(0xFF565E71)
val OrbitOnSecondary = Color(0xFFFFFFFF)
val OrbitBackgroundDark = Color(0xFF111418)
val OrbitSurfaceDark = Color(0xFF191C20)
val OrbitOnSurfaceDark = Color(0xFFE2E2E6)

val OrbitBackgroundLight = Color(0xFFFDFCFF)
val OrbitSurfaceLight = Color(0xFFFDFCFF)
val OrbitOnSurfaceLight = Color(0xFF1A1C1E)

val OrbitError = Color(0xFFBA1A1A)
```

#### `theme/Theme.kt`
```kotlin
package com.ruirui.orbit.android.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = OrbitPrimary,
    onPrimary = OrbitOnPrimary,
    background = OrbitBackgroundDark,
    surface = OrbitSurfaceDark,
    onSurface = OrbitOnSurfaceDark,
    error = OrbitError
)

private val LightColorScheme = lightColorScheme(
    primary = OrbitPrimary,
    onPrimary = OrbitOnPrimary,
    background = OrbitBackgroundLight,
    surface = OrbitSurfaceLight,
    onSurface = OrbitOnSurfaceLight,
    error = OrbitError
)

@Composable
fun OrbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
```

---

### Step 2: Dependency Injection via `AppContainer`

To allow our ViewModels to easily access the `:kmp` repositories without hardcoding factories everywhere, we set up an `AppContainer` in `OrbitApp`.

#### `app/AppContainer.kt`
```kotlin
package com.ruirui.orbit.android.app

import android.content.Context
import com.ruirui.orbit.crypto.Crypto
import com.ruirui.orbit.crypto.PreKeyManagerImpl
import com.ruirui.orbit.db.DriverFactory
import com.ruirui.orbit.db.createDatabase
import com.ruirui.orbit.network.HttpClientFactory
import com.ruirui.orbit.network.api.AuthApi
import com.ruirui.orbit.network.api.DeviceApi
import com.ruirui.orbit.network.api.FriendsApi
import com.ruirui.orbit.network.api.KeysApi
import com.ruirui.orbit.network.api.LocationApi
import com.ruirui.orbit.repository.AuthRepository
import com.ruirui.orbit.repository.AuthRepositoryImpl
import com.ruirui.orbit.repository.FriendRepository
import com.ruirui.orbit.repository.FriendRepositoryImpl
import com.ruirui.orbit.repository.LocationRepository
import com.ruirui.orbit.repository.LocationRepositoryImpl
import com.ruirui.orbit.storage.SecureStorage
import com.ruirui.orbit.sync.SessionStoreImpl
import com.ruirui.orbit.sync.SyncOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Storage & Cryptography
    val secureStorage by lazy { SecureStorage(context) }
    val crypto by lazy { Crypto() }
    val database by lazy { createDatabase(DriverFactory(context)) }

    // Networking
    // TODO: Point to your backend URL (e.g., http://10.0.2.2:8080 for Android Emulator)
    val baseUrl = "http://10.0.2.2:8080"
    val httpClient by lazy { HttpClientFactory.create(secureStorage = secureStorage, baseUrl = baseUrl) }

    // APIs
    val authApi by lazy { AuthApi(httpClient) }
    val keysApi by lazy { KeysApi(httpClient) }
    val friendsApi by lazy { FriendsApi(httpClient) }
    val locationApi by lazy { LocationApi(httpClient) }
    val deviceApi by lazy { DeviceApi(httpClient) }

    // Managers & Repositories
    val preKeyManager by lazy { PreKeyManagerImpl(crypto, secureStorage, keysApi, database) }

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(authApi, preKeyManager, secureStorage, crypto, appScope)
    }

    val friendRepository: FriendRepository by lazy {
        FriendRepositoryImpl(friendsApi, keysApi, database, appScope)
    }

    val locationRepository: LocationRepository by lazy {
        LocationRepositoryImpl(
            locationApi, keysApi, crypto, secureStorage,
            SessionStoreImpl(secureStorage, crypto),
            preKeyManager, database, appScope
        )
    }

    val syncOrchestrator by lazy {
        SyncOrchestrator(locationRepository, friendRepository, appScope)
    }
}
```

#### Update `app/OrbitApp.kt`
```kotlin
package com.ruirui.orbit.android.app

import android.app.Application

class OrbitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: OrbitApp
            private set
    }
}
```

---

### Step 3: Define the UI State (`AuthUiState.kt`)

A single immutable data class captures everything the Auth Screen displays.

```kotlin
package com.ruirui.orbit.android.ui.auth

data class AuthUiState(
    val usernameInput: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
) {
    // Validation: Username must be 3-24 characters, alphanumeric + underscore
    val isInputValid: Boolean
        get() = usernameInput.trim().length in 3..24 && 
                usernameInput.trim().all { it.isLetterOrDigit() || it == '_' }
}
```

---

### Step 4: The ViewModel (`AuthViewModel.kt`)

The ViewModel handles user interactions, manages the state stream, and executes coroutines via `viewModelScope`.

```kotlin
package com.ruirui.orbit.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ruirui.orbit.repository.AuthRepository
import com.ruirui.orbit.repository.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Pass-through to global auth state from KMP
    val authState: StateFlow<AuthState> = authRepository.authState

    fun onUsernameChanged(newUsername: String) {
        _uiState.update { 
            it.copy(
                usernameInput = newUsername,
                errorMessage = null // Clear error when typing
            ) 
        }
    }

    fun onRegister() {
        val username = _uiState.value.usernameInput.trim()
        if (!_uiState.value.isInputValid) {
            _uiState.update { it.copy(errorMessage = "Username must be 3-24 alphanumeric characters.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val result = authRepository.register(username)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, isSuccess = true) }
                },
                onFailure = { error ->
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            errorMessage = error.message ?: "Registration failed. Please try again."
                        ) 
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun provideFactory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(authRepository) as T
                }
            }
    }
}
```

---

### Step 5: The Composable (`AuthScreen.kt`)

Now we build the UI using Material 3 components:
* `OutlinedTextField` with singleLine, leading icon, and character validation.
* `Button` with progress indicator during cryptographic key generation.
* `SnackbarHost` for friendly error alerts.

```kotlin
package com.ruirui.orbit.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onRegistrationSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Trigger navigation on success
    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onRegistrationSuccess()
        }
    }

    // Display error snackbar if error occurs
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Header / Branding
                Text(
                    text = "Orbit",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Private, end-to-end encrypted location sharing for your inner circle.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Username Input Field
                OutlinedTextField(
                    value = uiState.usernameInput,
                    onValueChange = viewModel::onUsernameChanged,
                    label = { Text("Choose a username") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Username"
                        )
                    },
                    singleLine = true,
                    enabled = !uiState.isLoading,
                    isError = uiState.errorMessage != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            if (uiState.isInputValid) viewModel.onRegister()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "3-24 characters, letters, numbers, and underscores only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Button
                Button(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.onRegister()
                    },
                    enabled = uiState.isInputValid && !uiState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Generating Secure Identity...")
                    } else {
                        Text("Join Orbit")
                    }
                }
            }
        }
    }
}
```

---

### Step 6: Root Navigation in `MainActivity.kt`

`MainActivity` acts as the single activity entry point. It inspects `AuthRepository.authState`:
* If **`Loading`**: Shows a loading splash.
* If **`Unauthenticated`**: Shows `AuthScreen`.
* If **`Authenticated`**: Routes to the main application (Interactive Map).

```kotlin
package com.ruirui.orbit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruirui.orbit.android.app.OrbitApp
import com.ruirui.orbit.android.theme.OrbitTheme
import com.ruirui.orbit.android.ui.auth.AuthScreen
import com.ruirui.orbit.android.ui.auth.AuthViewModel
import com.ruirui.orbit.repository.AuthState

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.provideFactory(OrbitApp.instance.container.authRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OrbitTheme {
                val authState by authViewModel.authState.collectAsStateWithLifecycle()

                when (val state = authState) {
                    is AuthState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is AuthState.Unauthenticated -> {
                        AuthScreen(
                            viewModel = authViewModel,
                            onRegistrationSuccess = {
                                // Handled automatically via authState StateFlow update
                            }
                        )
                    }
                    is AuthState.Authenticated -> {
                        // Placeholder for MainMapScreen (Phase 2.2)
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Welcome to Orbit, user ${state.userId}!")
                        }
                    }
                }
            }
        }
    }
}
```

---

## 4. Verification & Testing

### 4.1 Manual Verification via Emulator
1. Start the backend PostgreSQL database:
   ```powershell
   docker run -d --name orbit-db -p 5433:5432 -e POSTGRES_USER=orbit -e POSTGRES_PASSWORD=supersecret -e POSTGRES_DB=orbit_db -v orbit_pgdata:/var/lib/postgresql/data postgres:16-alpine
   ```
2. Run the Rust backend server:
   ```powershell
   cd server
   cargo run
   ```
3. Run the Android app in Android Studio.
4. Enter a username (e.g., `alice`) and click **Join Orbit**.
5. Observe the progress indicator ("Generating Secure Identity...").
6. Verify that the screen transitions to `"Welcome to Orbit, user <uuid>!"`.
7. Terminate the app and reopen it — confirm that it bypasses the AuthScreen and immediately displays the Authenticated screen (session persistence check).

---

## 5. Next Steps
Once the Auth Screen is verified, proceed to **Phase 2.2: Main Interactive Map Screen** (`docs/ui/UI Roadmap.md`).
