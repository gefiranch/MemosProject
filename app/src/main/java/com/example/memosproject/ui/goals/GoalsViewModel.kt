package com.example.memosproject.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.memosproject.data.goals.Goal
import com.example.memosproject.data.goals.GoalDraft
import com.example.memosproject.data.goals.GoalImageChange
import com.example.memosproject.data.goals.GoalImageUpload
import com.example.memosproject.data.goals.GoalsRepository
import com.example.memosproject.data.goals.parseGoalAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

enum class GoalsPage { LIST, DETAIL, FORM }
enum class GoalField { NAME, TARGET, SAVED, DESCRIPTION }

data class GoalFormState(
    val id: String = UUID.randomUUID().toString(),
    val original: Goal? = null,
    val name: String = "",
    val target: String = "",
    val saved: String = "0",
    val description: String = "",
    val selectedImage: GoalImageUpload? = null,
    val removeImage: Boolean = false,
    val errors: Map<GoalField, String> = emptyMap()
) {
    val existingImagePath: String? get() = if (removeImage) null else original?.imagePath
}

data class GoalNotice(val text: String, val id: String = UUID.randomUUID().toString())

data class GoalsUiState(
    val goals: List<Goal> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val readingImage: Boolean = false,
    val page: GoalsPage = GoalsPage.LIST,
    val selectedId: String? = null,
    val form: GoalFormState? = null,
    val confirmDelete: Boolean = false,
    val error: String? = null,
    val notice: GoalNotice? = null
) {
    val selectedGoal: Goal? get() = goals.find { it.id == selectedId }
    val locked: Boolean get() = busy || readingImage
}

