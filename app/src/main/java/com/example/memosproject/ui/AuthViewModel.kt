package com.example.memosproject.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.memosproject.data.AuthRepository
import com.example.memosproject.data.SupabaseProvider
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class AuthScreen { LOADING, LOGIN, REGISTER, HOME, SETUP }

data class AuthUiState(
    val screen: AuthScreen = AuthScreen.LOADING,
    val busy: Boolean = false,
    val message: String? = null,
    val signedInEmail: String = ""
)

class AuthViewModel(private val repository: AuthRepository?) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        if (repository == null) {
            _uiState.update { it.copy(screen = AuthScreen.SETUP) }
        } else {
            viewModelScope.launch {
                repository.sessionStatus.collect { status ->
                    when (status) {
                        SessionStatus.Initializing -> Unit
                        is SessionStatus.Authenticated -> {
                            _uiState.update {
                                it.copy(
                                    screen = AuthScreen.HOME,
                                    signedInEmail = repository.currentEmail().orEmpty(),
                                    busy = false,
                                    message = null
                                )
                            }
                        }
                        is SessionStatus.NotAuthenticated -> {
                            _uiState.update {
                                if (it.screen == AuthScreen.LOADING || it.screen == AuthScreen.HOME)
                                    it.copy(screen = AuthScreen.LOGIN, busy = false, signedInEmail = "")
                                else it
                            }
                        }
                        is SessionStatus.RefreshFailure -> {
                            _uiState.update {
                                it.copy(screen = AuthScreen.LOGIN, busy = false,
                                    signedInEmail = "", message = "Sesi berakhir. Silakan login kembali.")
                            }
                        }
                    }
                }
            }
        }
    }

    fun openLogin() = _uiState.update { it.copy(screen = AuthScreen.LOGIN, message = null) }
    fun openRegister() = _uiState.update { it.copy(screen = AuthScreen.REGISTER, message = null) }

    fun register(email: String, username: String, password: String) {
        val e = email.trim().lowercase(Locale.ROOT)
        val name = username.trim()
        val validation = when {
            !GMAIL.matches(e) -> "Gunakan alamat yang berakhiran @gmail.com."
            !USERNAME.matches(name) -> "Username harus 3–20 karakter (huruf, angka, atau _)."
            password.length < 8 -> "Password minimal 8 karakter."
            else -> null
        }
        if (validation != null) {
            _uiState.update { it.copy(message = validation) }
            return
        }
        runAction(
            onSuccess = {
                // Never show a private screen if Supabase didn't issue a real session.
                val repo = requireNotNull(repository)
                if (repo.hasSession()) {
                    _uiState.update {
                        it.copy(screen = AuthScreen.HOME,
                            signedInEmail = repo.currentEmail() ?: e, message = null)
                    }
                } else {
                    _uiState.update {
                        it.copy(message = "Akun dibuat, tetapi sesi belum aktif. " +
                            "Nonaktifkan 'Confirm email' di Supabase (Authentication → Sign In / Providers → Email).")
                    }
                }
            },
            onFailure = { error ->
                val detail = error.message.orEmpty()
                val reason = when {
                    detail.contains("Username tidak tersedia", ignoreCase = true) ->
                        "Username sudah dipakai. Coba username lain."
                    detail.contains("Username tidak valid", ignoreCase = true) ->
                        "Username harus 3–20 karakter (huruf, angka, atau _)."
                    detail.contains("User already registered", ignoreCase = true) ||
                        detail.contains("already been registered", ignoreCase = true) ->
                        "Gmail sudah terdaftar. Silakan Login."
                    detail.contains("Hanya email @gmail.com", ignoreCase = true) ->
                        "Pendaftaran hanya menerima alamat @gmail.com."
                    detail.contains("rate limit", ignoreCase = true) ->
                        "Terlalu banyak percobaan. Coba beberapa saat lagi."
                    else -> "Pendaftaran gagal. Periksa email, username, atau koneksi internet."
                }
                _uiState.update { it.copy(message = reason) }
            }
        ) { requireNotNull(repository).register(e, name, password) }
    }

    fun login(email: String, password: String) {
        val e = email.trim().lowercase(Locale.ROOT)
        if (!GMAIL.matches(e) || password.isBlank()) {
            _uiState.update { it.copy(message = "Masukkan Gmail dan password yang valid.") }
            return
        }
        runAction(
            onSuccess = {
                val repo = requireNotNull(repository)
                if (repo.hasSession()) {
                    _uiState.update {
                        it.copy(screen = AuthScreen.HOME, signedInEmail = repo.currentEmail() ?: e)
                    }
                }
            },
            onFailure = { error ->
                val detail = error.message.orEmpty()
                val reason = if (detail.contains("email not confirmed", ignoreCase = true))
                    "Akun lama belum terkonfirmasi. Pastikan 'Confirm email' dimatikan " +
                        "dan gunakan akun uji baru atau konfirmasi akun lama lewat dashboard Supabase."
                else "Login gagal. Periksa Gmail, password, atau koneksi internet."
                _uiState.update { it.copy(message = reason) }
            }
        ) { requireNotNull(repository).login(e, password) }
    }

    fun logout() {
        runAction(onSuccess = {
            _uiState.update {
                it.copy(screen = AuthScreen.LOGIN, signedInEmail = "", message = null)
            }
        }) { requireNotNull(repository).logout() }
    }

    private fun runAction(
        onSuccess: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {
            _uiState.update { s -> s.copy(message = "Permintaan gagal. Periksa koneksi internet dan coba lagi.") }
        },
        action: suspend () -> Unit
    ) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                action()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        private val GMAIL = Regex("^[A-Za-z0-9._%+\\-]+@gmail\\.com$")
        private val USERNAME = Regex("^[A-Za-z0-9_]{3,20}$")

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repo = if (SupabaseProvider.isConfigured) AuthRepository(SupabaseProvider.client) else null
                return AuthViewModel(repo) as T
            }
        }
    }
}
