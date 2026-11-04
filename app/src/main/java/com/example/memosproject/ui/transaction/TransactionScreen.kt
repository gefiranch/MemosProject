package com.example.memosproject.ui.transaction

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.memosproject.data.transaction.Category
import com.example.memosproject.data.transaction.SupabaseTransactionRepository
import com.example.memosproject.data.transaction.Transaction
import com.example.memosproject.data.transaction.formatTransactionDate
import com.example.memosproject.data.transaction.formatTransactionMoney
import com.example.memosproject.data.transaction.formatTransactionTimestamp
import io.github.jan.supabase.auth.auth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TransactionViewModelOwner : ViewModel() {
    private var userId: String? = null
    private var store = ViewModelStore()

    fun get(ownerId: String): TransactionViewModel {
        if (userId != ownerId) {
            clearSession()
            userId = ownerId
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TransactionViewModel(
                SupabaseTransactionRepository(SupabaseProvider.client, ownerId)
            ) as T
        }
        return ViewModelProvider(store, factory)[TransactionViewModel::class.java]
    }

    fun clearSession() {
        store.clear()
        store = ViewModelStore()
        userId = null
    }

    override fun onCleared() {
        store.clear()
    }
}

private tailrec fun Context.requireActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.requireActivity()
    else -> error("Transaction memerlukan Activity.")
}

@Composable
fun rememberTransactionViewModel(): TransactionViewModel {
    val context = LocalContext.current
    val activity = context.requireActivity()
    val owner = remember(activity) { ViewModelProvider(activity)[TransactionViewModelOwner::class.java] }
    val userId = requireNotNull(SupabaseProvider.client.auth.currentUserOrNull()).id
    val viewModel = remember(owner, userId) { owner.get(userId) }
    DisposableEffect(owner, activity) {
        onDispose { if (!activity.isChangingConfigurations) owner.clearSession() }
    }
    return viewModel
}

@Composable
fun TransactionScreen(viewModel: TransactionViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.refresh()
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it.text)
            viewModel.consumeNotice()
        }
    }

    BackHandler(enabled = state.page != TransactionPage.LIST) {
        viewModel.back()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.page == TransactionPage.LIST && !state.loading && !state.busy) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openAddForm,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Tambah Transaksi") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.busy || (state.loading && state.transactions.isNotEmpty())) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.page == TransactionPage.LIST) {
                            TextButton(onClick = viewModel::refresh) { Text("Coba lagi") }
                        }
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (state.page) {
                    TransactionPage.LIST -> TransactionListContent(
                        state = state,
                        onFilterChange = viewModel::setFilter,
                        onOpenDetail = viewModel::openDetail,
                        onRefresh = viewModel::refresh,
                        onAdd = viewModel::openAddForm
                    )
                    TransactionPage.DETAIL -> state.selectedTransaction?.let { tx ->
                        TransactionDetailContent(
                            transaction = tx,
                            busy = state.busy,
                            onBack = viewModel::back,
                            onEdit = viewModel::openEditForm,
                            onDelete = { viewModel.confirmDelete(true) }
                        )
                    }
                    TransactionPage.FORM -> state.form?.let { form ->
                        TransactionFormContent(
                            form = form,
                            categories = state.categories,
                            categoryError = state.categoryError,
                            busy = state.busy,
                            onBack = viewModel::back,
                            onTypeChange = viewModel::onFormTypeChange,
                            onCategoryChange = viewModel::onFormCategoryChange,
                            onAmountChange = viewModel::onFormAmountChange,
                            onDateChange = viewModel::onFormDateChange,
                            onDescriptionChange = viewModel::onFormDescriptionChange,
                            onSave = viewModel::saveForm
                        )
                    }
                }
            }
        }
    }

    if (state.confirmDelete) {
        val selectedTx = state.selectedTransaction
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(false) },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Hapus Transaksi?") },
            text = {
                Text("Transaksi sebesar ${selectedTx?.let { formatTransactionMoney(it.amount) } ?: ""} (${selectedTx?.categoryName.orEmpty()}) akan dihapus secara permanen.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::deleteTransaction) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmDelete(false) }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun TransactionListContent(
    state: TransactionUiState,
    onFilterChange: (TransactionFilter) -> Unit,
    onOpenDetail: (Transaction) -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit
) {
    if (state.loading && state.transactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Memuat data transaksi…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Kelola Keuanganmu",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Pantau pemasukan dan pengeluaran harian",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                }
            }
        }

        // Summary Card: Total Pemasukan & Pengeluaran
        item {
            val totalIncome = remember(state.transactions) {
                state.transactions.filter { it.isPemasukan }.fold(java.math.BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
            }
            val totalExpense = remember(state.transactions) {
                state.transactions.filter { !it.isPemasukan }.fold(java.math.BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Pemasukan",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatTransactionMoney(totalIncome),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    Spacer(Modifier.width(16.dp))

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Pengeluaran",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatTransactionMoney(totalExpense),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                FilterChip(
                    selected = state.filter == TransactionFilter.SEMUA,
                    onClick = { onFilterChange(TransactionFilter.SEMUA) },
                    label = { Text("Semua") }
                )
                FilterChip(
                    selected = state.filter == TransactionFilter.PEMASUKAN,
                    onClick = { onFilterChange(TransactionFilter.PEMASUKAN) },
                    label = { Text("Pemasukan") }
                )
                FilterChip(
                    selected = state.filter == TransactionFilter.PENGELUARAN,
                    onClick = { onFilterChange(TransactionFilter.PENGELUARAN) },
                    label = { Text("Pengeluaran") }
                )
            }
        }

        val itemsToShow = state.filteredTransactions
        if (itemsToShow.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (state.filter == TransactionFilter.SEMUA) "Belum ada transaksi"
                        else "Tidak ada transaksi ${if (state.filter == TransactionFilter.PEMASUKAN) "pemasukan" else "pengeluaran"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Catat pemasukan atau pengeluaranmu sekarang.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(onClick = onAdd) {
                        Text("Catat Transaksi")
                    }
                }
            }
        } else {
            items(itemsToShow, key = { it.id }) { tx ->
                TransactionItemCard(transaction = tx, onClick = { onOpenDetail(tx) })
            }
        }
    }
}

