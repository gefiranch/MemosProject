package com.example.memosproject.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memosproject.data.checkin.Checkin
import com.example.memosproject.data.checkin.CheckinRepository
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

enum class CheckinPage { LIST, DETAIL, FORM }
data class CheckinState(
    val items: List<Checkin> = emptyList(), val page: CheckinPage = CheckinPage.LIST,
    val selected: Checkin? = null, val status: String = "Sesuai Rencana",
    val reflection: String = "", val plan: String = "", val loading: Boolean = false,
    val error: String? = null, val confirmDelete: Boolean = false
)
class CheckinViewModel(private val repository: CheckinRepository, private val userId: String) : ViewModel() {
    private val state = MutableStateFlow(CheckinState())
    val uiState = state.asStateFlow()
    init { refresh() }
    fun refresh() { if (state.value.loading) return; runOperation { val items = repository.list(); state.update { it.copy(items = items) } } }
    fun open(item: Checkin) { if (!state.value.loading) state.update { it.copy(page = CheckinPage.DETAIL, selected = item, error = null) } }
    fun add() {
        if (state.value.loading) return
        if (state.value.items.any { it.date == LocalDate.now().toString() }) {
            state.update { it.copy(error = "Kamu sudah check-in hari ini. Buka catatan untuk mengubahnya.") }; return
        }
        state.update { it.copy(page = CheckinPage.FORM, selected = null, status = "Sesuai Rencana", reflection = "", plan = "", error = null) }
    }
    fun edit() { val item = state.value.selected ?: return; state.update { it.copy(page = CheckinPage.FORM, status = item.status, reflection = item.reflection, plan = item.plan, error = null) } }
    fun back() { if (!state.value.loading) state.update { it.copy(page = if (it.page == CheckinPage.FORM && it.selected != null) CheckinPage.DETAIL else CheckinPage.LIST, error = null) } }
    fun status(value: String) { state.update { it.copy(status = value) } }
    fun reflection(value: String) { state.update { it.copy(reflection = value.take(2000)) } }
    fun plan(value: String) { state.update { it.copy(plan = value.take(2000)) } }
    fun save() {
        val s = state.value
        if (s.loading) return
        if (s.reflection.isBlank() || s.plan.isBlank()) { state.update { it.copy(error = "Isi refleksi dan rencana untuk besok.") }; return }
        val item = (s.selected ?: Checkin(UUID.randomUUID().toString(), userId, LocalDate.now().toString(), s.status, "", ""))
            .copy(status = s.status, reflection = s.reflection.trim(), plan = s.plan.trim())
        runOperation {
            val saved = if (s.selected == null) repository.insert(item) else repository.update(item)
            state.update { it.copy(items = (it.items.filterNot { old -> old.id == saved.id } + saved).sortedByDescending { entry -> entry.date }, selected = saved, page = CheckinPage.DETAIL) }
        }
    }
    fun confirmDelete(show: Boolean) { state.update { it.copy(confirmDelete = show) } }
    fun delete() { val item = state.value.selected ?: return; if (state.value.loading) return; runOperation {
        repository.delete(item)
        state.update { it.copy(items = it.items.filterNot { entry -> entry.id == item.id }, selected = null, page = CheckinPage.LIST, confirmDelete = false) }
    } }
    private fun runOperation(action: suspend () -> Unit) {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val message = if ((e as? PostgrestRestException)?.code == "23505") "Check-in untuk tanggal ini sudah ada. Muat ulang riwayat." else "Gagal memproses check-in. Periksa koneksi, sesi, dan konfigurasi database, lalu coba lagi."
                state.update { it.copy(error = message) }
            } finally { state.update { it.copy(loading = false) } }
        }
    }
}
