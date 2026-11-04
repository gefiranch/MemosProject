package com.example.memosproject.transaction

import com.example.memosproject.data.transaction.Category
import com.example.memosproject.data.transaction.Transaction
import com.example.memosproject.data.transaction.TransactionDraft
import com.example.memosproject.data.transaction.parseTransactionAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TransactionTest {

    @Test
    fun parseAmount_validFormats() {
        assertEquals(BigDecimal("50000"), parseTransactionAmount("50000"))
        assertEquals(BigDecimal("150000.50"), parseTransactionAmount("150000.50"))
        assertEquals(BigDecimal("150000.50"), parseTransactionAmount("150000,50"))
    }

    @Test
    fun parseAmount_invalidFormats() {
        assertNull(parseTransactionAmount("abc"))
        assertNull(parseTransactionAmount("-5000"))
        assertNull(parseTransactionAmount("0"))
        assertNull(parseTransactionAmount("150.999")) // lebih dari 2 desimal
    }

    @Test
    fun draft_validationSuccess() {
        val draft = TransactionDraft(
            categoryId = "cat-123",
            type = "expense",
            amount = BigDecimal("50000"),
            transactionDate = "2026-11-04",
            description = "Makan siang"
        )
        draft.validate()
        assertEquals("expense", draft.type)

        val draftIncome = TransactionDraft(
            categoryId = "cat-124",
            type = "income",
            amount = BigDecimal("1000000"),
            transactionDate = "2026-11-04",
            description = "Gaji"
        )
        draftIncome.validate()
        assertEquals("income", draftIncome.type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun draft_validationMissingCategory() {
        val draft = TransactionDraft(
            categoryId = "",
            type = "pengeluaran",
            amount = BigDecimal("50000"),
            transactionDate = "2026-11-04",
            description = null
        )
        draft.validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun draft_validationInvalidType() {
        val draft = TransactionDraft(
            categoryId = "cat-123",
            type = "investasi",
            amount = BigDecimal("50000"),
            transactionDate = "2026-11-04",
            description = null
        )
        draft.validate()
    }

    @Test
    fun transaction_categoryNameFallback() {
        val txWithoutCategory = Transaction(
            id = "tx-1",
            userId = "user-1",
            categoryId = "cat-1",
            type = "pemasukan",
            amount = BigDecimal("1000000"),
            transactionDate = "2026-11-04",
            description = "Gaji",
            categories = null
        )
        assertEquals("Tanpa Kategori", txWithoutCategory.categoryName)
        assertTrue(txWithoutCategory.isPemasukan)

        val txWithCategory = txWithoutCategory.copy(
            categories = Category(id = "cat-1", userId = "user-1", name = "Gaji Bulanan", type = "pemasukan")
        )
        assertEquals("Gaji Bulanan", txWithCategory.categoryName)
    }
}
