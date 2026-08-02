package com.coparently.app.presentation.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.coparently.app.presentation.LocalGoogleSignInCallback
import com.coparently.app.presentation.auth.AuthScreen
import com.coparently.app.presentation.calendar.CalendarScreen
import com.coparently.app.presentation.chat.ChatViewModel
import com.coparently.app.presentation.childinfo.ChildInfoScreen
import com.coparently.app.presentation.common.animations.*
import com.coparently.app.presentation.event.AddEditEventScreen
import com.coparently.app.presentation.event.EventListScreen
import com.coparently.app.presentation.pairing.PairingScreen
import com.coparently.app.presentation.settings.SettingsScreen
import com.coparently.app.presentation.sync.AuthStateViewModel
import com.coparently.app.presentation.sync.SyncViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Navigation graph for the app.
 * Defines all navigation routes and their destinations.
 * Includes authentication guard to redirect unauthenticated users to AuthScreen.
 * Top-level destinations (Calendar / Chat / Expenses / Settings) share a bottom
 * navigation bar; detail screens hide it.
 *
 * @param pendingPairingCode A code carried by a `coplanly://pair` deep link
 *   ([MainActivity][com.coparently.app.presentation.MainActivity] owns it), or
 *   null when none is outstanding.
 * @param onPairingCodeConsumed Called once [pendingPairingCode] has been
 *   handed to the pairing screen, so the caller can clear it and avoid
 *   re-navigating on the next recomposition.
 * @param pendingChatOpen A `coplanly://chat` deep link awaiting hand-off to the Chat tab,
 *   bundled with its own consumption callback (see [PendingChatOpen]) rather than as two more
 *   loose parameters alongside [pendingPairingCode]/[onPairingCodeConsumed] — that shape
 *   would have pushed this function's parameter count to detekt's `LongParameterList`
 *   threshold of 6.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    syncViewModel: SyncViewModel,
    pendingPairingCode: StateFlow<String?>,
    onPairingCodeConsumed: () -> Unit,
    pendingChatOpen: PendingChatOpen
) {
    val authStateViewModel: AuthStateViewModel = hiltViewModel()
    val isAuthenticated by authStateViewModel.isAuthenticated.collectAsState()
    val isLoading by authStateViewModel.isLoading.collectAsState()
    val chatUnreadCount = rememberChatUnreadCount()

    // Determine start destination based on authentication state
    val startDestination = when {
        isLoading -> Screen.Loading.route
        isAuthenticated == true -> Screen.Home.route
        else -> Screen.Auth.route
    }

    PairingDeepLinkEffect(pendingPairingCode, isAuthenticated, navController, onPairingCodeConsumed)
    ChatDeepLinkEffect(pendingChatOpen, isAuthenticated, navController)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = currentRoute in BottomNavDestination.topLevelRoutes,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                CoPlanlyBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { destination ->
                        navController.navigate(destination.navRoute) {
                            // Keep one instance per tab, preserve each tab's state
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    chatUnreadCount = chatUnreadCount
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Loading screen while checking authentication
            composable(
                route = Screen.Loading.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() }
            ) {
                LoadingScreen()
            }

            // Authentication screen for unauthenticated users
            composable(
                route = Screen.Auth.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() }
            ) {
                AuthScreen(
                    onAuthSuccess = {
                        // Refresh auth state and navigate to main app
                        authStateViewModel.refreshAuthState()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    },
                    onViewModelReady = { authViewModel ->
                        // Set callback to refresh auth state when authentication succeeds
                        authViewModel.onAuthStateChanged = {
                            authStateViewModel.refreshAuthState()
                        }
                    }
                )
            }

            // Home / overview dashboard — first screen (MVP 2)
            composable(
                route = Screen.Home.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.home.HomeScreen(
                    onOpenEvent = { eventId ->
                        navController.navigate(Screen.EditEvent.createRoute(eventId))
                    },
                    onOpenChangeRequests = {
                        navController.navigate(Screen.ChangeRequests.route)
                    },
                    onOpenWeeklySummary = {
                        navController.navigate(Screen.WeeklySummary.route)
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToPairing = {
                        navController.navigate(Screen.Pairing.routeWithCode(null))
                    }
                )
            }

            composable(
                route = Screen.Calendar.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                CalendarScreen(
                    onEventClick = { eventId ->
                        navController.navigate(Screen.EditEvent.createRoute(eventId))
                    },
                    onAddEventClick = { date, hour ->
                        navController.navigate(Screen.AddEvent.createRoute(date, hour))
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onChangeRequestsClick = {
                        navController.navigate(Screen.ChangeRequests.route)
                    },
                    onWeeklySummaryClick = {
                        navController.navigate(Screen.WeeklySummary.route)
                    }
                )
            }

            composable(
                route = Screen.EventList.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                EventListScreen(
                    onEventClick = { eventId ->
                        navController.navigate(Screen.EditEvent.createRoute(eventId))
                    },
                    onAddEventClick = {
                        navController.navigate(Screen.AddEvent.route)
                    },
                    onNavigateUp = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.AddEvent.route,
                arguments = listOf(
                    navArgument(Screen.AddEvent.ARG_DATE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.AddEvent.ARG_HOUR) {
                        type = NavType.IntType
                        defaultValue = -1
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val dateString = backStackEntry.arguments?.getString(Screen.AddEvent.ARG_DATE)
                val hourValue = backStackEntry.arguments?.getInt(Screen.AddEvent.ARG_HOUR) ?: -1
                val hour = if (hourValue >= 0) hourValue else null
                val initialDate = dateString?.takeIf { it != "null" }?.let { java.time.LocalDate.parse(it) }

                AddEditEventScreen(
                    eventId = null,
                    initialDate = initialDate,
                    initialHour = hour,
                    onSave = {
                        navController.popBackStack()
                    },
                    onCancel = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.EditEvent.route,
                arguments = listOf(
                    navArgument(Screen.EditEvent.ARG_EVENT_ID) {
                        type = NavType.StringType
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString(Screen.EditEvent.ARG_EVENT_ID) ?: return@composable
                AddEditEventScreen(
                    eventId = eventId,
                    onSave = {
                        navController.popBackStack()
                    },
                    onCancel = {
                        navController.popBackStack()
                    },
                    onRequestChange = { id ->
                        navController.navigate(Screen.RequestChange.createRoute(id))
                    }
                )
            }

            // Weekly summary dashboard (MVP 2)
            composable(
                route = Screen.WeeklySummary.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.summary.WeeklySummaryScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onEventClick = { eventId ->
                        navController.navigate(Screen.EditEvent.createRoute(eventId))
                    },
                    onOpenChangeRequests = {
                        navController.navigate(Screen.ChangeRequests.route)
                    }
                )
            }

            // Event change requests inbox (MVP 2)
            composable(
                route = Screen.ChangeRequests.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.changerequests.ChangeRequestsScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onOpenEvent = { eventId ->
                        navController.navigate(Screen.EditEvent.createRoute(eventId))
                    }
                )
            }

            // Propose a new time for an event (MVP 2). Optional conversationId means the
            // request was started from a chat, so a message is posted back to that thread.
            composable(
                route = Screen.RequestChange.route,
                arguments = listOf(
                    navArgument(Screen.RequestChange.ARG_EVENT_ID) {
                        type = NavType.StringType
                    },
                    navArgument(Screen.RequestChange.ARG_CONVERSATION_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString(Screen.RequestChange.ARG_EVENT_ID) ?: return@composable
                val conversationId = backStackEntry.arguments
                    ?.getString(Screen.RequestChange.ARG_CONVERSATION_ID)
                    ?.takeIf { it != "null" }
                com.coparently.app.presentation.changerequests.RequestChangeScreen(
                    eventId = eventId,
                    onBack = {
                        navController.popBackStack()
                    },
                    conversationId = conversationId
                )
            }

            composable(
                route = Screen.Settings.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                val googleSignInCallback = LocalGoogleSignInCallback.current
                SettingsScreen(
                    // Reached via the gear action in the top-level top bars — it opens as
                    // a detail screen, so it gets a back arrow.
                    onNavigateUp = { navController.popBackStack() },
                    onNavigateToChildInfo = {
                        navController.navigate(Screen.ChildInfo.route)
                    },
                    onNavigateToPairing = {
                        navController.navigate(Screen.Pairing.routeWithCode(null))
                    },
                    onNavigateToCustodySetup = {
                        navController.navigate(Screen.CustodySetup.route)
                    },
                    onStartGoogleSignIn = googleSignInCallback,
                    onSignOut = {
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    syncViewModel = syncViewModel
                )
            }

            composable(
                route = Screen.ChildInfo.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                ChildInfoScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onEditClick = { childInfoId ->
                        navController.navigate(Screen.EditChildInfo.createRoute(childInfoId))
                    }
                )
            }

            composable(
                route = Screen.EditChildInfo.route,
                arguments = listOf(
                    navArgument(Screen.EditChildInfo.ARG_CHILD_INFO_ID) {
                        type = NavType.StringType
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val childInfoId = backStackEntry.arguments?.getString(Screen.EditChildInfo.ARG_CHILD_INFO_ID) ?: "new"
                com.coparently.app.presentation.childinfo.AddEditChildInfoScreen(
                    childInfoId = childInfoId,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.Pairing.route,
                arguments = listOf(
                    navArgument(Screen.Pairing.ARG_CODE) {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                ),
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) { backStackEntry ->
                PairingScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    prefilledCode = backStackEntry.arguments
                        ?.getString(Screen.Pairing.ARG_CODE)
                        ?.takeIf { it.isNotEmpty() }
                )
            }

            composable(
                route = Screen.CustodySetup.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.custody.CustodySetupScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // Chat & Communications
            composable(
                route = Screen.Conversations.route,
                arguments = listOf(
                    navArgument(Screen.Conversations.ARG_DRAFT) {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                ),
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) { backStackEntry ->
                val draft = backStackEntry.arguments
                    ?.getString(Screen.Conversations.ARG_DRAFT).orEmpty()
                com.coparently.app.presentation.chat.ConversationsScreen(
                    onConversationClick = { conversationId ->
                        navController.navigate(
                            Screen.Chat.createRoute(conversationId, draft.ifEmpty { null })
                        )
                    },
                    onNavigateToPairing = {
                        navController.navigate(Screen.Pairing.routeWithCode(null))
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument(Screen.Chat.ARG_CONVERSATION_ID) {
                        type = NavType.StringType
                    },
                    navArgument(Screen.Chat.ARG_DRAFT) {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                ),
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString(Screen.Chat.ARG_CONVERSATION_ID) ?: return@composable
                com.coparently.app.presentation.chat.ChatScreen(
                    conversationId = conversationId,
                    draft = backStackEntry.arguments?.getString(Screen.Chat.ARG_DRAFT).orEmpty(),
                    onBack = {
                        navController.popBackStack()
                    },
                    onRequestChangeForEvent = { eventId ->
                        navController.navigate(
                            Screen.RequestChange.createRoute(eventId, conversationId)
                        )
                    }
                )
            }

            // Expenses & Budget
            composable(
                route = Screen.Expenses.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.expenses.ExpenseScreen(
                    onAddExpense = {
                        navController.navigate(Screen.AddExpense.route)
                    },
                    onEditExpense = { expenseId ->
                        navController.navigate(Screen.EditExpense.createRoute(expenseId))
                    },
                    onOpenBudgets = {
                        navController.navigate(Screen.Budgets.route)
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onSettleUp = { draft ->
                        // Carries the message to the thread the user opens and stops there:
                        // sending it is theirs to do.
                        navController.navigate(Screen.Conversations.createRoute(draft))
                    }
                )
            }

            composable(
                route = Screen.AddExpense.route,
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) {
                com.coparently.app.presentation.expenses.AddExpenseScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.EditExpense.route,
                arguments = listOf(
                    navArgument(Screen.EditExpense.ARG_EXPENSE_ID) {
                        type = NavType.StringType
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val expenseId = backStackEntry.arguments
                    ?.getString(Screen.EditExpense.ARG_EXPENSE_ID) ?: return@composable
                com.coparently.app.presentation.expenses.AddExpenseScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    expenseId = expenseId
                )
            }

            composable(
                route = Screen.Budgets.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.expenses.BudgetScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

/**
 * The Chat tab's unread-message count, for [CoPlanlyBottomBar]'s badge.
 *
 * `hiltViewModel()` resolves against [androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner],
 * which is decided by *composition position*, not by which Kotlin function the call happens
 * to sit in — so calling it here, from the same place [NavGraph] calls it for
 * `authStateViewModel`, keeps this [ChatViewModel] instance scoped to that same ambient owner
 * (the hosting Activity, so it survives for the app's lifetime) exactly as if the two lines
 * were inlined into [NavGraph] itself. They are pulled out into this small composable purely
 * to avoid growing [NavGraph] — already the codebase's longest function and already flagged
 * by detekt's `LongMethod` check — by lines that have nothing to do with routing.
 *
 * The Chat/Conversations screens keep creating their *own* `hiltViewModel()` instance, scoped
 * to their own back-stack entry, independent of this one; both merely observe the same
 * repository-backed flows, so there is no read-mark or state conflict between the two.
 */
@Composable
private fun rememberChatUnreadCount(): Int {
    val chatViewModel: ChatViewModel = hiltViewModel()
    val unreadCount by chatViewModel.unreadCount.collectAsState()
    return unreadCount
}

/**
 * Navigates to [Screen.Pairing] once a `coplanly://pair` deep link's code is
 * both present and safe to act on.
 *
 * A deep-linked pairing code must never be redeemed automatically, and it
 * must never land an unauthenticated user on the pairing screen behind the
 * auth gate: it is only actioned once [isAuthenticated] is confirmed `true`
 * (never while still loading — `null` — never while confirmed `false`).
 * Until then the code stays pending and the user follows the normal Auth
 * flow; once they sign in, this recomposes and fires.
 *
 * `popUpTo(...) { inclusive = true }` (rather than `launchSingleTop`) is
 * deliberate: it drops any Pairing entry already on the back stack before
 * pushing a fresh one, so a second link opened while already on that screen
 * still creates a brand-new entry. That matters because `PairingScreen`
 * re-arms its confirmation dialog via `rememberSaveable(prefilledCode)` — a
 * reused (singleTop) entry keeps the old saved state and never shows the
 * dialog for the new code, verified on-device before switching to this
 * approach. One side effect of dropping the old entry: if the user was
 * mid-way through typing a code by hand on that screen, the in-progress
 * text and any open confirmation dialog are discarded along with it. That is
 * accepted as correct here — a deep-linked code must never silently win over
 * what the user is doing, so replacing rather than merging keeps the two
 * paths from bleeding into each other.
 *
 * @param pendingPairingCode The code awaiting hand-off, or null when none is
 *   outstanding — see [NavGraph]'s parameter of the same name.
 * @param isAuthenticated Current auth state (`null` while loading).
 * @param navController Used to perform the navigation once conditions are met.
 * @param onPairingCodeConsumed Called once the code has been handed to the
 *   pairing screen, so the caller can clear it and avoid re-navigating on
 *   the next recomposition.
 */
@Composable
private fun PairingDeepLinkEffect(
    pendingPairingCode: StateFlow<String?>,
    isAuthenticated: Boolean?,
    navController: NavHostController,
    onPairingCodeConsumed: () -> Unit
) {
    val pairingCode by pendingPairingCode.collectAsState()
    LaunchedEffect(pairingCode, isAuthenticated) {
        if (pairingCode != null && isAuthenticated == true) {
            navController.navigate(Screen.Pairing.routeWithCode(pairingCode)) {
                popUpTo(Screen.Pairing.route) { inclusive = true }
            }
            onPairingCodeConsumed()
        }
    }
}

/**
 * A `coplanly://chat` deep link awaiting hand-off, or null while none is pending.
 *
 * [conversationId] carries the id from the link's `?conversationId=…` query parameter (see
 * [com.coparently.app.domain.chat.ChatUri]), or null for a bare `coplanly://chat` link — a
 * manual test push, an older payload, or a hand-typed link may carry none, and that must
 * degrade to opening the Chat tab's list rather than fail.
 */
data class PendingChatLink(val conversationId: String?)

/**
 * A [PendingChatLink] awaiting hand-off, bundled with the callback that clears it once
 * consumed.
 *
 * Exists purely to keep [NavGraph]'s own signature from growing by two more loose parameters
 * every time another deep link is added — see [NavGraph]'s `pendingChatOpen` doc.
 *
 * @property link The pending link, or null when none is outstanding.
 * @property onConsumed Called once the link has been acted on, so the owner (
 *   [MainActivity][com.coparently.app.presentation.MainActivity]) can clear it and avoid
 *   re-navigating on the next recomposition.
 */
class PendingChatOpen(val link: StateFlow<PendingChatLink?>, val onConsumed: () -> Unit)

/**
 * The route a [PendingChatLink] should open: the specific thread when it carries a
 * conversation id, otherwise the Chat tab's conversation list.
 *
 * A pure function (no [Composable] dependency) purely so this fallback — the part the review
 * that added it cared about — is pinned by a plain unit test rather than only exercised
 * through Compose UI test infrastructure this project does not otherwise use.
 *
 * @param conversationId The id from the link, or null/blank for a bare `coplanly://chat` link.
 * @return The route to navigate to.
 */
internal fun chatDeepLinkRoute(conversationId: String?): String =
    if (conversationId.isNullOrBlank()) {
        Screen.Conversations.createRoute()
    } else {
        Screen.Chat.createRoute(conversationId)
    }

/**
 * Navigates to the Chat tab (or a specific thread) once a `coplanly://chat` deep link is both
 * pending and safe to act on — same authentication guard as [PairingDeepLinkEffect], for the
 * same reason: an unauthenticated user must follow the normal Auth flow rather than being
 * dropped straight onto a screen behind the auth gate.
 *
 * Unlike [PairingDeepLinkEffect], this never pops [Screen.Conversations] off the back stack
 * first: `PairingScreen` needed that because it re-arms a confirmation dialog via
 * `rememberSaveable`, but nothing in `ChatScreen`/`ConversationsScreen` has an analogous
 * stale-state problem, so a plain [NavHostController.navigate] with `launchSingleTop` is
 * enough to avoid stacking duplicate entries from a repeated tap.
 *
 * @param pendingChatOpen The pending link and its consumption callback.
 * @param isAuthenticated Current auth state (`null` while loading).
 * @param navController Used to perform the navigation once conditions are met.
 */
@Composable
private fun ChatDeepLinkEffect(
    pendingChatOpen: PendingChatOpen,
    isAuthenticated: Boolean?,
    navController: NavHostController
) {
    val chatLink by pendingChatOpen.link.collectAsState()
    LaunchedEffect(chatLink, isAuthenticated) {
        if (chatLink != null && isAuthenticated == true) {
            navController.navigate(chatDeepLinkRoute(chatLink?.conversationId)) {
                launchSingleTop = true
            }
            pendingChatOpen.onConsumed()
        }
    }
}

/**
 * Loading screen displayed while checking authentication state.
 */
@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Checking authentication...",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Sealed class representing all navigation screens.
 */
sealed class Screen(val route: String) {
    data object Loading : Screen("loading")
    data object Auth : Screen("auth")
    data object Home : Screen("home")
    data object Calendar : Screen("calendar")
    data object EventList : Screen("event_list")
    data object AddEvent : Screen("add_event?date={date}&hour={hour}") {
        const val ARG_DATE = "date"
        const val ARG_HOUR = "hour"

        fun createRoute(date: java.time.LocalDate? = null, hour: Int? = null): String {
            val dateParam = date?.toString() ?: "null"
            val hourParam = hour?.toString() ?: "-1"
            return "add_event?date=$dateParam&hour=$hourParam"
        }
    }
    data object Settings : Screen("settings")
    data object ChildInfo : Screen("child_info")
    data object Pairing : Screen("pairing?code={code}") {
        /** Optional invite code carried by a `coplanly://pair` deep link. */
        const val ARG_CODE = "code"

        /** Builds the route, with [code] pre-filled when a deep link supplied one. */
        fun routeWithCode(code: String?): String =
            if (code.isNullOrEmpty()) "pairing" else "pairing?code=$code"
    }
    data object CustodySetup : Screen("custody_setup")

    data object EditEvent : Screen("edit_event/{eventId}") {
        const val ARG_EVENT_ID = "eventId"

        fun createRoute(eventId: String): String {
            return "edit_event/$eventId"
        }
    }

    data object EditChildInfo : Screen("edit_child_info/{childInfoId}") {
        const val ARG_CHILD_INFO_ID = "childInfoId"

        fun createRoute(childInfoId: String): String {
            return "edit_child_info/$childInfoId"
        }
    }

    /**
     * Conversation list. An optional [ARG_DRAFT] is carried through to the thread the user
     * opens, so "Settle up" on Expenses can pre-fill the composer without sending anything.
     */
    data object Conversations : Screen("conversations?draft={draft}") {
        const val ARG_DRAFT = "draft"

        fun createRoute(draft: String? = null): String {
            val encoded = draft?.let { Uri.encode(it) }.orEmpty()
            return "conversations?draft=$encoded"
        }
    }

    data object Chat : Screen("chat/{conversationId}?draft={draft}") {
        const val ARG_CONVERSATION_ID = "conversationId"
        const val ARG_DRAFT = "draft"

        fun createRoute(conversationId: String, draft: String? = null): String {
            val encoded = draft?.let { Uri.encode(it) }.orEmpty()
            return "chat/$conversationId?draft=$encoded"
        }
    }
    data object Expenses : Screen("expenses")
    data object AddExpense : Screen("add_expense")
    data object EditExpense : Screen("edit_expense/{expenseId}") {
        const val ARG_EXPENSE_ID = "expenseId"

        fun createRoute(expenseId: String): String = "edit_expense/$expenseId"
    }
    data object Budgets : Screen("budgets")

    data object WeeklySummary : Screen("weekly_summary")
    data object ChangeRequests : Screen("change_requests")
    data object RequestChange : Screen("request_change/{eventId}?conversationId={conversationId}") {
        const val ARG_EVENT_ID = "eventId"
        const val ARG_CONVERSATION_ID = "conversationId"

        fun createRoute(eventId: String, conversationId: String? = null): String {
            val convParam = conversationId ?: "null"
            return "request_change/$eventId?conversationId=$convParam"
        }
    }
}
