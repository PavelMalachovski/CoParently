package com.coparently.app.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.data.repository.RatioSubmission
import com.coparently.app.data.sync.SyncStatus
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.common.GroupLabel
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.common.SignedInAsRow
import com.coparently.app.presentation.sync.GoogleCalendarSyncState
import com.coparently.app.presentation.sync.SyncViewModel
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val syncTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Settings screen.
 *
 * Restructured by the August 2026 design review, which found eleven differently-shaped cards
 * on one flat scroll: family setup (pairing, child, custody) sat *below* the two sync cards it
 * is a prerequisite for, and no two cards agreed on whether a title got an icon, whether a row
 * was a `ListItem` or bespoke, or how many controls one card could carry.
 *
 * What replaced it: four labelled groups in dependency order — **Family** (the product),
 * **Sync**, **App**, **Account** — sharing one row anatomy (icon, title, status or value, at
 * most one trailing control). Google Calendar's toggle-plus-status-plus-two-buttons collapses
 * into a single row that expands on demand, and signing out is a red text row at the bottom
 * rather than a full-width error-coloured button.
 *
 * Stateless composable: state lives in the ViewModels, this only renders and forwards events.
 *
 * @param onNavigateUp Returns to the screen that opened Settings
 * @param onNavigateToChildInfo Opens child information
 * @param onNavigateToPets Opens the pets list
 * @param onNavigateToPairing Opens co-parent pairing
 * @param onNavigateToFriends Opens the calendar-friend list (item 16)
 * @param onNavigateToCustodySetup Opens custody schedule setup
 * @param onNavigateToMyProfile Opens the signed-in user's own profile, editable
 * @param onNavigateToCoParentProfile Opens the co-parent's profile, read-only
 * @param onStartGoogleSignIn Launches the Google Sign-In activity
 * @param onSignOut Called after the user signs out of the app
 * @param syncViewModel Sync operations
 * @param settingsViewModel Settings state
 * @param authStateViewModel Firebase auth state, used to sign out
 */
