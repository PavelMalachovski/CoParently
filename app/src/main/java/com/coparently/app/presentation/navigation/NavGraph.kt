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
import com.coparently.app.presentation.onboarding.OnboardingScreen
import com.coparently.app.presentation.pets.AddEditPetScreen
import com.coparently.app.presentation.pets.PetsScreen
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
 * @param pendingInviteCodes The `coplanly://pair` and `coplanly://guest` codes awaiting
 *   hand-off ([MainActivity][com.coparently.app.presentation.MainActivity] owns both), each
 *   with its own consumption callback — see [PendingInviteCodes].
 * @param pendingChatOpen A `coplanly://chat` deep link awaiting hand-off to the Chat tab,
 *   bundled with its own consumption callback (see [PendingChatOpen]) rather than as two more
 *   loose parameters — that shape would have pushed this function's parameter count to
 *   detekt's `LongParameterList` threshold of 6, which is also why the two invite codes
 *   above travel together.
 */
@Composable
// A NavHost's body is one flat list of route declarations, not branching logic — splitting it
// would only relocate the length into a second file without reducing what a reader has to scan
// to find a given route. Same reasoning HomeScreen.kt applies to its own linear column of
// dashboard sections.
@Suppress("LongMethod")
fun NavGraph(
    navController: NavHostController,
    syncViewModel: SyncViewModel,
    pendingInviteCodes: PendingInviteCodes,
    pendingChatOpen: PendingChatOpen
) {
    val authStateViewModel: AuthStateViewModel = hiltViewModel()
    val isAuthenticated by authStateViewModel.isAuthenticated.collectAsState()
    val isLoading by authStateViewModel.isLoading.collectAsState()
    val needsOnboarding by authStateViewModel.needsOnboarding.collectAsState()
    val chatUnreadCount = rememberChatUnreadCount()

    // Determine start destination based on authentication state, and — for a signed-in account
    // — on whether the first-run questionnaire still has to run. That second answer is a Room
    // read, so it is unknown for a moment after authentication resolves; while it is unknown
    // this must stay on Loading. Routing an unknown answer to Home would flash the dashboard
    // and then replace it with a questionnaire, which is worse than a moment's spinner.
    val startDestination = when {
        isLoading -> Screen.Loading.route
        isAuthenticated != true -> Screen.Auth.route
        needsOnboarding == null -> Screen.Loading.route
        needsOnboarding == true -> Screen.Onboarding.route
        else -> Screen.Home.route
    }

    PairingDeepLinkEffect(
        pendingInviteCodes.pairing,
        isAuthenticated,
        navController,
        pendingInviteCodes.onPairingConsumed
    )
    GuestDeepLinkEffect(pendingInviteCodes, isAuthenticated, navController)
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
                    onNavigate = navController::navigateToTab,
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

            // The first-run questionnaire, for an account that has not been through it.
            composable(
                route = Screen.Onboarding.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() }
            ) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    },
                    onOpenCustodySetup = { navController.navigate(Screen.CustodySetup.route) },
                    onOpenPairing = { navController.navigate(Screen.Pairing.routeWithCode(null)) }
                )
            }

            // Authentication screen for unauthenticated users
            composable(
                route = Screen.Auth.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() }
            ) {
                AuthScreen(
                    onAuthSuccess = {
                        // Re-runs the whole start-destination decision, questionnaire included,
                        // and parks on Loading until it resolves. Navigating straight to Home
                        // here — as this did — is what would let a parent who signed up in this
                        // very session never see the wizard at all: the start-destination
                        // decision above resolves once per authentication check, not per frame.
                        authStateViewModel.refreshAuthState()
                        navController.navigate(Screen.Loading.route) {
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
                        navController.navigate(Screen.ChangeRequests.createRoute())
                    },
                    onOpenContacts = {
                        navController.navigate(Screen.Contacts.route)
                    },
                    onOpenChildInfo = {
                        navController.navigate(Screen.ChildInfo.route)
                    },
                    onOpenPets = {
                        navController.navigate(Screen.Pets.route)
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToPairing = {
                        navController.navigate(Screen.Pairing.routeWithCode(null))
                    },
                    // The dashboard's stat tiles deep-link into the tabs that own those
                    // numbers, so they behave exactly like tapping the tab itself — same
                    // back stack, same restored state, bottom bar highlights correctly.
                    onOpenExpenses = {
                        navController.navigateToTab(BottomNavDestination.EXPENSES)
                    },
                    onOpenChat = {
                        navController.navigateToTab(BottomNavDestination.CHAT)
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
                        navController.navigate(Screen.ChangeRequests.createRoute())
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

            // Contacts — the numbers worth finding in a hurry. A detail screen, deliberately
            // not a tab: it is opened rarely and urgently, not browsed.
            composable(
                route = Screen.Contacts.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.contacts.ContactsScreen(
                    onNavigateUp = { navController.popBackStack() }
                )
            }

            // Event change requests inbox (MVP 2)
            composable(
                route = Screen.ChangeRequests.route,
                arguments = listOf(
                    navArgument(Screen.ChangeRequests.ARG_EVENT_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) { backStackEntry ->
                val linkedEventId = backStackEntry.arguments
                    ?.getString(Screen.ChangeRequests.ARG_EVENT_ID)
                    ?.takeIf { it != "null" }
                com.coparently.app.presentation.changerequests.ChangeRequestsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEvent = { eventId ->
                        navController.navigate(Screen.EditEvent.createRoute(eventId))
                    },
                    linkedEventId = linkedEventId
                )
            }

            // Propose a new time for an event (MVP 2). The thread the proposal is announced in
            // is resolved from the two uids by `ActivityAnnouncer`, not carried in the route.
            composable(
                route = Screen.RequestChange.route,
                arguments = listOf(
                    navArgument(Screen.RequestChange.ARG_EVENT_ID) {
                        type = NavType.StringType
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString(Screen.RequestChange.ARG_EVENT_ID) ?: return@composable
                com.coparently.app.presentation.changerequests.RequestChangeScreen(
                    eventId = eventId,
                    onBack = {
                        navController.popBackStack()
                    }
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
                    onNavigateToPets = {
                        navController.navigate(Screen.Pets.route)
                    },
                    onNavigateToPairing = {
                        navController.navigate(Screen.Pairing.routeWithCode(null))
                    },
                    onNavigateToCustodySetup = {
                        navController.navigate(Screen.CustodySetup.route)
                    },
                    onNavigateToMyProfile = {
                        navController.navigate(Screen.MyProfile.route)
                    },
                    onNavigateToCoParentProfile = {
                        navController.navigate(Screen.CoParentProfile.route)
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

            // Pets: a list screen plus its editor, both detail routes (bottom bar hidden),
            // mirroring the ChildInfo pair above.
            composable(
                route = Screen.Pets.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                PetsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onEditPet = { petId ->
                        navController.navigate(Screen.EditPet.createRoute(petId))
                    }
                )
            }

            composable(
                route = Screen.EditPet.route,
                arguments = listOf(
                    navArgument(Screen.EditPet.ARG_PET_ID) {
                        type = NavType.StringType
                    }
                ),
                enterTransition = { fadeInScaleUp() },
                exitTransition = { fadeOutScaleDown() },
                popEnterTransition = { fadeInScaleUp() },
                popExitTransition = { fadeOutScaleDown() }
            ) { backStackEntry ->
                val petId = backStackEntry.arguments?.getString(Screen.EditPet.ARG_PET_ID) ?: "new"
                AddEditPetScreen(
                    petId = petId,
                    onNavigateBack = { navController.popBackStack() }
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
                    onCustodyConflict = {
                        navController.navigate(Screen.CustodyConflict.route)
                    },
                    prefilledCode = backStackEntry.arguments
                        ?.getString(Screen.Pairing.ARG_CODE)
                        ?.takeIf { it.isNotEmpty() }
                )
            }

            composable(
                route = Screen.GuestAccept.route,
                arguments = listOf(
                    navArgument(Screen.GuestAccept.ARG_CODE) {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                ),
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) { backStackEntry ->
                com.coparently.app.presentation.guests.GuestAcceptScreen(
                    onDone = { navController.popBackStack() },
                    prefilledCode = backStackEntry.arguments
                        ?.getString(Screen.GuestAccept.ARG_CODE)
                        ?.takeIf { it.isNotEmpty() }
                )
            }

            composable(
                route = Screen.CustodyConflict.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                // No `onNavigateBack`: the screen offers two actions and no third exit, and
                // swallows the system back gesture itself. This lambda runs only once a choice
                // has been written (or when there is no conflict left to show), so popping here
                // never discards an unmade decision.
                com.coparently.app.presentation.pairing.CustodyConflictScreen(
                    onResolved = {
                        navController.popBackStack()
                    }
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

            // Both are detail screens: neither route is in BottomNavDestination.topLevelRoutes,
            // so the bottom bar hides itself automatically, same as Settings/ChildInfo above.
            composable(
                route = Screen.MyProfile.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.profile.ProfileScreen(
                    editable = true,
                    onNavigateUp = navController::popBackStack
                )
            }

            composable(
                route = Screen.CoParentProfile.route,
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) {
                com.coparently.app.presentation.profile.ProfileScreen(
                    editable = false,
                    onNavigateUp = navController::popBackStack
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
                    },
                    // With one co-parent there is one conversation, and the tab renders that
                    // thread in place — so the draft and the change-request route have to
                    // reach it here too, not only via the Chat detail route below.
                    draft = draft,
                    onRequestChangeForEvent = { eventId ->
                        navController.navigate(Screen.RequestChange.createRoute(eventId))
                    },
                    onOpenChangeRequest = { eventId ->
                        navController.navigate(Screen.ChangeRequests.createRoute(eventId))
                    },
                    // A day-swap chat card: the inbox with nothing highlighted — its
                    // entity is a date the event-id argument would misread.
                    onOpenInbox = {
                        navController.navigate(Screen.ChangeRequests.createRoute())
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
                            Screen.RequestChange.createRoute(eventId)
                        )
                    },
                    onOpenChangeRequest = { eventId ->
                        navController.navigate(Screen.ChangeRequests.createRoute(eventId))
                    },
                    // A day-swap chat card: the inbox with nothing highlighted — its
                    // entity is a date the event-id argument would misread.
                    onOpenInbox = {
                        navController.navigate(Screen.ChangeRequests.createRoute())
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
                        // sending it is theirs to do. Same tab semantics as navigateToTab — a
                        // plain navigate() here was the one path that pushed the Chat route
                        // onto the Expenses tab's stack, so the next tab switch saved that
                        // mixed stack and every later visit to Expenses restored the chat
                        // screen on top of it instead of the expenses list.
                        navController.navigate(Screen.Conversations.createRoute(draft)) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = false
                        }
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
 * Switches to a top-level tab.
 *
 * Extracted from the bottom bar's own handler because the home dashboard's stat tiles are
 * deep links into Expenses and Chat and must land the user in exactly the state tapping the
 * tab would have: one instance per tab, each tab's own scroll position restored, and a back
 * stack that unwinds to Home rather than accumulating tab entries.
 *
 * @param destination Tab to show
 */
private fun NavHostController.navigateToTab(destination: BottomNavDestination) {
    navigate(destination.navRoute) {
        // Keep one instance per tab, preserve each tab's state
        popUpTo(Screen.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
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
 * The two invitation codes a deep link can carry, each with the callback that clears it.
 *
 * They travel together because they arrive the same way and are consumed the same way — and
 * because [NavGraph] cannot afford four more loose parameters (see its `pendingChatOpen`
 * doc). They stay *distinct fields* because the codes themselves are indistinguishable: six
 * characters from the same generator, redeemable by two different callables, and the host the
 * link arrived on is the only thing that says which. Collapsing them into one field would
 * throw away that answer.
 *
 * @property pairing A `coplanly://pair` code, or null when none is outstanding. Empty string
 *   means "link present, no code" — see `MainActivity.readPairingCode`.
 * @property onPairingConsumed Clears [pairing] once the pairing screen has it.
 * @property guest A `coplanly://guest` code, or null when none is outstanding. Never empty: a
 *   bare guest link is ignored rather than opening an empty screen.
 * @property onGuestConsumed Clears [guest] once the guest-accept screen has it.
 */
class PendingInviteCodes(
    val pairing: StateFlow<String?>,
    val onPairingConsumed: () -> Unit,
    val guest: StateFlow<String?>,
    val onGuestConsumed: () -> Unit
)

/**
 * Navigates to the guest-accept screen when a `coplanly://guest` link is pending.
 *
 * Same hand-off shape as [PairingDeepLinkEffect] and, deliberately, a separate effect
 * navigating to a separate route. Nothing here should be able to end at the pairing screen.
 */
@Composable
private fun GuestDeepLinkEffect(
    pendingInviteCodes: PendingInviteCodes,
    isAuthenticated: Boolean?,
    navController: NavHostController
) {
    val guestCode by pendingInviteCodes.guest.collectAsState()
    LaunchedEffect(guestCode, isAuthenticated) {
        if (guestCode != null && isAuthenticated == true) {
            navController.navigate(Screen.GuestAccept.routeWithCode(guestCode))
            pendingInviteCodes.onGuestConsumed()
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

    /**
     * The first-run questionnaire. Deliberately absent from
     * [BottomNavDestination.topLevelRoutes]: the bottom bar hides itself for any route not
     * listed there, which is exactly what a wizard wants.
     */
    data object Onboarding : Screen("onboarding")
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
    data object Pets : Screen("pets")
    data object Pairing : Screen("pairing?code={code}") {
        /** Optional invite code carried by a `coplanly://pair` deep link. */
        const val ARG_CODE = "code"

        /** Builds the route, with [code] pre-filled when a deep link supplied one. */
        fun routeWithCode(code: String?): String =
            if (code.isNullOrEmpty()) "pairing" else "pairing?code=$code"
    }
    /**
     * Redeeming a guest invitation — a separate route from [Pairing], mirroring the two
     * separate callables behind them. Nothing about a guest belongs on a screen whose other
     * outcome is a co-parent link.
     */
    data object GuestAccept : Screen("guest_accept?code={code}") {
        /** Optional invite code carried by a `coplanly://guest` deep link. */
        const val ARG_CODE = "code"

        /** Builds the route, with [code] pre-filled when a deep link supplied one. */
        fun routeWithCode(code: String?): String =
            if (code.isNullOrEmpty()) "guest_accept" else "guest_accept?code=$code"
    }

    data object CustodySetup : Screen("custody_setup")

    /** The signed-in user's own profile — editable. */
    data object MyProfile : Screen("my_profile")

    /** The co-parent's profile — read-only, `firestore.rules` refuses the write anyway. */
    data object CoParentProfile : Screen("coparent_profile")

    /**
     * The pairing conflict screen. Reached only from an accepted pairing that found two
     * disagreeing custody patterns; the two patterns themselves travel in
     * `PendingCustodyConflict`, not in the route — no route argument could carry them, and
     * re-deriving them here would race the shared-custody mirror.
     */
    data object CustodyConflict : Screen("custody_conflict")

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

    data object EditPet : Screen("edit_pet/{petId}") {
        const val ARG_PET_ID = "petId"

        fun createRoute(petId: String): String {
            return "edit_pet/$petId"
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

    /**
     * Important phone numbers, one tap from the dialler.
     *
     * A detail screen, not in [BottomNavDestination.topLevelRoutes]: the bottom bar hides and
     * an up-arrow appears, the same as every other screen reached from a row rather than a tab.
     */
    data object Contacts : Screen("contacts")

    data object ChangeRequests : Screen("change_requests?eventId={eventId}") {
        const val ARG_EVENT_ID = "eventId"

        /** @param eventId Event whose request should be highlighted, or null for the plain inbox. */
        fun createRoute(eventId: String? = null): String =
            "change_requests?eventId=${eventId ?: "null"}"
    }
    /**
     * The change-request form.
     *
     * It used to carry the conversation it was opened from, because the chat card was posted only
     * when one was supplied — so a change proposed from the calendar reached the thread not at
     * all. `ActivityAnnouncer` resolves the pair's thread from the two uids itself, so the
     * argument had nothing left to do and is gone rather than left as dead weight.
     */
    data object RequestChange : Screen("request_change/{eventId}") {
        const val ARG_EVENT_ID = "eventId"

        fun createRoute(eventId: String): String = "request_change/$eventId"
    }
}
