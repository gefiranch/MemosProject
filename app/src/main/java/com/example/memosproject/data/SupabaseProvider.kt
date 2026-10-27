package com.example.memosproject.data

import com.example.memosproject.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient

object SupabaseProvider {
    val isConfigured: Boolean = BuildConfig.SUPABASE_URL.startsWith("https://") &&
        BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        check(isConfigured) { "Isi SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY di local.properties" }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Auth)
        }
    }
}
