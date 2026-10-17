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
                    _uiState.update { it.copy(isLoading = false, errorMessage = null) }
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