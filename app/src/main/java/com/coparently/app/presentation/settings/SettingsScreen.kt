package com.coparently.app.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.presentation.settings.components.SettingsNavigationCard
import com.coparently.app.presentation.settings.components.SettingsSwitchCard
import com.coparently.app.presentation.sync.GoogleCalendarSyncState
import com.coparently.app.presentation.sync.SyncStatusIndicator
import com.coparently.app.presentation.sync.SyncViewModel
import kotlinx.coroutines.launch

/**
 * Settings screen для управления настройками приложения и синхронизацией.
 * Stateless composable, который делегирует управление состоянием ViewModels.
 *
 * Использует Google Sign-In API для аутентификации с OAuth2 токенами.
 *
 * @param onNavigateUp Навигационный callback для возврата назад
 * @param onNavigateToChildInfo Навигационный callback для экрана информации о ребенке
 * @param onNavigateToPairing Навигационный callback для экрана pairing
 * @param onStartGoogleSignIn Callback для запуска Google Sign-In Activity
 * @param syncViewModel ViewModel для операций синхронизации
 * @param settingsViewModel ViewModel для состояния настроек
 */
// Was baselined before the onNavigateUp signature change; splitting this legacy
// screen into cards is tracked separately.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: (() -> Unit)? = null,
    onNavigateToChildInfo: (() -> Unit)? = null,
    onNavigateToPairing: (() -> Unit)? = null,
    onNavigateToCustodySetup: (() -> Unit)? = null,
    onStartGoogleSignIn: ((android.content.Intent) -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    syncViewModel: SyncViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    authStateViewModel: com.coparently.app.presentation.sync.AuthStateViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Sync ViewModel states
    val isSignedIn by syncViewModel.isSignedIn.collectAsState()
    val isSyncEnabled by syncViewModel.isSyncEnabled.collectAsState()
    val googleSyncState by syncViewModel.syncState.collectAsState()
    val firestoreSyncStatus by syncViewModel.firestoreSyncStatus.collectAsState()
    val userEmail by syncViewModel.userEmail.collectAsState()

    // Settings ViewModel states
    val settingsUiState by settingsViewModel.settingsState.collectAsState()
    val operationState by settingsViewModel.operationState.collectAsState()
    val darkTheme by settingsViewModel.darkThemeFlow.collectAsState()
    val defaultCurrency by settingsViewModel.defaultCurrency.collectAsState()
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    // AppCompat is the source of truth for the per-app language; selecting a new value
    // recreates the activity, so a plain remember is enough to keep the label fresh.
    var currentLanguage by remember { mutableStateOf(AppLanguage.current()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    // Hidden when Settings is opened as a top-level bottom-bar tab
                    onNavigateUp?.let { navigateUp ->
                        IconButton(onClick = navigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Theme Settings
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Text(
                        text = stringResource(R.string.settings_theme_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Theme options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // System Default
                        FilterChip(
                            selected = darkTheme == null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                settingsViewModel.resetThemeToSystemDefault()
                            },
                            label = { Text(stringResource(R.string.settings_theme_system), fontSize = 12.sp) },
                            leadingIcon = if (darkTheme == null) {
                                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f)
                        )

                        // Light Theme
                        FilterChip(
                            selected = darkTheme == false,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                settingsViewModel.toggleDarkTheme(false)
                            },
                            label = { Text(stringResource(R.string.settings_theme_light), fontSize = 12.sp) },
                            leadingIcon = if (darkTheme == false) {
                                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                            } else {
                                { Icon(Icons.Default.LightMode, contentDescription = null, Modifier.size(16.dp)) }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        // Dark Theme
                        FilterChip(
                            selected = darkTheme == true,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                settingsViewModel.toggleDarkTheme(true)
                            },
                            label = { Text(stringResource(R.string.settings_theme_dark), fontSize = 12.sp) },
                            leadingIcon = if (darkTheme == true) {
                                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                            } else {
                                { Icon(Icons.Default.DarkMode, contentDescription = null, Modifier.size(16.dp)) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // App Language
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_language_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    ListItem(
                        headlineContent = {
                            Text(stringResource(currentLanguage.labelRes))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_language_description))
                        },
                        modifier = Modifier.clickable { showLanguagePicker = true }
                    )
                }
            }

            // Default Currency
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payments,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.currency_settings_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.currency_settings_default))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.currency_settings_description))
                        },
                        trailingContent = {
                            TextButton(onClick = { showCurrencyPicker = true }) {
                                Text("${defaultCurrency.code} ${defaultCurrency.symbol}")
                            }
                        }
                    )
                }
            }

            // Firestore Sync Status
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_co_parent_sync),
                            style = MaterialTheme.typography.titleLarge
                        )
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            syncViewModel.performFirestoreSync()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_sync))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SyncStatusIndicator(
                        syncStatus = firestoreSyncStatus,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.settings_co_parent_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Google Calendar Sync
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_google_sync),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Sync enabled toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_gcal_enable_sync),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isSyncEnabled,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                syncViewModel.toggleSync(enabled)
                            },
                            enabled = isSignedIn || !isSyncEnabled
                        )
                    }

                    // Sign-in status
                    Text(
                        text = if (isSignedIn) {
                            stringResource(
                                R.string.settings_signed_in_as,
                                userEmail ?: stringResource(R.string.settings_unknown)
                            )
                        } else {
                            stringResource(R.string.settings_gcal_not_signed_in)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSignedIn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Sync status
                    when (val state = googleSyncState) {
                        is GoogleCalendarSyncState.Syncing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        is GoogleCalendarSyncState.Success -> {
                            Text(
                                text = "✓ ${state.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        is GoogleCalendarSyncState.Error -> {
                            Text(
                                text = "✗ ${state.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        else -> {}
                    }

                    // Action buttons
                    if (!isSignedIn) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val signInIntent = syncViewModel.createGoogleSignInIntent()
                                if (signInIntent != null) {
                                    if (onStartGoogleSignIn != null) {
                                        onStartGoogleSignIn(signInIntent)
                                    } else {
                                        syncViewModel.handleSignInCancellation(
                                            context.getString(R.string.sync_google_sign_in_failed)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            enabled = googleSyncState !is GoogleCalendarSyncState.Syncing
                        ) {
                            if (googleSyncState is GoogleCalendarSyncState.Syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.sync_sign_in_google))
                        }
                    } else {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                syncViewModel.syncFromGoogle()
                            },
                            enabled = isSyncEnabled && googleSyncState !is GoogleCalendarSyncState.Syncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        ) {
                            if (googleSyncState is GoogleCalendarSyncState.Syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.settings_sync_from_google))
                        }

                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                coroutineScope.launch {
                                    syncViewModel.signOut()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            enabled = googleSyncState !is GoogleCalendarSyncState.Syncing
                        ) {
                            Text(stringResource(R.string.settings_calendar_sign_out))
                        }
                    }
                }
            }

            // Co-Parent Pairing
            onNavigateToPairing?.let { navigate ->
                SettingsNavigationCard(
                    title = stringResource(R.string.settings_pairing_title),
                    description = stringResource(R.string.settings_pairing_description),
                    icon = Icons.Default.Group,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        navigate()
                    }
                )
            }

            // Child Information
            onNavigateToChildInfo?.let { navigate ->
                SettingsNavigationCard(
                    title = stringResource(R.string.settings_child_info_title),
                    description = stringResource(R.string.settings_child_info_description),
                    icon = Icons.Default.ChildCare,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        navigate()
                    }
                )
            }

            // Custody Schedule Setup
            onNavigateToCustodySetup?.let { navigate ->
                SettingsNavigationCard(
                    title = stringResource(R.string.settings_custody_title),
                    description = stringResource(R.string.settings_custody_description),
                    icon = Icons.Default.DateRange,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        navigate()
                    }
                )
            }

            // Notifications Settings — POST_NOTIFICATIONS is requested here,
            // contextually, instead of on app start
            val notificationPermissionRequester =
                com.coparently.app.presentation.common.rememberNotificationPermissionRequester()
            SettingsSwitchCard(
                title = stringResource(R.string.settings_push_notifications),
                description = stringResource(R.string.settings_push_notifications_description),
                icon = Icons.Default.Notifications,
                checked = settingsUiState.notificationsEnabled &&
                    com.coparently.app.presentation.common.hasNotificationPermission(context),
                onCheckedChange = { enabled ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (enabled) {
                        notificationPermissionRequester.request {
                            settingsViewModel.toggleNotifications(true)
                        }
                    } else {
                        settingsViewModel.toggleNotifications(false)
                    }
                },
                enabled = !settingsUiState.isLoading
            )

            // About
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_version,
                            com.coparently.app.BuildConfig.VERSION_NAME
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.settings_about_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Account Management — danger action lives at the very bottom so the
            // destructive "Sign out of app" isn't mid-list where it's easy to hit
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_account),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Text(
                        text = stringResource(R.string.settings_account_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // First sign out from Google Calendar sync
                            coroutineScope.launch {
                                syncViewModel.signOut()
                            }
                            // Then sign out from Firebase authentication
                            authStateViewModel.signOut()
                            // Navigate back to auth screen
                            onSignOut?.invoke()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.settings_account_sign_out))
                    }
                }
            }

            if (showLanguagePicker) {
                AlertDialog(
                    onDismissRequest = { showLanguagePicker = false },
                    title = { Text(stringResource(R.string.settings_language_picker_title)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            AppLanguage.entries.forEach { language ->
                                val select = {
                                    showLanguagePicker = false
                                    if (language != currentLanguage) {
                                        currentLanguage = language
                                        // Recreates the activity with the new locale and
                                        // persists the choice (autoStoreLocales).
                                        AppLanguage.apply(language)
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { select() }
                                        .padding(vertical = 8.dp)
                                ) {
                                    RadioButton(
                                        selected = language == currentLanguage,
                                        onClick = { select() }
                                    )
                                    Text(
                                        text = stringResource(language.labelRes),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanguagePicker = false }) {
                            Text(stringResource(R.string.settings_language_picker_cancel))
                        }
                    }
                )
            }

            if (showCurrencyPicker) {
                AlertDialog(
                    onDismissRequest = { showCurrencyPicker = false },
                    title = { Text(stringResource(R.string.currency_picker_title)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            SupportedCurrency.entries.forEach { currency ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            settingsViewModel.setDefaultCurrency(currency)
                                            showCurrencyPicker = false
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    RadioButton(
                                        selected = currency == defaultCurrency,
                                        onClick = {
                                            settingsViewModel.setDefaultCurrency(currency)
                                            showCurrencyPicker = false
                                        }
                                    )
                                    Text(
                                        text = "${currency.code}  ${currency.symbol}",
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCurrencyPicker = false }) {
                            Text(stringResource(R.string.currency_picker_cancel))
                        }
                    }
                )
            }
        }
    }
}
