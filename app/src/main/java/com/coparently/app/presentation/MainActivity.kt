package com.coparently.app.presentation

import android.content.Intent
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
import com.coparently.app.domain.chat.ChatUri
import com.coparently.app.domain.guests.GuestInviteUri
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.presentation.navigation.NavGraph
import com.coparently.app.presentation.navigation.PendingChatLink
import com.coparently.app.presentation.navigation.PendingChatOpen
import com.coparently.app.presentation.navigation.PendingInviteCodes
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

    /**
     * A pairing code carried by a `coplanly://pair` deep link, awaiting hand-off
     * to the pairing screen. [NavGraph] consumes it (setting it back to null)
     * once it has navigated there — see [readPairingCode] for why redeeming it
     * is never automatic.
     */
    private val _pendingPairingCode = MutableStateFlow<String?>(null)
    private val pendingPairingCode: StateFlow<String?> = _pendingPairingCode

    /**
     * A `coplanly://chat` deep link (opened by a chat-message push notification), awaiting
     * hand-off to the Chat tab or a specific thread — null while none is pending. [NavGraph]
     * consumes it (setting it back to null) once it has navigated there — same hand-off shape
     * as [pendingPairingCode], bundled into a single [PendingChatOpen] so [NavGraph]'s own
     * parameter count does not grow by two for every deep link it gains (see [NavGraph]'s
     * doc).
     */
    /**
     * A guest code carried by a `coplanly://guest` deep link, awaiting hand-off to the
     * guest-accept screen. Separate from [pendingPairingCode], and that is the point: the two
     * codes are indistinguishable six-character strings, so the host the link arrived on is
     * the only thing that says which callable may redeem it. Redeeming is never automatic
     * here either — the guest confirms on the screen, for the same reason
     * [readPairingCode] gives.
     */
    private val _pendingGuestCode = MutableStateFlow<String?>(null)
    private val pendingGuestCode: StateFlow<String?> = _pendingGuestCode

    /**
     * The two invitation codes, bundled for [NavGraph] — see [PendingInviteCodes] for why they
     * travel together and why they stay distinct fields.
     */
    private val pendingInviteCodes = PendingInviteCodes(
        pairing = _pendingPairingCode,
        onPairingConsumed = { _pendingPairingCode.value = null },
        guest = _pendingGuestCode,
        onGuestConsumed = { _pendingGuestCode.value = null }
    )

    private val _pendingChatLink = MutableStateFlow<PendingChatLink?>(null)
    private val pendingChatOpen = PendingChatOpen(
        link = _pendingChatLink,
        onConsumed = { _pendingChatLink.value = null }
    )

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

    /**
     * Handles a `coplanly://pair` link (or any other) arriving while the app is
     * already running. `MainActivity` is `singleTask`, so a warm launch is
     * routed here instead of creating a new instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPairingCode(intent)
        readGuestCode(intent)
        readChatDeepLink(intent)
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

        // Only a genuine cold start carries a launching deep link worth reading.
        // A config-change recreation (e.g. rotation) reports a non-null
        // savedInstanceState; re-reading the same launching intent there would
        // re-arm the confirmation dialog for a code the user already handled.
        if (savedInstanceState == null) {
            readPairingCode(intent)
            readGuestCode(intent)
            readChatDeepLink(intent)
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
                                syncViewModel = syncViewModel,
                                pendingInviteCodes = pendingInviteCodes,
                                pendingChatOpen = pendingChatOpen
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

    /**
     * Extracts a pairing code from a `coplanly://pair?code=…` intent.
     *
     * The code is only pre-filled on the pairing screen — redeeming it still
     * needs an explicit confirmation, because a share link may have been
     * forwarded on to someone else.
     *
     * A `coplanly://pair` link with no `code` (e.g. the one a pairing-status
     * push notification opens — see [com.coparently.app.data.remote.firebase.CoPlanlyMessagingService])
     * still needs to land on the pairing screen, just without a prefill. Empty
     * string is used as that "link present, no code" signal rather than null,
     * because [NavGraph][com.coparently.app.presentation.navigation.NavGraph]'s
     * deep-link effect only navigates when this flow holds a non-null value;
     * null is reserved for "no pairing link is pending" so a plain app launch
     * does not force a navigation. `Screen.Pairing.routeWithCode` already
     * treats null and empty identically, so the pairing screen itself sees no
     * difference from the existing code-less navigations (e.g. the Settings
     * menu entry).
     */
    private fun readPairingCode(intent: Intent?) {
        val data = intent?.data ?: return
        if (!PairingUri.isPairingUri(data.scheme, data.host)) return
        _pendingPairingCode.value = PairingUri.extractCode(data.toString()).orEmpty()
    }

    /**
     * Extracts a guest code from a `coplanly://guest?code=…` intent.
     *
     * Deliberately a second reader rather than a `host` branch inside [readPairingCode]. The
     * cost of the two paths crossing is not a broken screen: it is a guest redeemed through
     * `acceptPairingInvitation`, which would make them a co-parent.
     *
     * Unlike the pairing link, a `coplanly://guest` link with no code is ignored. There is no
     * push notification that opens a bare one, and a guest-accept screen with an empty field
     * and no explanation of how they got there would be worse than nothing.
     */
    private fun readGuestCode(intent: Intent?) {
        val data = intent?.data ?: return
        if (!GuestInviteUri.isGuestUri(data.scheme, data.host)) return
        _pendingGuestCode.value = GuestInviteUri.extractCode(data.toString()) ?: return
    }

    /**
     * Recognises a `coplanly://chat` intent (opened by a chat-message push
     * notification — see [com.coparently.app.data.remote.firebase.CoPlanlyMessagingService])
     * and arms [pendingChatOpen] so [NavGraph] navigates to the specific thread, or the
     * Chat tab's list when the link carries no `conversationId` (a manual test push, an
     * older payload, or a hand-typed link).
     */
    private fun readChatDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!ChatUri.isChatUri(data.scheme, data.host)) return
        _pendingChatLink.value = PendingChatLink(ChatUri.extractConversationId(data.toString()))
    }
}