// The complexity is the optional-callback fan-out: each group is only rendered when the route
// behind it was wired, and every one of those checks is a separate branch.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: (() -> Unit)? = null,
    onNavigateToChildInfo: (() -> Unit)? = null,
    onNavigateToPets: (() -> Unit)? = null,
    onNavigateToPairing: (() -> Unit)? = null,
    onNavigateToFriends: (() -> Unit)? = null,
    onNavigateToCustodySetup: (() -> Unit)? = null,
    onNavigateToMyProfile: (() -> Unit)? = null,
    onNavigateToCoParentProfile: (() -> Unit)? = null,
    onStartGoogleSignIn: ((android.content.Intent) -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    syncViewModel: SyncViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    authStateViewModel: com.coparently.app.presentation.sync.AuthStateViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val isSignedIn by syncViewModel.isSignedIn.collectAsState()
    val isSyncEnabled by syncViewModel.isSyncEnabled.collectAsState()
    val googleSyncState by syncViewModel.syncState.collectAsState()
    val firestoreSyncStatus by syncViewModel.firestoreSyncStatus.collectAsState()
    val userEmail by syncViewModel.userEmail.collectAsState()

    val settingsUiState by settingsViewModel.settingsState.collectAsState()
    val caresFor by settingsViewModel.caresFor.collectAsState()
    val agreedRatio by settingsViewModel.agreedRatio.collectAsState()
    var showFamilyKindPicker by rememberSaveable { mutableStateOf(false) }
    var showSplitPicker by rememberSaveable { mutableStateOf(false) }

    if (showSplitPicker) {
        SplitRatioDialog(
            current = agreedRatio,
            onConfirm = { ratio ->
                settingsViewModel.submitRatio(ratio)
                showSplitPicker = false
            },
            onDismiss = { showSplitPicker = false }
        )
    }

    if (showFamilyKindPicker) {
        FamilyKindDialog(
            selected = caresFor,
            onConfirm = { kinds ->
                settingsViewModel.setCaresFor(kinds)
                showFamilyKindPicker = false
            },
            onDismiss = { showFamilyKindPicker = false }
        )
    }
    val darkTheme by settingsViewModel.darkThemeFlow.collectAsState()
    val account by settingsViewModel.account.collectAsState()
    val defaultCurrency by settingsViewModel.defaultCurrency.collectAsState()

    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    // Deletion asks twice, the same shape unpair uses: the first dialog says what is lost,
    // the second says it is permanent. One tap on a red row must not be able to erase a
    // family's history.
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteFinalConfirm by remember { mutableStateOf(false) }
    var googleExpanded by remember { mutableStateOf(false) }
    // AppCompat is the source of truth for the per-app language; selecting a new value
    // recreates the activity, so a plain remember is enough to keep the label fresh.
    var currentLanguage by remember { mutableStateOf(AppLanguage.current()) }

    // Settings had no way to report a failure at all. Account deletion is the first action
    // here that can fail in a way the user must know about — the account still exists and the
    // attempt has to be repeated — so the screen grows a snackbar rather than letting the
    // error message sit unread in the state.
    val snackbarHostState = remember { SnackbarHostState() }
    val deletionFailed = stringResource(R.string.settings_account_delete_failed)
    LaunchedEffect(settingsUiState.errorMessage) {
        if (settingsUiState.errorMessage != null) {
            snackbarHostState.showSnackbar(deletionFailed)
            settingsViewModel.clearMessages()
        }
    }

    // "Saved" and "sent to your co-parent to confirm" are different outcomes, and a parent told
    // the first when the second is true will spend against a split nobody has agreed.
    val splitApplied = stringResource(R.string.settings_split_ratio_applied)
    val splitProposed = stringResource(R.string.settings_split_ratio_proposed)
    val splitRefused = stringResource(R.string.settings_split_ratio_refused)
    LaunchedEffect(Unit) {
        settingsViewModel.ratioSubmission.collect { outcome ->
            snackbarHostState.showSnackbar(
                when (outcome) {
                    RatioSubmission.APPLIED -> splitApplied
                    RatioSubmission.PROPOSED -> splitProposed
                    null -> splitRefused
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // ── FAMILY ─────────────────────────────────────────────────────────
            // First, because it is what the product is. It used to sit below the sync
            // cards that depend on it being set up.
            Column {
                GroupLabel(stringResource(R.string.settings_group_family))
                SectionGroup {
                    onNavigateToPairing?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.Group,
                            title = stringResource(R.string.settings_pairing_title),
                            supporting = stringResource(R.string.settings_pairing_description),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                        Divider()
                    }
                    // The trusted third person (item 16), directly under pairing: a friend can
                    // only be admitted once the pair exists, so this is where a parent already
                    // is when the thought occurs.
                    onNavigateToFriends?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.Diversity3,
                            title = stringResource(R.string.friend_section_title),
                            supporting = stringResource(R.string.friend_section_supporting),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                        Divider()
                    }
                    // What this family co-parents, and the way back into that answer. Without
                    // this row a family that gets a dog a year after signing up could never
                    // reach the (fully built) pet records — design item 8 in reverse.
                    SectionRow(
                        icon = Icons.Default.FamilyRestroom,
                        title = stringResource(R.string.settings_family_kind_title),
                        supporting = caresForSummary(caresFor),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showFamilyKindPicker = true
                        },
                        trailing = { Chevron() }
                    )
                    Divider()
                    // The money agreement lives with the family, not under App preferences: it
                    // is something the two parents agree, like the custody pattern, not a device
                    // setting like the language.
                    SectionRow(
                        icon = Icons.Default.Balance,
                        title = stringResource(R.string.settings_split_ratio_title),
                        supporting = stringResource(
                            R.string.settings_split_ratio_value,
                            agreedRatio.momPercent,
                            agreedRatio.dadPercent
                        ),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showSplitPicker = true
                        },
                        trailing = { Chevron() }
                    )
                    Divider()
                    onNavigateToChildInfo?.takeIf { FamilyKind.CHILDREN in caresFor }?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.ChildCare,
                            title = stringResource(R.string.settings_child_info_title),
                            supporting = stringResource(R.string.settings_child_info_description),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                        Divider()
                    }
                    onNavigateToPets?.takeIf { FamilyKind.PETS in caresFor }?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.Pets,
                            title = stringResource(R.string.settings_pets_title),
                            supporting = stringResource(R.string.settings_pets_description),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                        Divider()
                    }
                    onNavigateToCustodySetup?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.DateRange,
                            title = stringResource(R.string.settings_custody_title),
                            supporting = stringResource(R.string.settings_custody_description),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                        Divider()
                    }
                    onNavigateToMyProfile?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.Person,
                            title = stringResource(R.string.settings_my_profile_title),
                            supporting = stringResource(R.string.settings_my_profile_description),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                        Divider()
                    }
                    onNavigateToCoParentProfile?.let { navigate ->
                        SectionRow(
                            icon = Icons.Default.PersonOutline,
                            title = stringResource(R.string.settings_coparent_profile_title),
                            supporting = stringResource(R.string.settings_coparent_profile_description),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate()
                            },
                            trailing = { Chevron() }
                        )
                    }
                }
            }

            // ── SYNC ───────────────────────────────────────────────────────────
            Column {
                GroupLabel(stringResource(R.string.settings_group_sync))
                SectionGroup {
                    SectionRow(
                        icon = Icons.Default.Sync,
                        title = stringResource(R.string.settings_co_parent_sync),
                        supporting = firestoreSyncStatus.summary(),
                        supportingColor = firestoreSyncStatus.summaryColor(),
                        supportingIcon = firestoreSyncStatus.summaryDot(),
                        trailing = {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                syncViewModel.performFirestoreSync()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.settings_sync)
                                )
                            }
                        }
                    )
                    Divider()
                    // One row, expanded on demand — the card this replaces stacked a toggle,
                    // a status line and two full-width buttons into a single surface.
                    SectionRow(
                        icon = Icons.Default.EventAvailable,
                        title = stringResource(R.string.settings_google_sync),
                        supporting = if (isSignedIn) {
                            userEmail ?: stringResource(R.string.settings_unknown)
                        } else {
                            stringResource(R.string.settings_gcal_not_signed_in)
                        },
                        onClick = { googleExpanded = !googleExpanded },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = isSyncEnabled,
                                    onCheckedChange = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        syncViewModel.toggleSync(enabled)
                                    },
                                    enabled = isSignedIn || !isSyncEnabled
                                )
                                Icon(
                                    imageVector = if (googleExpanded) {
                                        Icons.Default.ExpandLess
                                    } else {
                                        Icons.Default.ExpandMore
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    AnimatedVisibility(visible = googleExpanded) {
                        GoogleCalendarActions(
                            isSignedIn = isSignedIn,
                            isSyncEnabled = isSyncEnabled,
                            syncState = googleSyncState,
                            onSignIn = {
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
                            onSyncNow = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                syncViewModel.syncFromGoogle()
                            },
                            onCalendarSignOut = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                coroutineScope.launch { syncViewModel.signOut() }
                            }
                        )
                    }
                }
            }

            // ── APP ────────────────────────────────────────────────────────────
            Column {
                GroupLabel(stringResource(R.string.settings_group_app))
                SectionGroup {
                    SectionRow(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.settings_theme),
                        onClick = { showThemePicker = true },
                        trailing = { ValueLabel(stringResource(themeLabelRes(darkTheme))) }
                    )
                    Divider()
                    SectionRow(
                        icon = Icons.Default.Language,
                        title = stringResource(R.string.settings_language_title),
                        onClick = { showLanguagePicker = true },
                        trailing = { ValueLabel(stringResource(currentLanguage.labelRes)) }
                    )
                    Divider()
                    SectionRow(
                        icon = Icons.Default.Payments,
                        title = stringResource(R.string.currency_settings_title),
                        onClick = { showCurrencyPicker = true },
                        trailing = {
                            ValueLabel("${defaultCurrency.code} ${defaultCurrency.symbol}")
                        }
                    )
                    Divider()
                    // POST_NOTIFICATIONS is requested here, contextually, not on app start.
                    val notificationPermissionRequester =
                        com.coparently.app.presentation.common.rememberNotificationPermissionRequester()
                    SectionRow(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.settings_push_notifications),
                        supporting = stringResource(R.string.settings_push_notifications_description),
                        trailing = {
                            Switch(
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
                        }
                    )
                }
            }

            // ── ACCOUNT ────────────────────────────────────────────────────────
            Column {
                GroupLabel(stringResource(R.string.settings_group_account))
                SectionGroup {
                    // The app account, not the Google Calendar one reported under Sync. Same
                    // strip as the pairing screen, so "who am I signed in as" reads identically
                    // in both places it can be asked. It leads the group because it is the
                    // subject the other two rows are about.
                    account?.let { signedIn ->
                        SignedInAsRow(
                            account = signedIn,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                        Divider()
                    }
                    SectionRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.settings_about),
                        supporting = stringResource(R.string.settings_about_tagline),
                        trailing = {
                            ValueLabel(
                                text = com.coparently.app.BuildConfig.VERSION_NAME,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    Divider()
                    // Destructive, so it stays at the very bottom of the screen. It reads as
                    // a red text row rather than a filled error button — but a row is easier
                    // to hit by accident than a deliberate button was, so it now confirms.
                    SectionRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.settings_account_sign_out),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showSignOutConfirm = true }
                    )
                    Divider()
                    // Below sign-out on purpose: the two are neighbours in a user's mind and
                    // only one of them is reversible, so the reversible one is reached first.
                    // Google Play requires this path for any app that offers account creation.
                    SectionRow(
                        icon = Icons.Default.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.settings_account_delete),
                        titleColor = MaterialTheme.colorScheme.error,
                        supporting = if (settingsUiState.isDeletingAccount) {
                            stringResource(R.string.settings_account_delete_progress)
                        } else {
                            stringResource(R.string.settings_account_delete_description)
                        },
                        onClick = {
                            if (!settingsUiState.isDeletingAccount) showDeleteConfirm = true
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showSignOutConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_account_sign_out),
            message = stringResource(R.string.settings_account_description),
            confirmText = stringResource(R.string.settings_account_sign_out),
            dismissText = stringResource(R.string.settings_language_picker_cancel),
            isDestructive = true,
            onDismiss = { showSignOutConfirm = false },
            onConfirm = {
                showSignOutConfirm = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // Google Calendar sync first, then Firebase auth, then leave the screen.
                coroutineScope.launch { syncViewModel.signOut() }
                authStateViewModel.signOut()
                onSignOut?.invoke()
            }
        )
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_account_delete_confirm_title),
            message = stringResource(R.string.settings_account_delete_confirm_message),
            confirmText = stringResource(R.string.settings_account_delete),
            dismissText = stringResource(R.string.settings_account_delete_cancel),
            isDestructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                showDeleteFinalConfirm = true
            }
        )
    }

    if (showDeleteFinalConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_account_delete_final_title),
            message = stringResource(R.string.settings_account_delete_final_message),
            confirmText = stringResource(R.string.settings_account_delete_final_yes),
            dismissText = stringResource(R.string.settings_account_delete_cancel),
            isDestructive = true,
            onDismiss = { showDeleteFinalConfirm = false },
            onConfirm = {
                showDeleteFinalConfirm = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // The server erases the account and this device is wiped; only then does the
                // app leave the screen. On failure the account still exists and the row's
                // error message says so, so the user can try again.
                settingsViewModel.deleteAccount { onSignOut?.invoke() }
            }
        )
    }

    if (showThemePicker) {
        ThemePickerDialog(
            selected = darkTheme,
            onSelect = { choice ->
                showThemePicker = false
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (choice == null) {
                    settingsViewModel.resetThemeToSystemDefault()
                } else {
                    settingsViewModel.toggleDarkTheme(choice)
                }
            },
            onDismiss = { showThemePicker = false }
        )
    }

    if (showLanguagePicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_language_picker_title),
            options = AppLanguage.entries.map { it to stringResource(it.labelRes) },
            selected = currentLanguage,
            onSelect = { language ->
                showLanguagePicker = false
                if (language != currentLanguage) {
                    currentLanguage = language
                    // Recreates the activity with the new locale and persists the choice
                    // (autoStoreLocales).
                    AppLanguage.apply(language)
                }
            },
            onDismiss = { showLanguagePicker = false }
        )
    }

    if (showCurrencyPicker) {
        SingleChoiceDialog(
            title = stringResource(R.string.currency_picker_title),
            options = SupportedCurrency.entries.map { it to "${it.code}  ${it.symbol}" },
            selected = defaultCurrency,
            onSelect = { currency ->
                showCurrencyPicker = false
                settingsViewModel.setDefaultCurrency(currency)
            },
            onDismiss = { showCurrencyPicker = false }
        )
    }
}

