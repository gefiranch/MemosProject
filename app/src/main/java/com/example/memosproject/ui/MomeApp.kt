package com.example.memosproject.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.memosproject.ui.goals.GoalsScreen
import com.example.memosproject.ui.goals.rememberGoalsViewModel

@Composable
fun MomeApp(viewModel: AuthViewModel) {
    val state by viewModel.uiState.collectAsState()
    when (state.screen) {
        AuthScreen.LOADING -> CenterContent("Mome", "Memeriksa sesi login…", loading = true)
        AuthScreen.SETUP -> CenterContent(
            "Supabase belum terhubung",
            "Tambahkan SUPABASE_URL dan SUPABASE_PUBLISHABLE_KEY pada local.properties, lalu Sync Gradle."
        )
        AuthScreen.LOGIN -> LoginScreen(
            busy = state.busy,
            message = state.message,
            onLogin = viewModel::login,
            onRegister = viewModel::openRegister
        )
        AuthScreen.REGISTER -> RegisterScreen(
            busy = state.busy,
            message = state.message,
            onRegister = viewModel::register,
            onBack = viewModel::openLogin
        )
        AuthScreen.HOME -> MainTabs(email = state.signedInEmail, busy = state.busy, onLogout = viewModel::logout)
    }
}

@Composable
private fun CenterContent(title: String, description: String, loading: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AuthLayout(title: String, subtitle: String, message: String?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Mome", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        content()
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoginScreen(
    busy: Boolean,
    message: String?,
    onLogin: (String, String) -> Unit,
    onRegister: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AuthLayout("Masuk ke akunmu", "Gunakan Gmail dan password yang sudah terdaftar.", message) {
        OutlinedTextField(
            email, { email = it }, label = { Text("Gmail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onLogin(email, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Memproses…" else "Login")
        }
        TextButton(onClick = onRegister, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Belum punya akun? Register")
        }
    }
}

@Composable
private fun RegisterScreen(
    busy: Boolean,
    message: String?,
    onRegister: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AuthLayout("Buat akun Mome", "Isi Gmail, username, dan password untuk mendaftar.", message) {
        OutlinedTextField(email, { email = it }, label = { Text("Gmail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true,
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Username") },
            supportingText = { Text("3–20 karakter: huruf, angka, underscore") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password (minimal 8 karakter)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onRegister(email, username, password) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Mendaftar…" else "Register") }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sudah punya akun? Login") }
    }
}

private data class MainTab(val title: String)
private val tabs = listOf(MainTab("Transactions"), MainTab("Goals"), MainTab("Budgets"), MainTab("Journal"), MainTab("Bills"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(email: String, busy: Boolean, onLogout: () -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var profileDialog by remember { mutableStateOf(false) }
    val goalsViewModel = rememberGoalsViewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mome · ${tabs[selectedTab].title}") },
                actions = { IconButton(onClick = { profileDialog = true }) {
                    Icon(Icons.Filled.Person, contentDescription = "Profil akun")
                } }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            when (index) {
                                0 -> Icon(Icons.Filled.List, contentDescription = null)
                                1 -> Icon(Icons.Filled.Star, contentDescription = null)
                                2 -> Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null)
                                3 -> Icon(Icons.Filled.Edit, contentDescription = null)
                                else -> Icon(Icons.Filled.DateRange, contentDescription = null)
                            }
                        },
                        label = { Text(tab.title, maxLines = 1) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedTab == 1) {
                GoalsScreen(goalsViewModel)
            } else {
                CenterContent(tabs[selectedTab].title, "Halaman fitur ini masih kosong dan siap dikembangkan.")
            }
        }
    }
    if (profileDialog) {
        AlertDialog(
            onDismissRequest = { profileDialog = false },
            title = { Text("Profil") },
            text = { Text("Gmail: ${email.ifBlank { "Akun aktif" }}\n\nPengaturan profil akan dikembangkan berikutnya.") },
            confirmButton = {
                Button(onClick = { profileDialog = false; onLogout() }, enabled = !busy) { Text("Logout") }
            },
            dismissButton = { TextButton(onClick = { profileDialog = false }) { Text("Tutup") } }
        )
    }
}
