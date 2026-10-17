package com.ruirui.orbit.android.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruirui.orbit.android.theme.OrbitTheme

// 1. Interactive Preview (Light Mode)
@Preview(name = "Light Mode", showBackground = true, device = "id:pixel_9_pro")
@Composable
fun AuthScreenPreviewLight() {
    OrbitTheme(darkTheme = false) {
        AuthContent(
            uiState = AuthUiState(usernameInput = "alice"),
            onUsernameChanged = {},
            onRegister = {}
        )
    }
}

// 2. Dark Mode Preview
@Preview(name = "Dark Mode", showBackground = true)
@Composable
fun AuthScreenPreviewDark() {
    OrbitTheme(darkTheme = true) {
        AuthContent(
            uiState = AuthUiState(usernameInput = "alice"),
            onUsernameChanged = {},
            onRegister = {}
        )
    }
}

// 3. Loading State Preview (See the button spinner!)
@Preview(name = "Loading State", showBackground = true)
@Composable
fun AuthScreenPreviewLoading() {
    OrbitTheme {
        AuthContent(
            uiState = AuthUiState(usernameInput = "alice", isLoading = true),
            onUsernameChanged = {},
            onRegister = {}
        )
    }
}

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onRegistrationSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) onRegistrationSuccess()
    }

    // Passes state down to the stateless AuthContent
    AuthContent(
        uiState = uiState,
        onUsernameChanged = viewModel::onUsernameChanged,
        onRegister = viewModel::onRegister,
        modifier = modifier
    )
}

@Composable
fun AuthContent(
    uiState: AuthUiState,
    onUsernameChanged: (String) -> Unit,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = uiState.usernameInput,
                onValueChange = onUsernameChanged,
                label = { Text("Choose a username") }
            )

            Button(
                onClick = onRegister,
                enabled = uiState.isInputValid && !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text("Join Orbit")
                }
            }
        }
    }
}