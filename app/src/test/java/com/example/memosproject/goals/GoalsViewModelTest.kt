package com.example.memosproject.goals

import com.example.memosproject.data.goals.Goal
import com.example.memosproject.data.goals.GoalDraft
import com.example.memosproject.data.goals.GoalImageChange
import com.example.memosproject.data.goals.GoalImageUpload
import com.example.memosproject.data.goals.GoalSaveResult
import com.example.memosproject.data.goals.GoalsRepository
import com.example.memosproject.ui.goals.GoalField
import com.example.memosproject.ui.goals.GoalsPage
import com.example.memosproject.ui.goals.GoalsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeGoalsRepository()
    private val image = GoalImageUpload(byteArrayOf(1, 2, 3), "image/jpeg", "jpg")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadingAndEmptyStateComeFromRepository() = runTest {
        val vm = viewModel()
        assertTrue(vm.uiState.value.loading)
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.goals.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test fun listFailureCanBeRetried() = runTest {
        repository.listError = IOException("offline")
        val vm = viewModel()
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        repository.listError = null
        repository.items = listOf(sampleGoal())
        vm.refresh()
        runCurrent()
        assertEquals(repository.items, vm.uiState.value.goals)
        assertNull(vm.uiState.value.error)
    }

    @Test fun invalidInputNeverReachesRepository() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.addGoal()
        vm.updateField(GoalField.NAME, " ")
        vm.updateField(GoalField.TARGET, "0")
        vm.updateField(GoalField.SAVED, "-1")
        vm.save()
        runCurrent()
        assertEquals(0, repository.saveCalls)
        assertEquals(setOf(GoalField.NAME, GoalField.TARGET, GoalField.SAVED), vm.uiState.value.form?.errors?.keys)
        assertEquals(GoalsPage.FORM, vm.uiState.value.page)
    }

    @Test fun createNormalizesDataAndShowsPersistedDetail() = runTest {
        val vm = viewModel()
        runCurrent()
        fillNewGoal(vm)
        vm.updateField(GoalField.SAVED, "1500,25")
        vm.updateField(GoalField.DESCRIPTION, "  Perjalanan keluarga  ")
        vm.save()
        runCurrent()
        assertEquals(1, repository.saveCalls)
        assertEquals("Liburan", repository.lastDraft?.name)
        assertEquals(BigDecimal("1500.25"), repository.lastDraft?.savedAmount)
        assertEquals("Perjalanan keluarga", repository.lastDraft?.description)
        assertEquals(GoalsPage.DETAIL, vm.uiState.value.page)
        assertEquals(vm.uiState.value.goals.single(), vm.uiState.value.selectedGoal)
        assertNull(vm.uiState.value.form)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test fun failedSaveKeepsFormAndStableIdForRetry() = runTest {
        val vm = viewModel()
        runCurrent()
        fillNewGoal(vm)
        val formId = vm.uiState.value.form?.id
        repository.saveError = IOException("timeout")
        vm.save()
        runCurrent()
        assertEquals(GoalsPage.FORM, vm.uiState.value.page)
        assertEquals(formId, vm.uiState.value.form?.id)
        assertEquals("  Liburan  ", vm.uiState.value.form?.name)
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
        repository.saveError = null
        vm.save()
        runCurrent()
        assertEquals(formId, vm.uiState.value.selectedGoal?.id)
        assertEquals(1, vm.uiState.value.goals.size)
    }

    @Test fun missingDatabaseColumnIsReportedAsSchemaFailureInsteadOfInternetFailure() = runTest {
        repository.saveError = Exception("Could not find column in schema cache. Code: PGRST204")
        val vm = viewModel()
        runCurrent()
        fillNewGoal(vm)
        vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.error!!.contains("Struktur tabel Goals"))
        assertFalse(vm.uiState.value.error!!.contains("koneksi internet"))
        assertEquals(GoalsPage.FORM, vm.uiState.value.page)
    }

    @Test fun editingSavingsBeyondTargetPreservesImageAndUpdatesList() = runTest {
        repository.items = listOf(sampleGoal())
        val vm = viewModel()
        runCurrent()
        vm.openGoal(repository.items.single())
        vm.editGoal()
        assertEquals("owner/goal/image.jpg", vm.uiState.value.form?.existingImagePath)
        vm.updateField(GoalField.SAVED, "250")
        vm.save()
        runCurrent()
        assertEquals(GoalImageChange.Keep, repository.lastImageChange)
        assertEquals(BigDecimal("250"), vm.uiState.value.selectedGoal?.savedAmount)
        assertEquals(1f, vm.uiState.value.selectedGoal!!.progress, 0f)
        assertEquals(1, vm.uiState.value.goals.size)
    }

    @Test fun imageCanBeReplacedAndRemovedExplicitly() = runTest {
        repository.items = listOf(sampleGoal())
        val vm = viewModel()
        runCurrent()
        vm.openGoal(repository.items.single())
        vm.editGoal()
        vm.selectImage("content://picked")
        runCurrent()
        assertEquals(image, vm.uiState.value.form?.selectedImage)
        vm.save()
        runCurrent()
        assertTrue(repository.lastImageChange is GoalImageChange.Replace)
        vm.editGoal()
        vm.removeImage()
        assertNull(vm.uiState.value.form?.existingImagePath)
        vm.save()
        runCurrent()
        assertEquals(GoalImageChange.Remove, repository.lastImageChange)
        assertNull(vm.uiState.value.selectedGoal?.imagePath)
    }

    @Test fun pickerFailureKeepsExistingImageAndForm() = runTest {
        repository.items = listOf(sampleGoal())
        val vm = GoalsViewModel(repository) { throw IllegalArgumentException("Ukuran gambar maksimal 5 MB.") }
        runCurrent()
        vm.openGoal(repository.items.single())
        vm.editGoal()
        vm.selectImage("content://too-large")
        runCurrent()
        assertFalse(vm.uiState.value.readingImage)
        assertEquals("owner/goal/image.jpg", vm.uiState.value.form?.existingImagePath)
        assertEquals("Ukuran gambar maksimal 5 MB.", vm.uiState.value.error)
    }

    @Test fun duplicateSaveAndNavigationAreBlockedWhileSaving() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.saveGate = gate
        val vm = viewModel()
        runCurrent()
        fillNewGoal(vm)
        vm.save()
        vm.save()
        runCurrent()
        assertEquals(1, repository.saveCalls)
        assertTrue(vm.uiState.value.busy)
        vm.back()
        vm.updateField(GoalField.NAME, "Should not change")
        assertEquals(GoalsPage.FORM, vm.uiState.value.page)
        assertEquals("  Liburan  ", vm.uiState.value.form?.name)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun deleteRequiresConfirmationAndFailureRetainsDetail() = runTest {
        repository.items = listOf(sampleGoal())
        val vm = viewModel()
        runCurrent()
        vm.openGoal(repository.items.single())
        vm.delete()
        runCurrent()
        assertEquals(0, repository.deleteCalls)
        vm.confirmDelete(true)
        vm.confirmDelete(false)
        vm.delete()
        runCurrent()
        assertEquals(0, repository.deleteCalls)
        repository.deleteError = IOException("offline")
        vm.confirmDelete(true)
        vm.delete()
        runCurrent()
        assertEquals(GoalsPage.DETAIL, vm.uiState.value.page)
        assertEquals(1, vm.uiState.value.goals.size)
        assertNotNull(vm.uiState.value.error)
        repository.deleteError = null
        vm.confirmDelete(true)
        vm.delete()
        runCurrent()
        assertEquals(GoalsPage.LIST, vm.uiState.value.page)
        assertTrue(vm.uiState.value.goals.isEmpty())
        assertNull(vm.uiState.value.selectedGoal)
        assertNotNull(vm.uiState.value.notice)
    }

    @Test fun cleanupFailureStillReportsSuccessfulSaveAndWarning() = runTest {
        repository.cleanupPending = true
        val vm = viewModel()
        runCurrent()
        fillNewGoal(vm)
        vm.save()
        runCurrent()
        assertEquals(GoalsPage.DETAIL, vm.uiState.value.page)
        assertTrue(vm.uiState.value.notice!!.text.contains("dicoba ulang"))
        val notice = vm.uiState.value.notice!!
        vm.consumeNotice("unrelated")
        assertEquals(notice, vm.uiState.value.notice)
        vm.consumeNotice(notice.id)
        assertNull(vm.uiState.value.notice)
    }

    @Test fun cancellingEditsDoesNotChangeSavedGoal() = runTest {
        repository.items = listOf(sampleGoal())
        val vm = viewModel()
        runCurrent()
        vm.openGoal(repository.items.single())
        vm.editGoal()
        vm.updateField(GoalField.SAVED, "500")
        vm.removeImage()
        vm.back()
        assertEquals(GoalsPage.DETAIL, vm.uiState.value.page)
        assertEquals(BigDecimal("25"), vm.uiState.value.selectedGoal?.savedAmount)
        assertEquals("owner/goal/image.jpg", vm.uiState.value.selectedGoal?.imagePath)
        assertEquals(0, repository.saveCalls)
    }

    private fun viewModel() = GoalsViewModel(repository) { image }

    private fun fillNewGoal(vm: GoalsViewModel) {
        vm.addGoal()
        vm.updateField(GoalField.NAME, "  Liburan  ")
        vm.updateField(GoalField.TARGET, "1000")
    }

    private fun sampleGoal() = Goal("goal", "owner", "Laptop", BigDecimal("100"), BigDecimal("25"),
        imagePath = "owner/goal/image.jpg")
}