class GoalsViewModel(
    private val repository: GoalsRepository,
    private val readImage: suspend (String) -> GoalImageUpload
) : ViewModel() {
    private val _uiState = MutableStateFlow(GoalsUiState())
    val uiState = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true || _uiState.value.locked || _uiState.value.page == GoalsPage.FORM) return
        _uiState.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val goals = repository.list()
                _uiState.update {
                    val missing = it.selectedId != null && goals.none { goal -> goal.id == it.selectedId }
                    it.copy(goals = goals, loading = false,
                        page = if (missing) GoalsPage.LIST else it.page,
                        selectedId = if (missing) null else it.selectedId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, error = goalError(error, "memuat goals")) }
            }
        }
    }

    fun openGoal(goal: Goal) {
        if (_uiState.value.locked) return
        _uiState.update { it.copy(page = GoalsPage.DETAIL, selectedId = goal.id, error = null) }
    }

    fun addGoal() {
        if (_uiState.value.locked || _uiState.value.loading) return
        _uiState.update { it.copy(page = GoalsPage.FORM, form = GoalFormState(), selectedId = null, error = null) }
    }

    fun editGoal() {
        if (_uiState.value.locked || _uiState.value.loading) return
        val goal = _uiState.value.selectedGoal ?: return
        _uiState.update { it.copy(page = GoalsPage.FORM, error = null, form = GoalFormState(
            id = goal.id, original = goal, name = goal.name,
            target = goal.targetAmount.toPlainString(), saved = goal.savedAmount.toPlainString(),
            description = goal.description.orEmpty()
        )) }
    }

    fun back() {
        if (_uiState.value.locked) return
        _uiState.update {
            it.copy(page = if (it.page == GoalsPage.FORM && it.form?.original != null) GoalsPage.DETAIL else GoalsPage.LIST,
                form = null, confirmDelete = false, error = null)
        }
    }

    fun updateField(field: GoalField, value: String) {
        if (_uiState.value.locked) return
        _uiState.update { state ->
            val form = state.form ?: return@update state
            val updated = when (field) {
                GoalField.NAME -> form.copy(name = value.take(100))
                GoalField.TARGET -> form.copy(target = value.take(24))
                GoalField.SAVED -> form.copy(saved = value.take(24))
                GoalField.DESCRIPTION -> form.copy(description = value.take(2000))
            }
            state.copy(form = updated.copy(errors = updated.errors - field), error = null)
        }
    }

    fun selectImage(uri: String) {
        if (_uiState.value.locked || _uiState.value.form == null) return
        _uiState.update { it.copy(readingImage = true, error = null) }
        viewModelScope.launch {
            try {
                val image = readImage(uri)
                _uiState.update { it.copy(form = it.form?.copy(selectedImage = image, removeImage = false)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(error = if (error is IllegalArgumentException) error.message
                    else "Gambar tidak dapat dibuka. Silakan pilih ulang.") }
            } finally {
                _uiState.update { it.copy(readingImage = false) }
            }
        }
    }

    fun removeImage() {
        if (!_uiState.value.locked) _uiState.update {
            it.copy(form = it.form?.copy(selectedImage = null, removeImage = true), error = null)
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.locked || state.loading) return
        val form = state.form ?: return
        val target = parseGoalAmount(form.target)
        val saved = parseGoalAmount(form.saved)
        val errors = buildMap {
            if (form.name.isBlank()) put(GoalField.NAME, "Masukkan nama tujuan.")
            if (target == null || target <= BigDecimal.ZERO)
                put(GoalField.TARGET, "Target harus lebih dari 0, maksimal 16 digit dan 2 desimal.")
            if (saved == null || saved < BigDecimal.ZERO)
                put(GoalField.SAVED, "Jumlah terkumpul minimal 0, maksimal 16 digit dan 2 desimal.")
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(form = form.copy(errors = errors)) }
            return
        }
        val draft = GoalDraft(form.name.trim(), requireNotNull(target), requireNotNull(saved),
            form.description.trim().takeIf { it.isNotEmpty() })
        val image = when {
            form.selectedImage != null -> GoalImageChange.Replace(form.selectedImage)
            form.removeImage -> GoalImageChange.Remove
            else -> GoalImageChange.Keep
        }
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.save(form.id, form.original, draft, image)
                _uiState.update {
                    val goals = if (it.goals.any { goal -> goal.id == result.goal.id })
                        it.goals.map { goal -> if (goal.id == result.goal.id) result.goal else goal }
                        else listOf(result.goal) + it.goals
                    it.copy(goals = goals, page = GoalsPage.DETAIL, selectedId = result.goal.id, form = null,
                        notice = GoalNotice((if (form.original == null) "Goal berhasil ditambahkan." else "Goal berhasil diperbarui.") +
                            if (result.cleanupPending) " Penghapusan gambar lama akan dicoba ulang saat memuat goals." else ""))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(error = goalError(error, "menyimpan goal")) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun confirmDelete(show: Boolean) {
        if (!_uiState.value.locked) _uiState.update { it.copy(confirmDelete = show, error = null) }
    }

    fun delete() {
        val state = _uiState.value
        if (state.locked || !state.confirmDelete) return
        val goal = state.selectedGoal ?: return
        _uiState.update { it.copy(busy = true, confirmDelete = false, error = null) }
        viewModelScope.launch {
            try {
                val cleanupPending = repository.delete(goal)
                _uiState.update { it.copy(goals = it.goals.filterNot { item -> item.id == goal.id },
                    selectedId = null, page = GoalsPage.LIST,
                    notice = GoalNotice("Goal berhasil dihapus." + if (cleanupPending)
                        " Penghapusan gambar akan dicoba ulang saat memuat goals." else "")) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(error = goalError(error, "menghapus goal")) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    suspend fun loadImage(path: String): ByteArray = repository.downloadImage(path)

    fun consumeNotice(id: String) = _uiState.update { if (it.notice?.id == id) it.copy(notice = null) else it }
}

private fun goalError(error: Exception, action: String): String {
    val detail = error.message.orEmpty()
    val databaseError = error as? PostgrestRestException
    val code = databaseError?.code
    val status = (error as? RestException)?.statusCode
    return when {
        detail.contains("Sesi berakhir") || status == 401 -> "Sesi berakhir. Silakan login kembali."
        code in setOf("PGRST204", "42703") || detail.contains("PGRST204") || detail.contains("42703") ->
            "Struktur tabel Goals belum sesuai dengan aplikasi. Periksa kolom Goals dan jalankan migrasi Supabase."
        code in setOf("PGRST205", "42P01") || detail.contains("PGRST205") || detail.contains("42P01") || detail.contains("Bucket not found", true) ->
            "Penyimpanan Goals belum siap. Jalankan migrasi Supabase Financial Goals."
        status == 403 || code == "42501" || detail.contains("row-level security", true) || detail.contains("42501") ->
            "Akses Goals ditolak. Periksa sesi dan kebijakan RLS Supabase."
        error is SerializationException -> "Data Goals dari server tidak sesuai. Periksa struktur tabel Goals di Supabase."
        error is IllegalArgumentException -> detail.ifBlank { "Data goal tidak valid." }
        error is IOException -> "Gagal $action. Periksa koneksi internet, lalu coba lagi."
        databaseError != null -> "Gagal $action. Database menolak data goal${code?.let { " (kode $it)" }.orEmpty()}."
        else -> "Gagal $action. Silakan coba lagi."
    }
}
