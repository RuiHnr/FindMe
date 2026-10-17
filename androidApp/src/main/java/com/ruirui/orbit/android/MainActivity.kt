package com.ruirui.orbit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
                        // Placeholder for MainMapScreen
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("You are logged in as user ${state.userId}!")
                        }
                    }
                }
            }
        }
    }
}