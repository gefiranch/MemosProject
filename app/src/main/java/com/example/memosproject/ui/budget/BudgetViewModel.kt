package com.example.memosproject.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memosproject.data.budget.Budget
import com.example.memosproject.data.budget.BudgetCategory
import com.example.memosproject.data.budget.BudgetRepository
import com.example.memosproject.data.budget.BudgetTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class BudgetPage { LIST, DETAIL, FORM }

data class BudgetUiState(
    val budgets: List<Budget> = sampleBudgets(),
    val categories: List<BudgetCategory> = sampleCategories(),
    val page: BudgetPage = BudgetPage.LIST,
    val selectedId: String? = null,
    val formId: String? = null,
    val categoryId: String = "",
    val category: String = "",
    val amount: String = "",
    val period: YearMonth = YearMonth.now(),
    val loading: Boolean = false,
    val remoteReady: Boolean = false,
    val error: String? = null,
    val confirmDelete: Boolean = false
) {
    val selected: Budget? get() = budgets.find { it.id == selectedId }
    val totalLimit: BigDecimal get() = budgets.filter { it.period == period }.fold(BigDecimal.ZERO) { sum, item -> sum + item.limit }
    val totalSpent: BigDecimal get() = budgets.filter { it.period == period }.fold(BigDecimal.ZERO) { sum, item -> sum + item.spent }
    val totalRemaining: BigDecimal get() = totalLimit - totalSpent
}

