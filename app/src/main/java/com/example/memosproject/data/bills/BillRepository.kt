package com.example.memosproject.data.bills

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface BillRepository {
    suspend fun listBills(): List<Bill>
    suspend fun insert(draft: BillDraft): Bill
    suspend fun update(id: String, draft: BillDraft): Bill
    suspend fun delete(id: String)
}

class SupabaseBillRepository(
    private val supabase: SupabaseClient,
    private val userId: String
) : BillRepository {

    private val mutationLock = Mutex()

    private fun checkSession() {
        check(supabase.auth.currentUserOrNull()?.id == userId) {
            "Sesi berakhir. Silakan login kembali."
        }
    }

    override suspend fun listBills(): List<Bill> = mutationLock.withLock {
        checkSession()

        val result = mutableListOf<Bill>()

        do {
            val page = supabase.from("bills").select(
                Columns.raw(
                    "id, user_id, title, amount, due_date, is_paid, notes, created_at, updated_at"
                )
            ) {
                filter {
                    eq("user_id", userId)
                }

                order("due_date", Order.ASCENDING)
                order("created_at", Order.DESCENDING)

                range(
                    result.size.toLong(),
                    result.size.toLong() + 99
                )
            }.decodeList<Bill>()

            result.addAll(page)

        } while (page.size == 100)

        result
    }

    override suspend fun insert(draft: BillDraft): Bill = mutationLock.withLock {
        checkSession()
        draft.validate()

        val values = buildJsonObject {
            put("user_id", userId)
            put("title", draft.title.trim())
            put("amount", billMoneyJson(draft.amount))
            put("due_date", draft.dueDate)
            put("is_paid", draft.isPaid)

            put(
                "notes",
                draft.notes
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::JsonPrimitive) ?: JsonNull
            )
        }

        supabase.from("bills").insert(values) {
            select(
                Columns.raw(
                    "id, user_id, title, amount, due_date, is_paid, notes, created_at, updated_at"
                )
            )
        }.decodeSingle<Bill>()
    }

    override suspend fun update(
        id: String,
        draft: BillDraft
    ): Bill = mutationLock.withLock {
        checkSession()
        draft.validate()

        val values = buildJsonObject {
            put("title", draft.title.trim())
            put("amount", billMoneyJson(draft.amount))
            put("due_date", draft.dueDate)
            put("is_paid", draft.isPaid)

            put(
                "notes",
                draft.notes
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::JsonPrimitive) ?: JsonNull
            )

            put(
                "updated_at",
                OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
        }

        supabase.from("bills").update(values) {
            filter {
                eq("id", id)
                eq("user_id", userId)
            }

            select(
                Columns.raw(
                    "id, user_id, title, amount, due_date, is_paid, notes, created_at, updated_at"
                )
            )
        }.decodeSingle<Bill>()
    }

    override suspend fun delete(id: String) = mutationLock.withLock {
        checkSession()

        supabase.from("bills").delete {
            filter {
                eq("id", id)
                eq("user_id", userId)
            }
            select()
        }.decodeSingle<Bill>()

        Unit
    }
}
