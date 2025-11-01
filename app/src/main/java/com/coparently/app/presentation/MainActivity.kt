package com.coparently.app.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.coparently.app.presentation.navigation.NavGraph
import com.coparently.app.presentation.theme.CoParentlyTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main Activity for CoParently app.
 * Entry point of the application.
 * Handles Google Sign-In result.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val RC_SIGN_IN = 9001
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Result returned from launching the Intent from GoogleSignInClient.getSignInIntent(...)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                // Google Sign-In was successful, authenticate with the account
                val account = task.getResult(ApiException::class.java)
                // Handle sign-in result - this will be handled by SettingsScreen
            } catch (e: ApiException) {
                // Google Sign-In failed, handle error
                // Error will be handled by SettingsScreen
            }
        }
    }
}

