package com.example.memosproject.ui.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memosproject.data.bills.Bill
import com.example.memosproject.data.bills.BillDraft
import com.example.memosproject.data.bills.BillRepository
import com.example.memosproject.data.bills.parseBillAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

enum class BillPage {
    LIST, DETAIL, FORM
}

data class BillFormState(
    val id: String? = null,
    val title: String = "",
    val amount: String = "",
    val dueDate: String = LocalDate.now().toString(),
    val isPaid: Boolean = false,
    val notes: String = "",
    val errorTitle: String? = null,
    val errorAmount: String? = null,
    val errorDate: String? = null
)

data class BillNotice(val text: String)

data class BillUiState(
    val bills: List<Bill> = emptyList(),
    val page: BillPage = BillPage.LIST,
    val selectedId: String? = null,
    val form: BillFormState? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val confirmDelete: Boolean = false,
    val error: String? = null,
    val notice: BillNotice? = null
) {
    val selectedBill: Bill?
        get() = bills.find { it.id == selectedId }

    // Tagihan belum lunas ditampilkan lebih dahulu.
    // Dalam masing-masing kelompok, tanggal terdekat ditampilkan dahulu.
    val sortedBills: List<Bill>
        get() = bills.sortedWith(
            compareBy<Bill> { it.isPaid }
                .thenBy { it.dueDate }
                .thenBy { it.createdAt.orEmpty() }
        )

    val unpaidBills: List<Bill>
        get() = sortedBills.filter { !it.isPaid }

    val paidBills: List<Bill>
        get() = sortedBills.filter { it.isPaid }
}

