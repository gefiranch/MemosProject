package com.example.memosproject.data.budget

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

@Serializable
data class BudgetCategory(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val type: String,
    @SerialName("created_at") val createdAt: String? = null
)

data class BudgetTransaction(
    val id: String,
    val title: String,
    /** ISO date from transactions.transaction_date. */
    val date: String,
    val amount: BigDecimal
)

data class Budget(
    val id: String,
    val categoryId: String,
    val category: String,
    val limit: BigDecimal,
    val spent: BigDecimal,
    val period: YearMonth,
    val transactions: List<BudgetTransaction> = emptyList()
) {
    val remaining: BigDecimal get() = limit.subtract(spent)
    val usage: Float get() = if (limit.signum() <= 0) 0f else
        spent.divide(limit, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    val usagePercent: BigDecimal get() = if (limit.signum() <= 0) BigDecimal.ZERO else
        spent.multiply(BigDecimal(100)).divide(limit, 2, RoundingMode.HALF_UP)
}
