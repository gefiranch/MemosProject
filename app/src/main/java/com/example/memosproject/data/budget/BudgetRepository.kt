package com.example.memosproject.data.budget

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

interface BudgetRepository {
    suspend fun listCategories(): List<BudgetCategory>
    suspend fun list(month: YearMonth): List<Budget>
    suspend fun insert(categoryId: String, month: YearMonth, limit: BigDecimal): Budget
    suspend fun update(id: String, categoryId: String, month: YearMonth, limit: BigDecimal): Budget
    suspend fun delete(id: String)
}

class SupabaseBudgetRepository(
    private val client: SupabaseClient,
    private val userId: String
) : BudgetRepository {
    private fun checkSession() {
        check(client.auth.currentUserOrNull()?.id == userId) { "Sesi berakhir. Silakan login kembali." }
    }

    override suspend fun listCategories(): List<BudgetCategory> {
        checkSession()
        return client.from("categories").select {
            filter { eq("user_id", userId); eq("type", "expense") }
            order("name", Order.ASCENDING)
        }.decodeList<BudgetCategory>()
    }

    override suspend fun list(month: YearMonth): List<Budget> {
        checkSession()
        val categories = listCategories().associateBy { it.id }
        val monthStart = month.atDay(1)
        val nextMonth = month.plusMonths(1).atDay(1)
        val rows = mutableListOf<BudgetRecord>()
        do {
            val page = client.from("budgets").select {
                filter { eq("user_id", userId); eq("month", monthStart.toString()) }
                order("created_at", Order.ASCENDING)
                range(rows.size.toLong(), rows.size.toLong() + 99)
            }.decodeList<BudgetRecord>()
            rows.addAll(page)
        } while (page.size == 100)

        val expenses = mutableListOf<ExpenseRecord>()
        do {
            val page = client.from("transactions").select {
                filter {
                    eq("user_id", userId)
                    eq("type", "expense")
                    gte("transaction_date", monthStart.toString())
                    lt("transaction_date", nextMonth.toString())
                }
                order("transaction_date", Order.DESCENDING)
                range(expenses.size.toLong(), expenses.size.toLong() + 99)
            }.decodeList<ExpenseRecord>()
            expenses.addAll(page)
        } while (page.size == 100)

        val expensesByCategory = expenses.groupBy { it.categoryId }
        return rows.mapNotNull { row ->
            val category = categories[row.categoryId] ?: return@mapNotNull null
            val categoryExpenses = expensesByCategory[row.categoryId].orEmpty()
            Budget(
                id = row.id,
                categoryId = row.categoryId,
                category = category.name,
                limit = row.limitAmount,
                spent = categoryExpenses.fold(BigDecimal.ZERO) { total, expense -> total + expense.amount },
                period = YearMonth.from(LocalDate.parse(row.month)),
                transactions = categoryExpenses.map { expense ->
                    BudgetTransaction(
                        id = expense.id,
                        title = expense.description?.takeIf(String::isNotBlank) ?: category.name,
                        date = expense.transactionDate,
                        amount = expense.amount
                    )
                }
            )
        }
    }

    override suspend fun insert(categoryId: String, month: YearMonth, limit: BigDecimal): Budget =
        save(categoryId, month, limit, id = null)

    override suspend fun update(id: String, categoryId: String, month: YearMonth, limit: BigDecimal): Budget =
        save(categoryId, month, limit, id)

    private suspend fun save(categoryId: String, month: YearMonth, limit: BigDecimal, id: String?): Budget {
        val operation = if (id == null) "INSERT" else "UPDATE"
        logAuthContext(operation)
        checkSession()
        require(limit > BigDecimal.ZERO) { "Batas anggaran harus lebih dari 0." }

        // Confirm the selected id is an expense category owned by this signed-in user.
        val ownedCategory = try {
            client.from("categories").select {
                filter { eq("id", categoryId); eq("user_id", userId); eq("type", "expense") }
            }.decodeList<BudgetCategory>().firstOrNull()
        } catch (error: Exception) {
            logOriginalError("CATEGORY VALIDATION before $operation", error)
            throw error
        }
        Log.i(TAG, "$operation category validation: ownedExpenseCategory=${ownedCategory != null}")
        checkNotNull(ownedCategory) { "Kategori pengeluaran tidak ditemukan untuk akun ini." }

        val values = buildJsonObject {
            put("user_id", userId)
            put("category_id", categoryId)
            put("month", month.atDay(1).toString())
            put("limit_amount", budgetMoneyJson(limit))
        }
        Log.d(TAG, "$operation public.budgets payload columns=[user_id, category_id, month, limit_amount]")

        val row = try {
            if (id == null) {
                client.from("budgets").insert(values) { select() }.decodeSingle<BudgetRecord>()
            } else {
                client.from("budgets").update(values) {
                    filter { eq("id", id); eq("user_id", userId) }
                    select()
                }.decodeSingle<BudgetRecord>()
            }
        } catch (error: Exception) {
            logOriginalError(operation, error)
            throw error
        }
        val current = list(YearMonth.from(LocalDate.parse(row.month))).firstOrNull { it.id == row.id }
        return current ?: Budget(row.id, row.categoryId, ownedCategory.name, row.limitAmount, BigDecimal.ZERO,
            YearMonth.from(LocalDate.parse(row.month)))
    }

    private fun logAuthContext(operation: String) {
        val session = client.auth.currentSessionOrNull()
        val activeUser = client.auth.currentUserOrNull()
        val authenticated = session != null && activeUser != null
        Log.i(TAG, "$operation auth context: requestRole=${if (authenticated) "authenticated session" else "anon (no user session)"}, " +
            "sessionPresent=${session != null}, userMatchesRepository=${activeUser?.id == userId}")
    }

    private fun logOriginalError(operation: String, error: Exception) {
        val restError = error as? RestException
        val postgrestError = error as? PostgrestRestException
        Log.e(TAG, "$operation public.budgets failed: exception=${error::class.qualifiedName}, " +
            "httpStatus=${restError?.statusCode}, postgrestCode=${postgrestError?.code}, " +
            "originalMessage=${error.message}", error)
    }

    private companion object {
        const val TAG = "MomeBudgetRepo"
    }

    override suspend fun delete(id: String) {
        checkSession()
        client.from("budgets").delete {
            filter { eq("id", id); eq("user_id", userId) }
            select()
        }.decodeSingle<BudgetRecord>()
    }
}

@Serializable
private data class BudgetRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("category_id") val categoryId: String,
    val month: String,
    @SerialName("limit_amount") @Serializable(with = BudgetMoneySerializer::class) val limitAmount: BigDecimal,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
private data class ExpenseRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("category_id") val categoryId: String,
    val type: String,
    @Serializable(with = BudgetMoneySerializer::class) val amount: BigDecimal,
    @SerialName("transaction_date") val transactionDate: String,
    val description: String? = null
)

private object BudgetMoneySerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BudgetMoney", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal = if (decoder is JsonDecoder)
        decoder.decodeJsonElement().jsonPrimitive.content.toBigDecimal() else decoder.decodeString().toBigDecimal()
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) encoder.encodeJsonElement(budgetMoneyJson(value)) else encoder.encodeString(value.toPlainString())
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun budgetMoneyJson(value: BigDecimal): JsonPrimitive = JsonUnquotedLiteral(value.toPlainString())