class BillViewModel(
    private val repository: BillRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BillUiState())
    val uiState = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true || _uiState.value.busy) return

        _uiState.update {
            it.copy(loading = true, error = null)
        }

        loadJob = viewModelScope.launch {
            try {
                val bills = repository.listBills()

                _uiState.update { state ->
                    val missing = state.selectedId != null &&
                            bills.none { it.id == state.selectedId }

                    state.copy(
                        bills = bills,
                        loading = false,
                        selectedId = if (missing) null else state.selectedId,
                        page = if (
                            missing && state.page == BillPage.DETAIL
                        ) BillPage.LIST else state.page
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = mapError(e, "memuat tagihan")
                    )
                }
            }
        }
    }

    fun openDetail(bill: Bill) {
        if (_uiState.value.busy) return

        _uiState.update {
            it.copy(
                page = BillPage.DETAIL,
                selectedId = bill.id,
                error = null
            )
        }
    }

    fun openAddForm() {
        val state = _uiState.value
        if (state.busy || state.loading) return

        _uiState.update {
            it.copy(
                page = BillPage.FORM,
                selectedId = null,
                form = BillFormState(),
                error = null
            )
        }
    }

    fun openEditForm() {
        val state = _uiState.value
        if (state.busy || state.loading) return

        val bill = state.selectedBill ?: return

        _uiState.update {
            it.copy(
                page = BillPage.FORM,
                error = null,
                form = BillFormState(
                    id = bill.id,
                    title = bill.title,
                    amount = bill.amount.toPlainString(),
                    dueDate = bill.dueDate,
                    isPaid = bill.isPaid,
                    notes = bill.notes.orEmpty()
                )
            )
        }
    }

    fun back() {
        if (_uiState.value.busy) return

        _uiState.update { state ->
            val nextPage = if (
                state.page == BillPage.FORM &&
                state.form?.id != null
            ) {
                BillPage.DETAIL
            } else {
                BillPage.LIST
            }

            state.copy(
                page = nextPage,
                form = null,
                confirmDelete = false,
                error = null
            )
        }
    }

    fun onTitleChange(value: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state

            state.copy(
                form = form.copy(
                    title = value.take(100),
                    errorTitle = null
                )
            )
        }
    }

    fun onAmountChange(value: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state

            state.copy(
                form = form.copy(
                    amount = value.take(20),
                    errorAmount = null
                )
            )
        }
    }

    fun onDueDateChange(value: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state

            state.copy(
                form = form.copy(
                    dueDate = value,
                    errorDate = null
                )
            )
        }
    }

    fun onPaidChange(value: Boolean) {
        _uiState.update { state ->
            val form = state.form ?: return@update state

            state.copy(
                form = form.copy(isPaid = value)
            )
        }
    }

    fun onNotesChange(value: String) {
        _uiState.update { state ->
            val form = state.form ?: return@update state

            state.copy(
                form = form.copy(notes = value.take(1000))
            )
        }
    }

    fun saveForm() {
        val state = _uiState.value

        if (state.busy || state.loading) return

        val form = state.form ?: return
        val parsedAmount = parseBillAmount(form.amount)

        val errorTitle = if (form.title.isBlank()) {
            "Nama tagihan wajib diisi."
        } else {
            null
        }

        val errorAmount = if (
            parsedAmount == null || parsedAmount <= BigDecimal.ZERO
        ) {
            "Nominal harus berupa angka lebih dari 0."
        } else {
            null
        }

        val errorDate = if (
            runCatching { LocalDate.parse(form.dueDate) }.isFailure
        ) {
            "Tanggal jatuh tempo tidak valid."
        } else {
            null
        }

        if (
            errorTitle != null ||
            errorAmount != null ||
            errorDate != null
        ) {
            _uiState.update {
                it.copy(
                    form = form.copy(
                        errorTitle = errorTitle,
                        errorAmount = errorAmount,
                        errorDate = errorDate
                    )
                )
            }
            return
        }

        val draft = BillDraft(
            title = form.title.trim(),
            amount = requireNotNull(parsedAmount),
            dueDate = form.dueDate,
            isPaid = form.isPaid,
            notes = form.notes.trim().takeIf { it.isNotEmpty() }
        )

        _uiState.update {
            it.copy(busy = true, error = null)
        }

        viewModelScope.launch {
            try {
                val isEdit = form.id != null

                val saved = if (isEdit) {
                    repository.update(
                        requireNotNull(form.id),
                        draft
                    )
                } else {
                    repository.insert(draft)
                }

                _uiState.update { current ->
                    val updatedBills = if (isEdit) {
                        current.bills.map {
                            if (it.id == saved.id) saved else it
                        }
                    } else {
                        current.bills + saved
                    }

                    current.copy(
                        bills = updatedBills,
                        page = BillPage.DETAIL,
                        selectedId = saved.id,
                        form = null,
                        busy = false,
                        notice = BillNotice(
                            if (isEdit) {
                                "Tagihan berhasil diperbarui."
                            } else {
                                "Tagihan berhasil ditambahkan."
                            }
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        error = mapError(e, "menyimpan tagihan")
                    )
                }
            }
        }
    }

    fun togglePaid() {
        val state = _uiState.value
        if (state.busy || state.loading) return

        val bill = state.selectedBill ?: return

        val draft = BillDraft(
            title = bill.title,
            amount = bill.amount,
            dueDate = bill.dueDate,
            isPaid = !bill.isPaid,
            notes = bill.notes
        )

        _uiState.update {
            it.copy(busy = true, error = null)
        }

        viewModelScope.launch {
            try {
                val updated = repository.update(bill.id, draft)

                _uiState.update { current ->
                    current.copy(
                        bills = current.bills.map {
                            if (it.id == updated.id) updated else it
                        },
                        busy = false,
                        notice = BillNotice(
                            if (updated.isPaid) {
                                "Tagihan ditandai sudah dibayar."
                            } else {
                                "Tagihan ditandai belum dibayar."
                            }
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        error = mapError(e, "mengubah status pembayaran")
                    )
                }
            }
        }
    }

    fun confirmDelete(show: Boolean) {
        if (_uiState.value.busy) return

        _uiState.update {
            it.copy(
                confirmDelete = show,
                error = null
            )
        }
    }

    fun deleteBill() {
        val state = _uiState.value

        if (state.busy || !state.confirmDelete) return

        val bill = state.selectedBill ?: return

        _uiState.update {
            it.copy(
                busy = true,
                confirmDelete = false,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                repository.delete(bill.id)

                _uiState.update { current ->
                    current.copy(
                        bills = current.bills.filterNot {
                            it.id == bill.id
                        },
                        selectedId = null,
                        page = BillPage.LIST,
                        busy = false,
                        notice = BillNotice(
                            "Tagihan berhasil dihapus."
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        busy = false,
                        error = mapError(e, "menghapus tagihan")
                    )
                }
            }
        }
    }

    fun consumeNotice() {
        _uiState.update {
            it.copy(notice = null)
        }
    }

    private fun mapError(
        error: Exception,
        action: String
    ): String {
        val detail = error.message.orEmpty()

        return when {
            detail.contains("Sesi berakhir", true) ->
                "Sesi berakhir. Silakan login kembali."

            detail.contains("row-level security", true) ||
                    detail.contains("42501") ->
                "Akses ditolak. Periksa kebijakan RLS Supabase."

            detail.contains("bills", true) &&
                    (detail.contains("42P01") ||
                            detail.contains("PGRST205")) ->
                "Tabel bills belum tersedia atau belum terdeteksi."

            error is IllegalArgumentException ->
                detail.ifBlank { "Data tagihan tidak valid." }

            else ->
                "Gagal $action: ${
                    detail.ifBlank { "Terjadi kesalahan." }
                }"
        }
    }
}
