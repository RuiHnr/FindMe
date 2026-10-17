package com.ruirui.orbit.android.ui.auth

data class AuthUiState(
    val usernameInput: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
) {
    // TODO: Allow more symbols
    val isInputValid: Boolean
        get() = usernameInput.trim().length in 3..24 &&
                usernameInput.trim().all { it.isLetterOrDigit() || it == '_' }
}
