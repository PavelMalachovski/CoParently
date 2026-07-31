package com.coparently.app.presentation

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.coparently.app.data.notification.NotificationManager
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.presentation.navigation.NavGraph
import com.coparently.app.presentation.splash.SplashScreen
import com.coparently.app.presentation.sync.SyncViewModel
import com.coparently.app.presentation.theme.CoPlanlyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CompositionLocal for providing Google Sign-In callback throughout the app.
 */
val LocalGoogleSignInCallback = staticCompositionLocalOf<((android.content.Intent) -> Unit)?> {
    null
}

/**
 * Main Activity for CoPlanly app.
 * Entry point of the application.
 * Handles Google Sign-In result, Push Notifications, and Splash Screen (Android 12+).
 *
 * Extends [AppCompatActivity] (not ComponentActivity) so that per-app language
 * preferences set via AppCompatDelegate.setApplicationLocales are applied to this
 * activity's configuration on every recreation, including on Android < 13.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    private val _darkThemeState = MutableStateFlow<Boolean?>(null)
    private val darkThemeState: StateFlow<Boolean?> = _darkThemeState

    private val syncViewModel: SyncViewModel by viewModels()

    // Google Sign-In Activity Result launcher for sync
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
            lifecycleScope.launch {
                syncViewModel.handleSignInResult(task)
            }
        } else {
            val isCanceled = result.resultCode == RESULT_CANCELED
            val message = if (isCanceled) {
                getString(com.coparently.app.R.string.sync_google_sign_in_cancelled)
            } else {
                getString(com.coparently.app.R.string.sync_google_sign_in_failed)
            }
            Log.w("MainActivity", "Google sign-in aborted: resultCode=${result.resultCode}")
            syncViewModel.handleSignInCancellation(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before calling super.onCreate()
        // This ensures the splash screen is displayed on Android 12+
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display for modern Android UI
        // This makes the app draw behind the system bars
        enableEdgeToEdge()

        // Notification permission is requested contextually (Settings push toggle,
        // event reminder selection) instead of on every cold start — see
        // NotificationPermission.kt.

        // Initialize notifications
        try {
            notificationManager.initializeNotifications()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing notifications", e)
        }

        // Setup app shortcuts (Android 7.1+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                com.coparently.app.utils.AppShortcuts.setupShortcuts(this)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up app shortcuts", e)
        }

        // Load theme preference
        lifecycleScope.launch {
            try {
                preferencesRepository.getDarkThemeFlow().collect { isDark ->
                    _darkThemeState.value = isDark
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading theme preference", e)
                _darkThemeState.value = null // Use system default on error
            }
        }

        setContent {
            val darkTheme by darkThemeState.collectAsState()
            val systemDarkTheme = isSystemInDarkTheme()

            // Use saved preference or fall back to system default
            val useDarkTheme = darkTheme ?: systemDarkTheme

            // Provide Google Sign-In callback through CompositionLocal
            val googleSignInCallback: (android.content.Intent) -> Unit = remember(googleSignInLauncher) {
                {
                        intent ->
                    googleSignInLauncher.launch(intent)
                }
            }

            CoPlanlyTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Branded splash overlays the app on launch, then fades out to
                    // reveal it (auth state resolves underneath while it plays).
                    var showSplash by remember { mutableStateOf(true) }
                    val navController = rememberNavController()

                    Box(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalGoogleSignInCallback provides googleSignInCallback
                        ) {
                            NavGraph(
                                navController = navController,
                                syncViewModel = syncViewModel
                            )
                        }

                        AnimatedVisibility(
                            visible = showSplash,
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = fadeOut(animationSpec = tween(500))
                        ) {
                            SplashScreen(onFinished = { showSplash = false })
                        }
                    }
                }
            }
        }
    }
}
