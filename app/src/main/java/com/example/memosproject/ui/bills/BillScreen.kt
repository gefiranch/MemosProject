package com.example.memosproject.ui.bills

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.memosproject.data.SupabaseProvider
import com.example.memosproject.data.bills.Bill
import com.example.memosproject.data.bills.SupabaseBillRepository
import com.example.memosproject.data.bills.formatBillDate
import com.example.memosproject.data.bills.formatBillMoney
import io.github.jan.supabase.auth.auth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class BillViewModelOwner : ViewModel() {
    private var userId: String? = null
    private var store = ViewModelStore()

    fun get(ownerId: String): BillViewModel {
        if (userId != ownerId) {
            clearSession()
            userId = ownerId
        }

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BillViewModel(
                    SupabaseBillRepository(
                        SupabaseProvider.client,
                        ownerId
                    )
                ) as T
        }

        return ViewModelProvider(store, factory)[BillViewModel::class.java]
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

private tailrec fun Context.requireBillActivity(): ComponentActivity =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.requireBillActivity()
        else -> error("Bills memerlukan Activity.")
    }

@Composable
fun rememberBillViewModel(): BillViewModel {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context.requireBillActivity()

    val owner = remember(activity) {
        ViewModelProvider(activity)[BillViewModelOwner::class.java]
    }

    val userId = requireNotNull(
        SupabaseProvider.client.auth.currentUserOrNull()
    ).id

    val viewModel = remember(owner, userId) {
        owner.get(userId)
    }

    DisposableEffect(owner, activity) {
        onDispose {
            if (!activity.isChangingConfigurations) {
                owner.clearSession()
            }
        }
    }

    return viewModel
}