class BudgetViewModel(private val repository: BudgetRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(BudgetUiState())
    val uiState = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        val state = _uiState.value
        if (state.loading) return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val categories = repository.listCategories()
                val budgets = repository.list(_uiState.value.period)
                _uiState.update { current -> current.copy(
                    categories = categories,
                    budgets = budgets,
                    remoteReady = true,
                    loading = false,
                    error = null
                ) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, error = budgetError(error, "memuat anggaran")) }
            }
        }
    }

    fun open(budget: Budget) = _uiState.update { it.copy(page = BudgetPage.DETAIL, selectedId = budget.id, error = null) }
    fun add() = _uiState.update { it.copy(page = BudgetPage.FORM, selectedId = null, formId = null, categoryId = "", category = "", amount = "", error = null) }
    fun edit() {
        val item = _uiState.value.selected ?: return
        _uiState.update { it.copy(page = BudgetPage.FORM, formId = item.id, categoryId = item.categoryId, category = item.category, amount = item.limit.toPlainString(), period = item.period, error = null) }
    }
    fun back() = _uiState.update { state ->
        when (state.page) {
            BudgetPage.FORM -> state.copy(page = if (state.formId == null) BudgetPage.LIST else BudgetPage.DETAIL, error = null)
            BudgetPage.DETAIL -> state.copy(page = BudgetPage.LIST, selectedId = null, error = null)
            BudgetPage.LIST -> state
        }
    }
    fun category(value: BudgetCategory) = _uiState.update { it.copy(categoryId = value.id, category = value.name, error = null) }
    fun amount(value: String) = _uiState.update { it.copy(amount = value.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(20), error = null) }
    fun movePeriod(months: Long) {
        val changed = _uiState.value.period.plusMonths(months)
        _uiState.update { it.copy(period = changed) }
        if (_uiState.value.remoteReady) refresh()
    }
    fun confirmDelete(show: Boolean) = _uiState.update { it.copy(confirmDelete = show) }
    fun delete() {
        val id = _uiState.value.selectedId ?: return
        if (_uiState.value.remoteReady) {
            _uiState.update { it.copy(loading = true, confirmDelete = false, error = null) }
            viewModelScope.launch {
                try {
                    repository.delete(id)
                    _uiState.update { current -> current.copy(budgets = current.budgets.filterNot { it.id == id }, selectedId = null, page = BudgetPage.LIST, loading = false) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { _uiState.update { it.copy(loading = false, error = budgetError(error, "menghapus anggaran")) } }
            }
        } else {
            _uiState.update { it.copy(budgets = it.budgets.filterNot { budget -> budget.id == id }, selectedId = null, page = BudgetPage.LIST, confirmDelete = false, error = null) }
        }
    }
    fun save() {
        val state = _uiState.value
        val amount = state.amount.trim().replace(',', '.').toBigDecimalOrNull()
        if (state.categoryId.isBlank()) { _uiState.update { it.copy(error = "Pilih kategori anggaran.") }; return }
        if (amount == null || amount <= BigDecimal.ZERO || amount.stripTrailingZeros().scale() > 2) {
            _uiState.update { it.copy(error = "Masukkan nominal lebih dari 0 dengan maksimal 2 angka desimal.") }; return
        }
        val duplicate = state.budgets.any { it.period == state.period && it.category == state.category && it.id != state.formId }
        if (duplicate) { _uiState.update { it.copy(error = "Kategori ini sudah memiliki anggaran pada periode tersebut.") }; return }
        val previous = state.budgets.find { it.id == state.formId }
        if (_uiState.value.remoteReady) {
            _uiState.update { it.copy(loading = true, error = null) }
            viewModelScope.launch {
                try {
                    val saved = if (state.formId == null) repository.insert(state.categoryId, state.period, amount)
                    else repository.update(state.formId, state.categoryId, state.period, amount)
                    val refreshed = repository.list(saved.period)
                    _uiState.update { current -> current.copy(
                        budgets = refreshed,
                        period = saved.period,
                        page = BudgetPage.DETAIL, selectedId = saved.id, formId = null, loading = false, error = null
                    ) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { _uiState.update { it.copy(loading = false, error = budgetError(error, "menyimpan anggaran")) } }
            }
        } else {
            val previous = state.budgets.find { it.id == state.formId }
            val updated = Budget(state.formId ?: UUID.randomUUID().toString(), state.categoryId, state.category, amount,
                previous?.spent ?: BigDecimal.ZERO, state.period, previous?.transactions.orEmpty())
            _uiState.update { current -> current.copy(
                budgets = (current.budgets.filterNot { it.id == updated.id } + updated).sortedBy { it.category },
                page = BudgetPage.DETAIL, selectedId = updated.id, formId = null, error = null
            ) }
        }
    }
}

private fun budgetError(error: Exception, action: String): String = when {
    error.message.orEmpty().contains("Sesi berakhir", true) -> "Sesi berakhir. Silakan login kembali."
    error is java.io.IOException -> "Gagal $action. Periksa koneksi internet, lalu coba lagi."
    else -> "Gagal $action. Periksa koneksi, akses Data API, dan policy RLS Supabase."
}

fun formatBudgetMoney(value: BigDecimal): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID"))
    .apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(value)

fun formatBudgetPeriod(period: YearMonth): String = period.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("id-ID")))

private fun sampleBudgets(): List<Budget> {
    val period = YearMonth.now()
    return listOf(
        Budget("food", "category-food", "Makanan", BigDecimal("2000000"), BigDecimal("1250000"), period, listOf(
            BudgetTransaction("f1", "Makan siang", "04 Nov 2026", BigDecimal("45000")),
            BudgetTransaction("f2", "Belanja bahan makanan", "03 Nov 2026", BigDecimal("185000")),
            BudgetTransaction("f3", "Kopi", "02 Nov 2026", BigDecimal("32000"))
        )),
        Budget("transport", "category-transport", "Transportasi", BigDecimal("800000"), BigDecimal("520000"), period, listOf(
            BudgetTransaction("t1", "Transportasi online", "04 Nov 2026", BigDecimal("28000")),
            BudgetTransaction("t2", "Isi saldo kartu", "01 Nov 2026", BigDecimal("150000"))
        )),
        Budget("entertainment", "category-entertainment", "Hiburan", BigDecimal("600000"), BigDecimal("160000"), period, listOf(
            BudgetTransaction("e1", "Tiket bioskop", "02 Nov 2026", BigDecimal("80000"))
        )),
        Budget("shopping", "category-shopping", "Belanja", BigDecimal("1000000"), BigDecimal("1080000"), period, listOf(
            BudgetTransaction("s1", "Perlengkapan rumah", "03 Nov 2026", BigDecimal("225000"))
        ))
    )
}

private fun sampleCategories(): List<BudgetCategory> = listOf(
    BudgetCategory("category-food", "", "Makanan", "expense"),
    BudgetCategory("category-transport", "", "Transportasi", "expense"),
    BudgetCategory("category-entertainment", "", "Hiburan", "expense"),
    BudgetCategory("category-shopping", "", "Belanja", "expense")
)