/** The trailing chevron on a row that opens another screen. */
@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}

/** The trailing current-value text on a row that opens a picker. */
@Composable
private fun ValueLabel(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}

/**
 * The Google Calendar actions, revealed under its row.
 *
 * @param isSignedIn Whether a Google account is connected
 * @param isSyncEnabled Whether syncing is switched on
 * @param syncState Current Google sync state
 * @param onSignIn Starts Google Sign-In
 * @param onSyncNow Pulls events from Google now
 * @param onCalendarSignOut Disconnects the Google account (does not sign out of the app)
 */
@Composable
@Suppress("LongParameterList") // one call site; splitting it would only add a wrapper type
private fun GoogleCalendarActions(
    isSignedIn: Boolean,
    isSyncEnabled: Boolean,
    syncState: GoogleCalendarSyncState,
    onSignIn: () -> Unit,
    onSyncNow: () -> Unit,
    onCalendarSignOut: () -> Unit
) {
    val syncing = syncState is GoogleCalendarSyncState.Syncing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (syncState) {
            is GoogleCalendarSyncState.Syncing -> StatusLine(
                text = syncState.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                busy = true
            )
            is GoogleCalendarSyncState.Success -> StatusLine(
                text = syncState.message,
                color = MaterialTheme.colorScheme.tertiary
            )
            is GoogleCalendarSyncState.Error -> StatusLine(
                text = syncState.message,
                color = MaterialTheme.colorScheme.error
            )
            else -> Unit
        }

        if (!isSignedIn) {
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncing
            ) {
                Text(stringResource(R.string.sync_sign_in_google))
            }
        } else {
            Button(
                onClick = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = isSyncEnabled && !syncing
            ) {
                Text(stringResource(R.string.settings_sync_from_google))
            }
            OutlinedButton(
                onClick = onCalendarSignOut,
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncing
            ) {
                Text(stringResource(R.string.settings_calendar_sign_out))
            }
        }
    }
}

