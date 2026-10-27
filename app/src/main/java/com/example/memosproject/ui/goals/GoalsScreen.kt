package com.example.memosproject.ui.goals

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.memosproject.data.SupabaseProvider
import com.example.memosproject.data.goals.Goal
import com.example.memosproject.data.goals.GoalImageCleanup
import com.example.memosproject.data.goals.GoalImageFiles
import com.example.memosproject.data.goals.SupabaseGoalsRepository
import com.example.memosproject.data.goals.decodeGoalImage
import com.example.memosproject.data.goals.formatGoalMoney
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Retains form state on rotation and clears the entire feature scope on logout. */
class GoalsViewModelOwner : ViewModel() {
    private var userId: String? = null
    private var store = ViewModelStore()

    fun get(context: Context, ownerId: String): GoalsViewModel {
        if (userId != ownerId) {
            clearSession()
            userId = ownerId
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GoalsViewModel(
                SupabaseGoalsRepository(SupabaseProvider.client, ownerId, GoalImageCleanup(context)),
                GoalImageFiles(context)::read
            ) as T
        }
        return ViewModelProvider(store, factory)[GoalsViewModel::class.java]
    }

    fun clearSession() {
        store.clear()
        store = ViewModelStore()
        userId = null
    }

    override fun onCleared() { store.clear() }
}

@Composable
fun rememberGoalsViewModel(): GoalsViewModel {
    val context = LocalContext.current
    val activity = context.requireActivity()
    val owner = remember(activity) { ViewModelProvider(activity)[GoalsViewModelOwner::class.java] }
    val userId = requireNotNull(SupabaseProvider.client.auth.currentUserOrNull()).id
    val viewModel = remember(owner, userId) { owner.get(context.applicationContext, userId) }
    DisposableEffect(owner, activity) {
        onDispose { if (!activity.isChangingConfigurations) owner.clearSession() }
    }
    return viewModel
}

private tailrec fun Context.requireActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.requireActivity()
    else -> error("Goals memerlukan Activity.")
}

@Composable
fun GoalsScreen(viewModel: GoalsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            snackbar.showSnackbar(notice.text)
            viewModel.consumeNotice(notice.id)
        }
    }
    BackHandler(enabled = state.page != GoalsPage.LIST) { viewModel.back() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.page == GoalsPage.LIST && !state.loading && !state.locked) {
                ExtendedFloatingActionButton(onClick = viewModel::addGoal,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Tambah goal") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy || state.readingImage || (state.loading && state.goals.isNotEmpty()))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                GoalError(error, if (state.page != GoalsPage.FORM) viewModel::refresh else null)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.page) {
                    GoalsPage.LIST -> GoalsGrid(state, viewModel::openGoal, viewModel::refresh, viewModel::addGoal, viewModel::loadImage)
                    GoalsPage.DETAIL -> state.selectedGoal?.let { goal ->
                        GoalDetailScreen(goal, state.locked || state.loading, viewModel::back,
                            viewModel::editGoal, { viewModel.confirmDelete(true) }, viewModel::loadImage)
                    }
                    GoalsPage.FORM -> state.form?.let { form ->
                        GoalFormScreen(form, state.locked, state.readingImage, viewModel::back,
                            viewModel::updateField, viewModel::selectImage, viewModel::removeImage,
                            viewModel::save, viewModel::loadImage)
                    }
                }
            }
        }
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(false) },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("Hapus goal?") },
            text = { Text("Tujuan \"${state.selectedGoal?.name.orEmpty()}\" beserta gambarnya akan dihapus permanen.") },
            confirmButton = { TextButton(onClick = viewModel::delete) {
                Text("Hapus", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { viewModel.confirmDelete(false) }) { Text("Batal") } }
        )
    }
}