@Composable
fun BillScreen(viewModel: BillViewModel) {
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

    BackHandler(enabled = state.page != BillPage.LIST) {
        viewModel.back()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (
                state.page == BillPage.LIST &&
                !state.loading &&
                !state.busy
            ) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openAddForm,
                    icon = {
                        Icon(Icons.Default.Add, contentDescription = null)
                    },
                    text = { Text("Tambah Tagihan") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.busy || (state.loading && state.bills.isNotEmpty())) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        if (state.page == BillPage.LIST) {
                            TextButton(onClick = viewModel::refresh) {
                                Text("Coba lagi")
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (state.page) {
                    BillPage.LIST -> BillListContent(
                        state = state,
                        onOpenDetail = viewModel::openDetail,
                        onRefresh = viewModel::refresh,
                        onAdd = viewModel::openAddForm
                    )

                    BillPage.DETAIL -> state.selectedBill?.let { bill ->
                        BillDetailContent(
                            bill = bill,
                            busy = state.busy,
                            onBack = viewModel::back,
                            onEdit = viewModel::openEditForm,
                            onDelete = {
                                viewModel.confirmDelete(true)
                            },
                            onTogglePaid = viewModel::togglePaid
                        )
                    }

                    BillPage.FORM -> state.form?.let { form ->
                        BillFormContent(
                            form = form,
                            busy = state.busy,
                            onBack = viewModel::back,
                            onTitleChange = viewModel::onTitleChange,
                            onAmountChange = viewModel::onAmountChange,
                            onDateChange = viewModel::onDueDateChange,
                            onPaidChange = viewModel::onPaidChange,
                            onNotesChange = viewModel::onNotesChange,
                            onSave = viewModel::saveForm
                        )
                    }
                }
            }
        }
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = {
                viewModel.confirmDelete(false)
            },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Hapus Tagihan?") },
            text = {
                Text(
                    "Tagihan ${state.selectedBill?.title.orEmpty()} " +
                            "akan dihapus secara permanen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::deleteBill
                ) {
                    Text(
                        "Hapus",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmDelete(false)
                    }
                ) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun BillListContent(
    state: BillUiState,
    onOpenDetail: (Bill) -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit
) {
    if (state.loading && state.bills.isEmpty()) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Memuat data tagihan...")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Kelola Tagihanmu",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Pantau tagihan dan tanggal jatuh temponya",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !state.loading
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Muat ulang"
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    title = "Belum Dibayar",
                    count = state.unpaidBills.size,
                    icon = Icons.Default.Schedule,
                    modifier = Modifier.weight(1f)
                )

                SummaryCard(
                    title = "Sudah Dibayar",
                    count = state.paidBills.size,
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (state.bills.isEmpty()) {
            item {
                EmptyBillsContent(onAdd)
            }
        } else {
            if (state.unpaidBills.isNotEmpty()) {
                item {
                    Text(
                        "Belum Dibayar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(
                    state.unpaidBills,
                    key = { it.id }
                ) { bill ->
                    BillItemCard(
                        bill = bill,
                        onClick = { onOpenDetail(bill) }
                    )
                }
            }

            if (state.paidBills.isNotEmpty()) {
                item {
                    Text(
                        "Sudah Dibayar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(
                    state.paidBills,
                    key = { it.id }
                ) { bill ->
                    BillItemCard(
                        bill = bill,
                        onClick = { onOpenDetail(bill) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyBillsContent(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.ReceiptLong,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            "Belum ada tagihan",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            "Tambahkan tagihan agar kamu tidak lupa membayarnya.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(onClick = onAdd) {
            Text("Tambah Tagihan")
        }
    }
}

@Composable
private fun BillItemCard(
    bill: Bill,
    onClick: () -> Unit
) {
    val textDecoration = if (bill.isPaid) {
        TextDecoration.LineThrough
    } else {
        TextDecoration.None
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (bill.isPaid) {
                        Icons.Default.CheckCircle
                    } else if (bill.isOverdue) {
                        Icons.Default.Warning
                    } else {
                        Icons.Default.ReceiptLong
                    },
                    contentDescription = null,
                    tint = when {
                        bill.isPaid -> MaterialTheme.colorScheme.primary
                        bill.isOverdue -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    }
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    bill.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = textDecoration,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    "Jatuh tempo: ${formatBillDate(bill.dueDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = textDecoration
                )

                Text(
                    when {
                        bill.isPaid -> "Sudah dibayar"
                        bill.isOverdue -> "Terlambat dibayar"
                        else -> "Belum dibayar"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        bill.isPaid -> MaterialTheme.colorScheme.primary
                        bill.isOverdue -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                formatBillMoney(bill.amount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textDecoration = textDecoration
            )
        }
    }
}

@Composable
private fun BillDetailContent(
    bill: Bill,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePaid: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                enabled = !busy
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali"
                )
            }

            Text(
                "Detail Tagihan",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onEdit,
                enabled = !busy
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit Tagihan"
                )
            }

            IconButton(
                onClick = onDelete,
                enabled = !busy
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Hapus Tagihan",
                    tint = MaterialTheme.colorScheme.error
                )
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
                    if (bill.isPaid) "SUDAH DIBAYAR" else "BELUM DIBAYAR",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (bill.isPaid) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )

                Text(
                    formatBillMoney(bill.amount),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    bill.title,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BillDetailRow(
                    "Nama Tagihan",
                    bill.title
                )

                Divider()

                BillDetailRow(
                    "Nominal",
                    formatBillMoney(bill.amount)
                )

                Divider()

                BillDetailRow(
                    "Tanggal Jatuh Tempo",
                    formatBillDate(bill.dueDate)
                )

                Divider()

                BillDetailRow(
                    "Status Pembayaran",
                    if (bill.isPaid) "Sudah dibayar" else "Belum dibayar"
                )

                if (!bill.isPaid && bill.isOverdue) {
                    Divider()
                    BillDetailRow(
                        "Keterangan",
                        "Tagihan sudah melewati tanggal jatuh tempo."
                    )
                }

                Divider()

                BillDetailRow(
                    "Catatan",
                    bill.notes?.takeIf { it.isNotBlank() }
                        ?: "Tidak ada catatan."
                )
            }
        }

        Button(
            onClick = onTogglePaid,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                if (bill.isPaid) {
                    "Tandai Belum Dibayar"
                } else {
                    "Tandai Sudah Dibayar"
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null
                )
                Spacer(Modifier.width(6.dp))
                Text("Hapus")
            }

            Button(
                onClick = onEdit,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null
                )
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
    }
}

@Composable
private fun BillDetailRow(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun BillFormContent(
    form: BillFormState,
    busy: Boolean,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onPaidChange: (Boolean) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit
) {
    var showDatePicker by remember {
        mutableStateOf(false)
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
            IconButton(
                onClick = onBack,
                enabled = !busy
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali"
                )
            }

            Text(
                if (form.id == null) "Tambah Tagihan" else "Edit Tagihan",
                style = MaterialTheme.typography.titleLarge
            )
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            label = { Text("Nama Tagihan") },
            placeholder = { Text("Contoh: Tagihan Listrik") },
            singleLine = true,
            isError = form.errorTitle != null,
            supportingText = {
                form.errorTitle?.let { Text(it) }
                    ?: Text("${form.title.length}/100")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        OutlinedTextField(
            value = form.amount,
            onValueChange = onAmountChange,
            label = { Text("Nominal Tagihan") },
            prefix = { Text("Rp ") },
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            singleLine = true,
            isError = form.errorAmount != null,
            supportingText = {
                form.errorAmount?.let { Text(it) }
                    ?: Text("Contoh: 50000 atau 75000.50")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        OutlinedTextField(
            value = runCatching {
                formatBillDate(form.dueDate)
            }.getOrDefault(form.dueDate),
            onValueChange = {},
            readOnly = true,
            label = { Text("Tanggal Jatuh Tempo") },
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (!busy) showDatePicker = true
                    }
                ) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = "Pilih Tanggal"
                    )
                }
            },
            isError = form.errorDate != null,
            supportingText = {
                form.errorDate?.let { Text(it) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) {
                    showDatePicker = true
                },
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Status Pembayaran",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (form.isPaid) "Sudah dibayar" else "Belum dibayar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilterChip(
                    selected = form.isPaid,
                    onClick = {
                        if (!busy) onPaidChange(!form.isPaid)
                    },
                    label = {
                        Text(if (form.isPaid) "Lunas" else "Belum Lunas")
                    },
                    enabled = !busy
                )
            }
        }

        OutlinedTextField(
            value = form.notes,
            onValueChange = onNotesChange,
            label = { Text("Catatan (Opsional)") },
            placeholder = { Text("Tambahkan keterangan tagihan...") },
            minLines = 3,
            maxLines = 5,
            supportingText = {
                Text("${form.notes.length}/1000")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            enabled = !busy
        )

        Button(
            onClick = onSave,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Menyimpan...")
            } else {
                Text(
                    if (form.id == null) {
                        "Simpan Tagihan"
                    } else {
                        "Perbarui Tagihan"
                    }
                )
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
        val initialMillis = remember(form.dueDate) {
            runCatching {
                LocalDate.parse(form.dueDate)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }.getOrDefault(Instant.now().toEpochMilli())
        }

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis
        )

        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.of("UTC"))
                                .toLocalDate()

                            onDateChange(selectedDate.toString())
                        }

                        showDatePicker = false
                    }
                ) {
                    Text("Pilih")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                    }
                ) {
                    Text("Batal")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