/**
 * A sync status line: a coloured dot (or spinner while working) and a message.
 *
 * Replaces the "✓ …" / "✗ …" text glyphs the audit flagged — a tick typed into a string is not
 * a status component, and it carries no colour or accessibility meaning.
 *
 * @param text Status message
 * @param color Dot and text colour, carrying the state
 * @param busy Shows a spinner instead of the dot while a sync is running
 */
@Composable
private fun StatusLine(text: String, color: Color, busy: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Theme picker. The three FilterChips this replaces were 12sp labels squeezed three-across;
 * a single-choice list is the Material pattern for picking one of three.
 *
 * @param selected Current choice — null means "follow the system"
 * @param onSelect Called with the new choice
 * @param onDismiss Dismisses without changing anything
 */
@Composable
private fun ThemePickerDialog(
    selected: Boolean?,
    onSelect: (Boolean?) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf<Pair<Boolean?, String>>(
        null to stringResource(R.string.settings_theme_system),
        false to stringResource(R.string.settings_theme_light),
        true to stringResource(R.string.settings_theme_dark)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    ChoiceRow(
                        label = label,
                        selected = value == selected,
                        onSelect = { onSelect(value) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_language_picker_cancel))
            }
        }
    )
}

/**
 * A single-choice dialog, shared by the language and currency pickers so the two stop being
 * two hand-rolled copies of the same list.
 *
 * @param title Dialog title
 * @param options Value/label pairs, in display order
 * @param selected Currently selected value
 * @param onSelect Called with the chosen value
 * @param onDismiss Dismisses without changing anything
 */