private class FakeGoalsRepository : GoalsRepository {
    var items = emptyList<Goal>()
    var listError: Exception? = null
    var saveError: Exception? = null
    var deleteError: Exception? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var saveCalls = 0
    var deleteCalls = 0
    var lastDraft: GoalDraft? = null
    var lastImageChange: GoalImageChange? = null
    var cleanupPending = false

    override suspend fun list(): List<Goal> {
        listError?.let { throw it }
        return items
    }

    override suspend fun save(id: String, original: Goal?, draft: GoalDraft, imageChange: GoalImageChange): GoalSaveResult {
        saveCalls++
        lastDraft = draft
        lastImageChange = imageChange
        saveGate?.await()
        saveError?.let { throw it }
        val goal = Goal(id, "owner", draft.name, draft.targetAmount, draft.savedAmount, draft.description,
            when (imageChange) {
                GoalImageChange.Keep -> original?.imagePath
                GoalImageChange.Remove -> null
                is GoalImageChange.Replace -> "owner/$id/new-image.jpg"
            })
        items = items.filterNot { it.id == id } + goal
        return GoalSaveResult(goal, cleanupPending)
    }

    override suspend fun delete(goal: Goal): Boolean {
        deleteCalls++
        deleteError?.let { throw it }
        items = items.filterNot { it.id == goal.id }
        return cleanupPending
    }

    override suspend fun downloadImage(path: String) = byteArrayOf()
}
