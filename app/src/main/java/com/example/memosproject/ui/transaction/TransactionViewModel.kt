package com.example.memosproject.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memosproject.data.transaction.Category
import com.example.memosproject.data.transaction.Transaction
import com.example.memosproject.data.transaction.TransactionDraft
import com.example.memosproject.data.transaction.TransactionRepository
import com.example.memosproject.data.transaction.parseTransactionAmount
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate

enum class TransactionPage { LIST, DETAIL, FORM }
enum class TransactionFilter { SEMUA, PEMASUKAN, PENGELUARAN }

data class TransactionFormState(
    val id: String? = null, // null saat Create, terisi saat Edit
    val type: String = "expense", // "expense" atau "income"
    val categoryId: String = "",
    val amount: String = "",
    val transactionDate: String = LocalDate.now().toString(),
    val description: String = "",
    val categoryName: String = "",
    val errorCategory: String? = null,
    val errorAmount: String? = null,
    val errorDate: String? = null
)

data class TransactionNotice(val text: String)

data class TransactionUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val filter: TransactionFilter = TransactionFilter.SEMUA,
    val page: TransactionPage = TransactionPage.LIST,
    val selectedId: String? = null,
    val form: TransactionFormState? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val confirmDelete: Boolean = false,
    val error: String? = null,
    val categoryError: String? = null,
    val notice: TransactionNotice? = null
) {
    val selectedTransaction: Transaction? get() = transactions.find { it.id == selectedId }

    val filteredTransactions: List<Transaction> get() = when (filter) {
        TransactionFilter.SEMUA -> transactions
        TransactionFilter.PEMASUKAN -> transactions.filter { it.isPemasukan }
        TransactionFilter.PENGELUARAN -> transactions.filter { !it.isPemasukan }
    }
}

