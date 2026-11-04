package com.example.memosproject.ui.budget

import androidx.activity.compose.BackHandler
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.memosproject.data.SupabaseProvider
import com.example.memosproject.data.budget.Budget
import com.example.memosproject.data.budget.BudgetCategory
import com.example.memosproject.data.budget.SupabaseBudgetRepository
import io.github.jan.supabase.auth.auth
import java.math.BigDecimal

class BudgetViewModelOwner : ViewModel() {
    private var userId: String? = null
    private var store = ViewModelStore()

    fun get(ownerId: String): BudgetViewModel {
        if (userId != ownerId) {
            clearSession()
            userId = ownerId
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BudgetViewModel(
                SupabaseBudgetRepository(SupabaseProvider.client, ownerId)
            ) as T
        }
        return ViewModelProvider(store, factory)[BudgetViewModel::class.java]
    }

    fun clearSession() {
        store.clear()
        store = ViewModelStore()
        userId = null
    }

    override fun onCleared() { store.clear() }
}

private tailrec fun Context.requireBudgetActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.requireBudgetActivity()
    else -> error("Budget memerlukan Activity.")
}

@Composable
fun rememberBudgetViewModel(): BudgetViewModel {
    val context = LocalContext.current
    val activity = context.requireBudgetActivity()
    val owner = remember(activity) { ViewModelProvider(activity)[BudgetViewModelOwner::class.java] }
    val userId = requireNotNull(SupabaseProvider.client.auth.currentUserOrNull()).id
    val viewModel = remember(owner, userId) { owner.get(userId) }
    DisposableEffect(owner, activity) {
        onDispose { if (!activity.isChangingConfigurations) owner.clearSession() }
    }
    return viewModel
}

@Composable
fun BudgetScreen(viewModel: BudgetViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != BudgetPage.LIST) { viewModel.back() }

    Scaffold(
        floatingActionButton = {
            if (state.page == BudgetPage.LIST) ExtendedFloatingActionButton(
                onClick = viewModel::add,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Tambah") }
            )
        }
    ) { padding ->
        when (state.page) {
            BudgetPage.LIST -> BudgetList(state, viewModel, Modifier.padding(padding))
            BudgetPage.DETAIL -> state.selected?.let { BudgetDetail(it, viewModel, Modifier.padding(padding)) }
            BudgetPage.FORM -> BudgetForm(state, viewModel, Modifier.padding(padding))
        }
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(false) },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Hapus anggaran?") },
            text = { Text("Anggaran ${state.selected?.category.orEmpty()} akan dihapus dari data sementara.") },
            confirmButton = { TextButton(onClick = viewModel::delete) { Text("Hapus", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { viewModel.confirmDelete(false) }) { Text("Batal") } }
        )
    }
}

@Composable
private fun BudgetList(state: BudgetUiState, vm: BudgetViewModel, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Anggaran Bulanan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(formatBudgetPeriod(state.period), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, contentDescription = "Muat ulang anggaran") }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        item { SummaryCard(state) }
        item { Text("Anggaran per kategori", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        val budgets = state.budgets.filter { it.period == state.period }
        if (budgets.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Belum ada anggaran untuk periode ini", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = vm::add) { Text("Tambah anggaran") }
                    }
                }
            }
        }
        items(budgets, key = { it.id }) { budget -> BudgetRow(budget, onClick = { vm.open(budget) }) }
    }
}

@Composable
private fun SummaryCard(state: BudgetUiState) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Total anggaran", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(formatBudgetMoney(state.totalLimit), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            LinearProgressIndicator(
                progress = { if (state.totalLimit.signum() <= 0) 0f else state.totalSpent.divide(state.totalLimit, 4, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Terpakai", style = MaterialTheme.typography.labelMedium); Text(formatBudgetMoney(state.totalSpent), fontWeight = FontWeight.SemiBold) }
                Column(horizontalAlignment = Alignment.End) { Text("Sisa anggaran", style = MaterialTheme.typography.labelMedium); Text(formatBudgetMoney(state.totalRemaining), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun BudgetRow(budget: Budget, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(budget.category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(formatBudgetMoney(budget.limit), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { budget.usage }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Terpakai ${formatBudgetMoney(budget.spent)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(budget.spent.divide(budget.limit, 2, java.math.RoundingMode.HALF_UP) * BigDecimal(100)).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BudgetDetail(budget: Budget, vm: BudgetViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
            Text("Detail anggaran", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::edit) { Icon(Icons.Default.Edit, contentDescription = "Edit anggaran") }
            IconButton(onClick = { vm.confirmDelete(true) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus anggaran", tint = MaterialTheme.colorScheme.error) }
        }
        Text(budget.category, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(formatBudgetPeriod(budget.period), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailValue("Batas anggaran", formatBudgetMoney(budget.limit))
                DetailValue("Pengeluaran", formatBudgetMoney(budget.spent))
                DetailValue("Sisa anggaran", formatBudgetMoney(budget.remaining))
                LinearProgressIndicator(progress = { budget.usage }, modifier = Modifier.fillMaxWidth())
                Text("${budget.spent.divide(budget.limit, 2, java.math.RoundingMode.HALF_UP).multiply(BigDecimal(100)).toInt()}% digunakan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("Transaksi kategori ini", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (budget.transactions.isEmpty()) Text("Belum ada transaksi dummy untuk kategori ini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(budget.transactions, key = { it.id }) { transaction ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(transaction.title, style = MaterialTheme.typography.titleSmall); Text(transaction.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(formatBudgetMoney(transaction.amount), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetForm(state: BudgetUiState, vm: BudgetViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Batal dan kembali") }
            Text(if (state.formId == null) "Tambah anggaran" else "Edit anggaran", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = state.category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Kategori") },
                placeholder = { Text("Pilih kategori") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.categories.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { vm.category(category); expanded = false })
                }
            }
        }
        OutlinedTextField(
            value = state.amount,
            onValueChange = vm::amount,
            label = { Text("Nominal anggaran") },
            prefix = { Text("Rp") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Periode anggaran", style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { vm.movePeriod(-1) }) { Icon(Icons.Default.ExpandLess, contentDescription = "Periode sebelumnya") }
                Text(formatBudgetPeriod(state.period), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { vm.movePeriod(1) }) { Icon(Icons.Default.ExpandMore, contentDescription = "Periode berikutnya") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = vm::back, modifier = Modifier.weight(1f)) { Text("Batal") }
            Button(onClick = vm::save, modifier = Modifier.weight(1f)) { Text("Simpan") }
        }
    }
}
