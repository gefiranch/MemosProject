package com.example.memosproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.memosproject.ui.AuthViewModel
import com.example.memosproject.ui.MomeApp
import com.example.memosproject.ui.theme.MemosProjectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val authViewModel = ViewModelProvider(this, AuthViewModel.Factory)[AuthViewModel::class.java]
        setContent {
            MemosProjectTheme {
                MomeApp(authViewModel)
            }
        }
    }
}
