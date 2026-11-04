package com.example.memosproject.data.transaction

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

interface TransactionRepository {
    suspend fun listTransactions(): List<Transaction>
    suspend fun listCategories(): List<Category>
    suspend fun insert(draft: TransactionDraft): Transaction
    suspend fun update(id: String, draft: TransactionDraft): Transaction
    suspend fun delete(id: String)
}

class SupabaseTransactionRepository(
    private val supabase: SupabaseClient,
    private val userId: String
) : TransactionRepository {
    private val mutationLock = Mutex()

    private fun checkSession() {
        check(supabase.auth.currentUserOrNull()?.id == userId) { "Sesi berakhir. Silakan login kembali." }
    }

    override suspend fun listTransactions(): List<Transaction> = mutationLock.withLock {
        checkSession()
        val result = mutableListOf<Transaction>()
        do {
            // Join embedded select: categories(*) agar nama kategori langsung terisi
            val page = supabase.from("transactions").select(
                Columns.raw("id, user_id, category_id, type, amount, transaction_date, description, created_at, categories(*)")
            ) {
                filter { eq("user_id", userId) }
                order("transaction_date", Order.DESCENDING)
                order("created_at", Order.DESCENDING)
                range(result.size.toLong(), result.size.toLong() + 99)
            }.decodeList<Transaction>()
            result.addAll(page)
        } while (page.size == 100)
        result
    }

    override suspend fun listCategories(): List<Category> = mutationLock.withLock {
        checkSession()
        supabase.from("categories").select {
            filter { eq("user_id", userId) }
            order("name", Order.ASCENDING)
        }.decodeList<Category>()
    }

    override suspend fun insert(draft: TransactionDraft): Transaction = mutationLock.withLock {
        checkSession()
        draft.validate()
        val values = buildJsonObject {
            put("user_id", userId)
            put("category_id", draft.categoryId)
            put("type", draft.type.lowercase())
            put("amount", transactionMoneyJson(draft.amount))
            put("transaction_date", draft.transactionDate)
            put("description", draft.description?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }
        supabase.from("transactions").insert(values) {
            select(Columns.raw("id, user_id, category_id, type, amount, transaction_date, description, created_at, categories(*)"))
        }.decodeSingle<Transaction>()
    }

    override suspend fun update(id: String, draft: TransactionDraft): Transaction = mutationLock.withLock {
        checkSession()
        draft.validate()
        val values = buildJsonObject {
            put("category_id", draft.categoryId)
            put("type", draft.type.lowercase())
            put("amount", transactionMoneyJson(draft.amount))
            put("transaction_date", draft.transactionDate)
            put("description", draft.description?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }
        supabase.from("transactions").update(values) {
            filter { eq("id", id); eq("user_id", userId) }
            select(Columns.raw("id, user_id, category_id, type, amount, transaction_date, description, created_at, categories(*)"))
        }.decodeSingle<Transaction>()
    }

    override suspend fun delete(id: String) = mutationLock.withLock {
        checkSession()
        supabase.from("transactions").delete {
            filter { eq("id", id); eq("user_id", userId) }
            select()
        }.decodeSingle<Transaction>()
        Unit
    }
}