@Composable
private fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    ChoiceRow(
                        label = label,
                        selected = value == selected,
                        onSelect = { onSelect(value) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_language_picker_cancel))
            }
        }
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}

private fun themeLabelRes(darkTheme: Boolean?): Int = when (darkTheme) {
    null -> R.string.settings_theme_system
    true -> R.string.settings_theme_dark
    false -> R.string.settings_theme_light
}

/** One-line summary of co-parent sync, for the row's supporting text. */
@Composable
private fun SyncStatus.summary(): String = when (this) {
    is SyncStatus.Idle -> stringResource(R.string.settings_sync_idle)
    is SyncStatus.Syncing -> stringResource(R.string.settings_syncing)
    is SyncStatus.Success ->
        stringResource(R.string.settings_sync_last, lastSyncTime.format(syncTimeFormatter))
    is SyncStatus.Error -> message
}

/** Colour for [summary]; errors are the only state that shouts. */
@Composable
private fun SyncStatus.summaryColor(): Color = when (this) {
    is SyncStatus.Error -> MaterialTheme.colorScheme.error
    is SyncStatus.Success -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Status dot colour for [summary], or null while idle. */
@Composable
private fun SyncStatus.summaryDot(): Color? = when (this) {
    is SyncStatus.Idle -> null
    is SyncStatus.Syncing -> MaterialTheme.colorScheme.onSurfaceVariant
    is SyncStatus.Success -> MaterialTheme.colorScheme.tertiary
    is SyncStatus.Error -> MaterialTheme.colorScheme.error
}

/**
 * The one-line summary of what the family co-parents, for the Settings row.
 *
 * Composable because the answer is a set of slot-like constants and the row shows names in the
 * reader's language, which a ViewModel has no `Context` to resolve.
 */
@Composable
private fun caresForSummary(kinds: Set<FamilyKind>): String {
    val children = stringResource(R.string.onboarding_family_children)
    val pets = stringResource(R.string.onboarding_family_pets)
    return when {
        kinds.containsAll(FamilyKind.ALL) -> stringResource(
            R.string.settings_family_kind_both,
            children,
            pets
        )
        FamilyKind.PETS in kinds -> pets
        else -> children
    }
}

/**
 * Changes what the family co-parents.
 *
 * A dialog rather than a second screen: two checkboxes and a confirm is the whole interaction,
 * and it is reached from a row that already says the current answer. Confirm is disabled with
 * nothing ticked — a family that co-parents neither is not a state this product has, and an OK
 * that silently did nothing would be worse than one that is plainly unavailable.
 *
 * @param selected What is currently agreed, as the union of both parents' answers.
 * @param onConfirm Called with the new set; only this parent's own record is written.
 * @param onDismiss Closes without changing anything.
 */
@Composable
private fun FamilyKindDialog(
    selected: Set<FamilyKind>,
    onConfirm: (Set<FamilyKind>) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by rememberSaveable(selected) { mutableStateOf(selected.map { it.name }.toSet()) }
    val kinds = chosen.mapNotNull { name -> FamilyKind.entries.firstOrNull { it.name == name } }
        .toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_family_kind_title)) },
        text = {
            Column {
                FamilyKind.entries.forEach { kind ->
                    val label = stringResource(
                        if (kind == FamilyKind.CHILDREN) {
                            R.string.onboarding_family_children
                        } else {
                            R.string.onboarding_family_pets
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = kind.name in chosen,
                            onCheckedChange = { checked ->
                                chosen = if (checked) chosen + kind.name else chosen - kind.name
                            }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(kinds) },
                enabled = kinds.isNotEmpty()
            ) {
                Text(stringResource(R.string.settings_family_kind_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_family_kind_cancel))
            }
        }
    )
}

