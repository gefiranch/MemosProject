package com.example.memosproject.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Email/password authentication without any OTP or email-verification screen. */
class AuthRepository(private val supabase: SupabaseClient) {
    val sessionStatus: Flow<SessionStatus> get() = supabase.auth.sessionStatus

    suspend fun register(email: String, username: String, password: String) {
        // Supabase automatically establishes a session ONLY when Confirm email is disabled.
        supabase.auth.signUpWith(Email) {
            this.email = email.trim().lowercase()
            this.password = password
            data = buildJsonObject { put("username", username.trim()) }
        }
    }

    suspend fun login(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim().lowercase()
            this.password = password
        }
    }

    fun hasSession(): Boolean = supabase.auth.currentSessionOrNull() != null

    fun currentEmail(): String? = supabase.auth.currentUserOrNull()?.email

    suspend fun logout() {
        supabase.auth.signOut()
    }
}