class TransactionViewModel(
    private val repository: TransactionRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(TransactionUiState())
    val uiState = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true || _uiState.value.busy) return
        _uiState.update { it.copy(loading = true, error = null, categoryError = null) }
        loadJob = viewModelScope.launch {
            try {
                val transactions = repository.listTransactions()
                var catError: String? = null
                val categories = try {
                    repository.listCategories()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    catError = mapError(e, "memuat kategori")
                    emptyList()
                }

                _uiState.update { state ->
                    val missing = state.selectedId != null && transactions.none { it.id == state.selectedId }
                    state.copy(
                        transactions = transactions,
                        categories = categories,
                        categoryError = catError,
                        loading = false,
                        page = if (missing && state.page == TransactionPage.DETAIL) TransactionPage.LIST else state.page,
                        selectedId = if (missing) null else state.selectedId
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = mapError(e, "memuat transaksi")) }
            }
        }
    }

    fun setFilter(filter: TransactionFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun openDetail(transaction: Transaction) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(page = TransactionPage.DETAIL, selectedId = transaction.id, error = null) }
    }

    fun openAddForm() {
        if (_uiState.value.busy || _uiState.value.loading) return
        val defaultType = if (_uiState.value.filter == TransactionFilter.PEMASUKAN) "income" else "expense"
        _uiState.update {
            it.copy(
                page = TransactionPage.FORM,
                selectedId = null,
                form = TransactionFormState(type = defaultType),
                error = null
            )
        }
    }

    fun openEditForm() {
        if (_uiState.value.busy || _uiState.value.loading) return
        val tx = _uiState.value.selectedTransaction ?: return
        val normalizedType = if (tx.type.equals("pemasukan", ignoreCase = true) || tx.type.equals("income", ignoreCase = true)) {
            "income"
        } else {
            "expense"
        }
        _uiState.update {
            it.copy(
                page = TransactionPage.FORM,
                error = null,
                form = TransactionFormState(
                    id = tx.id,
                    type = normalizedType,
                    categoryId = tx.categoryId,
                    amount = tx.amount.toPlainString(),
                    transactionDate = tx.transactionDate,
                    description = tx.description.orEmpty(),
                    categoryName = tx.categoryName
                )
            )
        }
    }

    fun back() {
        if (_uiState.value.busy) return
        _uiState.update { state ->
            val nextPage = if (state.page == TransactionPage.FORM && state.form?.id != null) {
                TransactionPage.DETAIL
            } else {
                TransactionPage.LIST
            }
            state.copy(page = nextPage, form = null, confirmDelete = false, error = null)
        }
    }

    fun onFormTypeChange(newType: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state
            val normalized = if (newType.equals("pemasukan", ignoreCase = true) || newType.equals("income", ignoreCase = true)) {
                "income"
            } else {
                "expense"
            }
            if (form.type.equals(normalized, ignoreCase = true)) return@update state
            // Saat jenis diganti, reset kategori terpilih sesuai aturan
            form.copy(
                type = normalized,
                categoryId = "",
                categoryName = "",
                errorCategory = null
            ).let { state.copy(form = it) }
        }
    }

    fun onFormCategoryChange(category: Category) {
        _uiState.update { state ->
            val form = state.form ?: return@update state
            form.copy(
                categoryId = category.id,
                categoryName = category.name,
                errorCategory = null
            ).let { state.copy(form = it) }
        }
    }

    fun onFormAmountChange(value: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state
            form.copy(amount = value.take(20), errorAmount = null).let { state.copy(form = it) }
        }
    }

    fun onFormDateChange(dateIso: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state
            form.copy(transactionDate = dateIso, errorDate = null).let { state.copy(form = it) }
        }
    }

    fun onFormDescriptionChange(value: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state
            form.copy(description = value.take(1000)).let { state.copy(form = it) }
        }
    }

    fun saveForm() {
        val state = _uiState.value
        if (state.busy || state.loading) return
        val form = state.form ?: return

        val parsedAmount = parseTransactionAmount(form.amount)
        var hasError = false
        var errorCat: String? = null
        var errorAmt: String? = null
        var errorDt: String? = null

        if (form.categoryId.isBlank()) {
            errorCat = "Pilih kategori untuk transaksi ini."
            hasError = true
        }
        if (parsedAmount == null || parsedAmount <= BigDecimal.ZERO) {
            errorAmt = "Nominal harus berupa angka lebih dari 0."
            hasError = true
        }
        if (runCatching { LocalDate.parse(form.transactionDate) }.isFailure) {
            errorDt = "Tanggal transaksi tidak valid."
            hasError = true
        }

        if (hasError) {
            _uiState.update {
                it.copy(
                    form = form.copy(
                        errorCategory = errorCat,
                        errorAmount = errorAmt,
                        errorDate = errorDt
                    )
                )
            }
            return
        }

        val draft = TransactionDraft(
            categoryId = form.categoryId,
            type = form.type,
            amount = requireNotNull(parsedAmount),
            transactionDate = form.transactionDate,
            description = form.description.trim().takeIf { it.isNotEmpty() }
        )

        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val isEdit = form.id != null
                val saved = if (isEdit) {
                    repository.update(requireNotNull(form.id), draft)
                } else {
                    repository.insert(draft)
                }

                // Perbarui list lokal dan arahkan ke detail transaksi
                _uiState.update { current ->
                    val updatedList = if (isEdit) {
                        current.transactions.map { if (it.id == saved.id) saved else it }
                    } else {
                        listOf(saved) + current.transactions
                    }.sortedWith(compareByDescending<Transaction> { it.transactionDate }.thenByDescending { it.createdAt.orEmpty() })

                    current.copy(
                        transactions = updatedList,
                        page = TransactionPage.DETAIL,
                        selectedId = saved.id,
                        form = null,
                        busy = false,
                        notice = TransactionNotice(if (isEdit) "Transaksi berhasil diperbarui." else "Transaksi berhasil disimpan.")
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = mapError(e, "menyimpan transaksi")) }
            }
        }
    }

    fun confirmDelete(show: Boolean) {
        if (!_uiState.value.busy) {
            _uiState.update { it.copy(confirmDelete = show, error = null) }
        }
    }

    fun deleteTransaction() {
        val state = _uiState.value
        if (state.busy || !state.confirmDelete) return
        val tx = state.selectedTransaction ?: return

        _uiState.update { it.copy(busy = true, confirmDelete = false, error = null) }
        viewModelScope.launch {
            try {
                repository.delete(tx.id)
                _uiState.update { current ->
                    current.copy(
                        transactions = current.transactions.filterNot { it.id == tx.id },
                        selectedId = null,
                        page = TransactionPage.LIST,
                        busy = false,
                        notice = TransactionNotice("Transaksi berhasil dihapus.")
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(busy = false, error = mapError(e, "menghapus transaksi")) }
            }
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun mapError(error: Exception, action: String): String {
        val detail = error.message.orEmpty()
        val databaseError = error as? PostgrestRestException
        val code = databaseError?.code
        val status = (error as? RestException)?.statusCode
        return when {
            detail.contains("Sesi berakhir") || status == 401 -> "Sesi berakhir. Silakan login kembali."
            code in setOf("PGRST205", "42P01") || detail.contains("42P01") ->
                "Tabel transaksi atau kategori belum tersedia di Supabase."
            status == 403 || code == "42501" || detail.contains("row-level security", true) ->
                "Akses ditolak. Periksa autentikasi atau kebijakan RLS Supabase."
            error is SerializationException -> "Format data transaksi tidak sesuai."
            error is IllegalArgumentException -> detail.ifBlank { "Data transaksi tidak valid." }
            error is IOException -> "Gagal $action. Periksa koneksi internet."
            databaseError != null -> "Gagal $action (Database: ${code ?: "error"})."
            else -> "Gagal $action: ${detail.ifBlank { "Terjadi kesalahan." }}"
        }
    }
}
