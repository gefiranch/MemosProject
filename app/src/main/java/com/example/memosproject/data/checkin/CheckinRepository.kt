package com.example.memosproject.data.checkin

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

class CheckinRepository(private val client: SupabaseClient, private val userId: String) {
    private fun checkSession() = check(client.auth.currentUserOrNull()?.id == userId) { "Sesi berakhir." }
    suspend fun list(): List<Checkin> {
        checkSession()
        val result = mutableListOf<Checkin>()
        do {
            val page = client.from("daily_checkins").select {
                filter { eq("user_id", userId) }
                order("checkin_date", Order.DESCENDING)
                range(result.size.toLong(), result.size.toLong() + 99)
            }.decodeList<Checkin>()
            result.addAll(page)
        } while (page.size == 100)
        return result
    }
    suspend fun insert(item: Checkin): Checkin {
        checkSession()
        return client.from("daily_checkins").insert(item) { select() }.decodeSingle<Checkin>()
    }
    suspend fun update(item: Checkin): Checkin {
        checkSession()
        return client.from("daily_checkins").update(mapOf("status" to item.status, "reflection" to item.reflection, "plan" to item.plan)) {
            filter { eq("id", item.id); eq("user_id", userId) }
            select()
        }.decodeSingle<Checkin>()
    }
    suspend fun delete(item: Checkin) {
        checkSession()
        client.from("daily_checkins").delete { filter { eq("id", item.id); eq("user_id", userId) }; select() }.decodeSingle<Checkin>()
    }
}