/**
 * Picks a new split of a shared expense.
 *
 * A slider over whole percents for slot 1, with slot 2 taking the remainder — the two can never
 * be stored inconsistently because only one of them is a value. Percent rather than a free
 * amount because a ratio is what the parents agree; an amount is what an individual expense is.
 *
 * This dialog **proposes**. Whether it applies straight away depends on whether there is a
 * co-parent to ask, and the screen says which happened rather than letting a parent believe a
 * split the other has not agreed.
 *
 * @param current The agreed ratio, as the slider opens.
 * @param onConfirm Called with the chosen ratio.
 * @param onDismiss Closes without proposing anything.
 */
@Composable
private fun SplitRatioDialog(
    current: SplitRatio,
    onConfirm: (SplitRatio) -> Unit,
    onDismiss: () -> Unit
) {
    var momPercent by rememberSaveable(current) { mutableIntStateOf(current.momPercent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_split_ratio_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.settings_split_ratio_value,
                        momPercent,
                        SPLIT_WHOLE_PERCENT - momPercent
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = momPercent.toFloat(),
                    onValueChange = { momPercent = it.toInt() },
                    valueRange = 0f..SPLIT_WHOLE_PERCENT.toFloat(),
                    steps = SPLIT_SLIDER_STEPS
                )
                Text(
                    text = stringResource(R.string.settings_split_ratio_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(SplitRatio.ofMomPercent(momPercent)) }) {
                Text(stringResource(R.string.settings_family_kind_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_family_kind_cancel))
            }
        }
    )
}

/** A whole share, as a percent. */
private const val SPLIT_WHOLE_PERCENT = 100

/**
 * Stops on the slider: every 5 %.
 *
 * `steps` counts the stops *between* the ends, so twenty 5 % intervals give nineteen. Whole
 * single percents would be a slider nobody can land on with a thumb.
 */
private const val SPLIT_SLIDER_STEPS = 19