@Composable
private fun GoalsGrid(
    state: GoalsUiState, onOpen: (Goal) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit,
    loadImage: suspend (String) -> ByteArray
) {
    if (state.loading && state.goals.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Memuat goals…")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wujudkan tujuanmu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Sedikit demi sedikit, semakin dekat.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Muat ulang goals")
                }
            }
        }
        if (state.goals.isNotEmpty()) {
            items(state.goals, key = { it.id }) { goal ->
                Card(onClick = { onOpen(goal) }, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    GoalImage(goal.imagePath, null, goal.name, loadImage,
                        Modifier.fillMaxWidth().aspectRatio(1.4f))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(goal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatGoalMoney(goal.savedAmount), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("dari ${formatGoalMoney(goal.targetAmount)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GoalProgress(goal)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Flag, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.error == null) "Belum ada goals" else "Goals belum dapat dimuat",
                        style = MaterialTheme.typography.titleLarge)
                    Text(if (state.error == null) "Mulai dari satu tujuan yang berarti untukmu." else "Coba muat ulang untuk mengambil data dari Supabase.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = if (state.error == null) onAdd else onRefresh) {
                        Text(if (state.error == null) "Buat goal pertama" else "Coba lagi")
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalDetailScreen(
    goal: Goal, busy: Boolean, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    loadImage: suspend (String) -> ByteArray
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali ke goals") }
            Text("Detail goal", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Default.Edit, "Edit goal") }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.DeleteOutline, "Hapus goal", tint = MaterialTheme.colorScheme.error)
            }
        }
        GoalImage(goal.imagePath, null, goal.name, loadImage,
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)))
        Text(goal.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Tabungan terkumpul", style = MaterialTheme.typography.labelLarge)
                Text(formatGoalMoney(goal.savedAmount), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                GoalProgress(goal)
                HorizontalDivider()
                AmountRow("Target", formatGoalMoney(goal.targetAmount))
                AmountRow("Sisa menuju target", formatGoalMoney(goal.remainingAmount))
                if (goal.isComplete) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Selamat, tujuanmu tercapai!", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Deskripsi", style = MaterialTheme.typography.titleMedium)
            Text(goal.description?.takeIf { it.isNotBlank() } ?: "Belum ada deskripsi.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            goalDate(goal.createdAt)?.let { Text("Dibuat $it", style = MaterialTheme.typography.bodySmall) }
            goalDate(goal.updatedAt)?.let { Text("Diperbarui $it", style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Edit goal & tabungan") }
    }
}

@Composable
private fun GoalFormScreen(
    form: GoalFormState, busy: Boolean, readingImage: Boolean, onBack: () -> Unit,
    onChange: (GoalField, String) -> Unit, onPick: (String) -> Unit, onRemove: () -> Unit,
    onSave: () -> Unit, loadImage: suspend (String) -> ByteArray
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPick(it.toString()) }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Batal mengedit goal") }
            Text(if (form.original == null) "Tambah goal" else "Edit goal", style = MaterialTheme.typography.titleLarge)
        }
        GoalImage(form.existingImagePath, form.selectedImage?.bytes, "Gambar tujuan", loadImage,
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (readingImage) "Membaca gambar…" else if (form.selectedImage != null || form.existingImagePath != null)
                    "Ganti gambar" else "Pilih gambar")
            }
            if (form.selectedImage != null || form.existingImagePath != null) {
                IconButton(onClick = onRemove, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Hapus gambar") }
            }
        }
        Text("Gambar opsional · JPG, PNG, WebP · Maks. 5 MB", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GoalTextField("Nama tujuan", form.name, GoalField.NAME, form, busy, onChange)
        GoalTextField("Target nominal", form.target, GoalField.TARGET, form, busy, onChange, money = true)
        GoalTextField("Jumlah terkumpul", form.saved, GoalField.SAVED, form, busy, onChange, money = true)
        Text("Masukkan nominal tanpa pemisah ribuan, contoh 1500000 atau 1500000,50.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GoalTextField("Deskripsi (opsional)", form.description, GoalField.DESCRIPTION, form, busy, onChange)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("Jumlah terkumpul dapat diubah secara manual. Perubahan ini tidak memengaruhi Transactions.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (busy) "  Memproses…" else "Simpan goal")
        }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Batal") }
    }
}

@Composable
private fun GoalTextField(
    label: String, value: String, field: GoalField, form: GoalFormState, busy: Boolean,
    onChange: (GoalField, String) -> Unit, money: Boolean = false
) {
    val error = form.errors[field]
    OutlinedTextField(value = value, onValueChange = { onChange(field, it) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), enabled = !busy, isError = error != null,
        singleLine = field != GoalField.DESCRIPTION, minLines = if (field == GoalField.DESCRIPTION) 3 else 1,
        prefix = if (money) ({ Text("Rp ") }) else null,
        supportingText = { if (error != null) Text(error) else if (field == GoalField.NAME) Text("${value.length}/100")
            else if (field == GoalField.DESCRIPTION) Text("${value.length}/2000") },
        keyboardOptions = KeyboardOptions(keyboardType = if (money) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(14.dp))
}

@Composable
private fun GoalProgress(goal: Goal) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(progress = { goal.progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
            gapSize = 0.dp, drawStopIndicator = {})
        Text("${goal.progressPercent.stripTrailingZeros().toPlainString().replace('.', ',')}%${if (goal.isComplete) " · Tercapai" else " terkumpul"}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AmountRow(label: String, amount: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private data class GoalBitmapState(val bitmap: Bitmap? = null, val loading: Boolean = false, val failed: Boolean = false)

@Composable
private fun GoalImage(
    path: String?, bytes: ByteArray?, description: String, loadImage: suspend (String) -> ByteArray, modifier: Modifier
) {
    var retry by remember(path, bytes) { mutableIntStateOf(0) }
    val image by produceState(GoalBitmapState(loading = path != null || bytes != null), path, bytes, retry) {
        value = GoalBitmapState(loading = path != null || bytes != null)
        if (path != null || bytes != null) {
            value = try {
                GoalBitmapState(bitmap = decodeGoalImage(bytes ?: loadImage(requireNotNull(path))))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                GoalBitmapState(failed = true)
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        when {
            image.bitmap != null -> Image(requireNotNull(image.bitmap).asImageBitmap(), description,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            image.loading -> CircularProgressIndicator(Modifier.size(28.dp))
            image.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Gambar gagal dimuat", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text("Coba lagi") }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Image, contentDescription = null, Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Belum ada gambar", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GoalError(message: String, onRetry: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Coba lagi") }
        }
    }
}

private fun goalDate(value: String): String? = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID")))
}.getOrNull()
