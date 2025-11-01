package com.coparently.app.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.coparently.app.presentation.navigation.NavGraph
import com.coparently.app.presentation.theme.CoParentlyTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main Activity for CoParently app.
 * Entry point of the application.
 * Handles Google Sign-In result.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Use Activity Result API instead of deprecated onActivityResult
    private val signInResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Result handling moved to SettingsScreen via Activity Result API
        // This launcher is kept for backward compatibility if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoParentlyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }
}

