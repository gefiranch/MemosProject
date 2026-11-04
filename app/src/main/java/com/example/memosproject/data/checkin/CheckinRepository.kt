package com.example.memosproject.data.checkin

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class StoredCheckin(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("checkin_date") val date: String,
    val content: String,
    val status: String? = null,
    val reflection: String? = null,
    val plan: String? = null
) {
    fun toCheckin() = Checkin(
        id, userId, date, status ?: "Belum ditentukan", reflection ?: content, plan ?: ""
    )
}

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
            }.decodeList<StoredCheckin>().map { it.toCheckin() }
            result.addAll(page)
        } while (page.size == 100)
        return result
    }
    suspend fun insert(item: Checkin): Checkin {
        checkSession()
        val values = mapOf(
            "id" to item.id, "user_id" to item.userId, "checkin_date" to item.date,
            "content" to item.reflection, "status" to item.status,
            "reflection" to item.reflection, "plan" to item.plan
        )
        return client.from("daily_checkins").insert(values) { select() }.decodeSingle<StoredCheckin>().toCheckin()
    }
    suspend fun update(item: Checkin): Checkin {
        checkSession()
        val values = mapOf(
            "status" to item.status, "content" to item.reflection,
            "reflection" to item.reflection, "plan" to item.plan
        )
        return client.from("daily_checkins").update(values) {
            filter { eq("id", item.id); eq("user_id", userId) }
            select()
        }.decodeSingle<StoredCheckin>().toCheckin()
    }
    suspend fun delete(item: Checkin) {
        checkSession()
        client.from("daily_checkins").delete { filter { eq("id", item.id); eq("user_id", userId) }; select() }.decodeSingle<StoredCheckin>()
    }
}
