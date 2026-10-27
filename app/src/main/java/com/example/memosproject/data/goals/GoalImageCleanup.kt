package com.example.memosproject.data.goals

import android.content.Context
import androidx.core.content.edit

/** Durable cleanup candidates, scoped to the signed-in user. Never stores image bytes or tokens. */
class GoalImageCleanup(context: Context) {
    private val preferences = context.getSharedPreferences("goal_image_cleanup", Context.MODE_PRIVATE)

    @Synchronized
    fun pending(userId: String): Set<String> = preferences.getStringSet(userId, emptySet()).orEmpty().toSet()

    @Synchronized
    fun add(userId: String, path: String) {
        // Use synchronous commit: the queue must survive process death before the network call.
        @Suppress("UseKtx")
        val committed = preferences.edit().putStringSet(userId, pending(userId) + path).commit()
        check(committed) { "Tidak dapat menyiapkan penyimpanan gambar. Coba lagi." }
    }

    @Synchronized
    fun remove(userId: String, path: String) {
        preferences.edit(commit = true) { putStringSet(userId, pending(userId) - path) }
    }
}
