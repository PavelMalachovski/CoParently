package com.coparently.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.coparently.app.presentation.calendar.CalendarScreen
import com.coparently.app.presentation.event.AddEditEventScreen
import com.coparently.app.presentation.event.EventListScreen

/**
 * Navigation graph for the app.
 * Defines all navigation routes and their destinations.
 */
@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Calendar.route
    ) {
        composable(Screen.Calendar.route) {
            CalendarScreen(
                onEventClick = { eventId ->
                    navController.navigate(Screen.EditEvent.createRoute(eventId))
                },
                onAddEventClick = {
                    navController.navigate(Screen.AddEvent.route)
                }
            )
        }

        composable(Screen.EventList.route) {
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

        composable(Screen.AddEvent.route) {
            AddEditEventScreen(
                eventId = null,
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
            )
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString(Screen.EditEvent.ARG_EVENT_ID) ?: return@composable
            AddEditEventScreen(
                eventId = eventId,
                onSave = {
                    navController.popBackStack()
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }
    }
}

/**
 * Sealed class representing all navigation screens.
 */
sealed class Screen(val route: String) {
    data object Calendar : Screen("calendar")
    data object EventList : Screen("event_list")
    data object AddEvent : Screen("add_event")

    data object EditEvent : Screen("edit_event/{eventId}") {
        const val ARG_EVENT_ID = "eventId"

        fun createRoute(eventId: String): String {
            return "edit_event/$eventId"
        }
    }
}

