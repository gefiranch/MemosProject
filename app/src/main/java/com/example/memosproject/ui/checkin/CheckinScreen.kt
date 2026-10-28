package com.example.memosproject.ui.checkin

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.memosproject.data.SupabaseProvider
import com.example.memosproject.data.checkin.CheckinRepository
import com.example.memosproject.data.checkin.calculateStreaks
import io.github.jan.supabase.auth.auth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class CheckinOwner : ViewModel() {
    private var id: String? = null
    private var store = ViewModelStore()
    fun get(userId: String): CheckinViewModel {
        if (id != userId) { clearSession(); id = userId }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CheckinViewModel(CheckinRepository(SupabaseProvider.client, userId), userId) as T
        }
        return ViewModelProvider(store, factory)[CheckinViewModel::class.java]
    }
    fun clearSession() { store.clear(); store = ViewModelStore(); id = null }
    override fun onCleared() { store.clear() }
}
private tailrec fun Context.activity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Journal memerlukan Activity")
}
@Composable
fun rememberCheckinViewModel(): CheckinViewModel {
    val activity = LocalContext.current.activity()
    val owner = remember(activity) { ViewModelProvider(activity)[CheckinOwner::class.java] }
    val userId = requireNotNull(SupabaseProvider.client.auth.currentUserOrNull()).id
    val vm = remember(owner, userId) { owner.get(userId) }
    DisposableEffect(owner, activity) { onDispose { if (!activity.isChangingConfigurations) owner.clearSession() } }
    return vm
}
private val statuses = listOf("Sesuai Rencana", "Hampir Melebihi Anggaran", "Melebihi Anggaran")
private fun displayDate(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("id-ID")))

@Composable
fun CheckinScreen(vm: CheckinViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != CheckinPage.LIST) { vm.back() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        when (state.page) {
            CheckinPage.LIST -> {
                val streaks = calculateStreaks(state.items.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }, LocalDate.now())
                Text("Daily Check-in", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) { Text("Streak saat ini"); Text("${streaks.current} hari", style = MaterialTheme.typography.titleLarge) } }
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) { Text("Streak terpanjang"); Text("${streaks.longest} hari", style = MaterialTheme.typography.titleLarge) } }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::add, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Tambah check-in hari ini") }
                TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("Muat ulang") }
                Text("Riwayat", style = MaterialTheme.typography.titleMedium)
                if (state.items.isEmpty() && !state.loading) Text("Belum ada check-in. Mulai refleksi keuanganmu hari ini.", modifier = Modifier.padding(vertical = 16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(state.items, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth().clickable(enabled = !state.loading) { vm.open(item) }) {
                            Column(Modifier.padding(16.dp)) { Text(displayDate(item.date), style = MaterialTheme.typography.titleMedium); Text(item.status); Text(item.reflection, maxLines = 2, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            CheckinPage.DETAIL -> state.selected?.let { item ->
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = vm::back, enabled = !state.loading) { Text("← Riwayat") }
                    Text("Detail check-in", style = MaterialTheme.typography.headlineSmall)
                    Text(displayDate(item.date), style = MaterialTheme.typography.titleMedium)
                    Text("Status: ${item.status}")
                    Text("Refleksi", style = MaterialTheme.typography.titleSmall); Text(item.reflection)
                    Text("Rencana besok", style = MaterialTheme.typography.titleSmall); Text(item.plan)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::edit, enabled = !state.loading) { Text("Edit") }
                        OutlinedButton(onClick = { vm.confirmDelete(true) }, enabled = !state.loading) { Text("Hapus") }
                    }
                }
            }
            CheckinPage.FORM -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::back, enabled = !state.loading) { Text("← Kembali") }
                Text(if (state.selected == null) "Check-in hari ini" else "Edit check-in", style = MaterialTheme.typography.headlineSmall)
                Text(displayDate(state.selected?.date ?: LocalDate.now().toString()))
                Text("Bagaimana pengeluaranmu hari ini?", style = MaterialTheme.typography.titleMedium)
                statuses.forEach { status ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.loading) { vm.status(status) }, horizontalArrangement = Arrangement.Start) {
                        RadioButton(selected = state.status == status, onClick = { vm.status(status) }, enabled = !state.loading)
                        Text(status, modifier = Modifier.padding(top = 14.dp))
                    }
                }
                OutlinedTextField(state.reflection, vm::reflection, label = { Text("Refleksi hari ini") }, modifier = Modifier.fillMaxWidth(), enabled = !state.loading, minLines = 3)
                OutlinedTextField(state.plan, vm::plan, label = { Text("Rencana untuk besok") }, modifier = Modifier.fillMaxWidth(), enabled = !state.loading, minLines = 3)
                Button(onClick = vm::save, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) { Text("Simpan") }
            }
        }
    }
    if (state.confirmDelete) AlertDialog(onDismissRequest = { vm.confirmDelete(false) }, title = { Text("Hapus check-in?") }, text = { Text("Streak akan dihitung ulang setelah catatan dihapus.") }, confirmButton = { TextButton(onClick = vm::delete, enabled = !state.loading) { Text("Hapus") } }, dismissButton = { TextButton(onClick = { vm.confirmDelete(false) }) { Text("Batal") } })
}