@Composable
private fun TransactionItemCard(
    transaction: Transaction,
    onClick: () -> Unit
) {
    val isIncome = transaction.isPemasukan
    val iconColor = if (isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val iconBgColor = if (isIncome) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    val amountPrefix = if (isIncome) "+ " else "- "

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(iconBgColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isIncome) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = transaction.categoryName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTransactionDate(transaction.transactionDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!transaction.description.isNullOrBlank()) {
                    Text(
                        text = transaction.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$amountPrefix${formatTransactionMoney(transaction.amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = iconColor
            )
        }
    }
}

@Composable
private fun TransactionDetailContent(
    transaction: Transaction,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isIncome = transaction.isPemasukan
    val accentColor = if (isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Text("Detail Transaksi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit, enabled = !busy) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Transaksi")
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus Transaksi", tint = MaterialTheme.colorScheme.error)
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (isIncome) "PEMASUKAN" else "PENGELUARAN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    formatTransactionMoney(transaction.amount),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    transaction.categoryName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetailRow("Jenis Transaksi", if (isIncome) "Pemasukan" else "Pengeluaran")
                HorizontalDivider()
                DetailRow("Kategori", transaction.categoryName)
                HorizontalDivider()
                DetailRow("Tanggal Transaksi", formatTransactionDate(transaction.transactionDate))
                HorizontalDivider()
                DetailRow(
                    "Deskripsi",
                    transaction.description?.takeIf { it.isNotBlank() } ?: "Tidak ada catatan."
                )
                formatTransactionTimestamp(transaction.createdAt)?.let { createdAtText ->
                    HorizontalDivider()
                    DetailRow("Dicatat Pada", createdAtText)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("Hapus", color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onEdit,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionFormContent(
    form: TransactionFormState,
    categories: List<Category>,
    categoryError: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onTypeChange: (String) -> Unit,
    onCategoryChange: (Category) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit
) {
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Kategori difilter sesuai jenis transaksi yang dipilih (expense atau income)
    val availableCategories = remember(categories, form.type) {
        val targetType = if (form.type.equals("income", ignoreCase = true) || form.type.equals("pemasukan", ignoreCase = true)) {
            "income"
        } else {
            "expense"
        }
        categories.filter {
            val catType = if (it.type.equals("income", ignoreCase = true) || it.type.equals("pemasukan", ignoreCase = true)) {
                "income"
            } else {
                "expense"
            }
            catType == targetType
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Batal")
            }
            Text(
                if (form.id == null) "Tambah Transaksi" else "Edit Transaksi",
                style = MaterialTheme.typography.titleLarge
            )
        }

        // Pilihan Jenis Transaksi: "expense" (Pengeluaran) dan "income" (Pemasukan)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val types = listOf("expense" to "Pengeluaran", "income" to "Pemasukan")
            types.forEachIndexed { index, (typeKey, typeLabel) ->
                SegmentedButton(
                    selected = form.type == typeKey,
                    onClick = { onTypeChange(typeKey) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                    enabled = !busy
                ) {
                    Text(typeLabel)
                }
            }
        }

        // Dropdown Kategori
        ExposedDropdownMenuBox(
            expanded = categoryDropdownExpanded,
            onExpandedChange = { if (!busy) categoryDropdownExpanded = it }
        ) {
            val selectedCategoryText = availableCategories.find { it.id == form.categoryId }?.name
                ?: form.categoryName.ifBlank { "Pilih Kategori" }

            val supportingMessage = when {
                form.errorCategory != null -> form.errorCategory
                categoryError != null -> "Gagal memuat kategori: $categoryError"
                availableCategories.isEmpty() -> "Belum ada kategori untuk jenis ${if (form.type == "income") "Pemasukan" else "Pengeluaran"}."
                else -> null
            }

            OutlinedTextField(
                value = if (form.categoryId.isBlank()) "" else selectedCategoryText,
                onValueChange = {},
                readOnly = true,
                label = { Text("Kategori") },
                placeholder = { Text("Pilih kategori") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                leadingIcon = { Icon(Icons.Default.Sell, contentDescription = null) },
                isError = form.errorCategory != null || categoryError != null,
                supportingText = supportingMessage?.let { msg -> { Text(msg) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(14.dp),
                enabled = !busy
            )

            ExposedDropdownMenu(
                expanded = categoryDropdownExpanded,
                onDismissRequest = { categoryDropdownExpanded = false }
            ) {
                if (availableCategories.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (categoryError != null) "Error: $categoryError"
                                else "Tidak ada kategori ${if (form.type == "income") "Pemasukan" else "Pengeluaran"}"
                            )
                        },
                        onClick = { categoryDropdownExpanded = false },
                        enabled = false
                    )
                } else {
                    availableCategories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.name) },
                            onClick = {
                                onCategoryChange(category)
                                categoryDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Input Nominal
        OutlinedTextField(
            value = form.amount,
            onValueChange = onAmountChange,
            label = { Text("Nominal") },
            prefix = { Text("Rp ") },
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = form.errorAmount != null,
            supportingText = {
                if (form.errorAmount != null) {
                    Text(form.errorAmount)
                } else {
                    Text("Contoh: 50000 atau 75000.50 (tanpa titik ribuan)")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        // Input Tanggal (Date Picker)
        OutlinedTextField(
            value = formatTransactionDate(form.transactionDate),
            onValueChange = {},
            readOnly = true,
            label = { Text("Tanggal Transaksi") },
            trailingIcon = {
                IconButton(onClick = { if (!busy) showDatePicker = true }) {
                    Icon(Icons.Default.CalendarToday, contentDescription = "Pilih Tanggal")
                }
            },
            isError = form.errorDate != null,
            supportingText = form.errorDate?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { showDatePicker = true },
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        // Input Deskripsi (Opsional)
        OutlinedTextField(
            value = form.description,
            onValueChange = onDescriptionChange,
            label = { Text("Deskripsi (Opsional)") },
            placeholder = { Text("Tambahkan keterangan transaksi...") },
            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
            minLines = 3,
            maxLines = 5,
            supportingText = { Text("${form.description.length}/1000") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onSave,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Menyimpan…")
            } else {
                Text(if (form.id == null) "Simpan Transaksi" else "Perbarui Transaksi")
            }
        }

        TextButton(
            onClick = onBack,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Batal")
        }
    }

    if (showDatePicker) {
        val initialEpochMillis = remember(form.transactionDate) {
            runCatching {
                LocalDate.parse(form.transactionDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrDefault(Instant.now().toEpochMilli())
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialEpochMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        onDateChange(selectedDate.toString())
                    }
                    showDatePicker = false
                }) {
                    Text("Pilih")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Batal")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
