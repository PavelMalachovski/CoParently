package com.coparently.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.coparently.app.R

/**
 * Top-level destinations reachable from the bottom navigation bar.
 *
 * @property route Route *pattern* of the destination, used to match the current entry
 * @property navRoute Concrete route to navigate to. Differs from [route] where the pattern
 *   carries optional arguments — navigating to a pattern would pass `{placeholder}` through as
 *   a literal value.
 * @property labelRes Label string resource
 * @property selectedIcon Icon when the destination is selected
 * @property unselectedIcon Icon when the destination is not selected
 */
enum class BottomNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val navRoute: String = route
) {
    HOME(
        route = Screen.Home.route,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    CALENDAR(
        route = Screen.Calendar.route,
        labelRes = R.string.nav_calendar,
        selectedIcon = Icons.Filled.CalendarMonth,
        unselectedIcon = Icons.Outlined.CalendarMonth
    ),
    CHAT(
        route = Screen.Conversations.route,
        labelRes = R.string.nav_chat,
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
        // The pattern carries an optional draft argument; the tab opens the plain list.
        navRoute = Screen.Conversations.createRoute()
    ),
    EXPENSES(
        route = Screen.Expenses.route,
        labelRes = R.string.nav_expenses,
        selectedIcon = Icons.Filled.Payments,
        unselectedIcon = Icons.Outlined.Payments
    );

    // Settings is intentionally NOT a bottom-nav destination — it is reached via a
    // gear action in the top bar of the top-level screens and opens as a detail
    // screen (with a back arrow, bottom bar hidden).

    companion object {
        /** Routes on which the bottom bar is visible. */
        val topLevelRoutes: Set<String> = entries.mapTo(mutableSetOf()) { it.route }
    }
}

/**
 * Material 3 bottom navigation bar with the four top-level destinations.
 *
 * @param currentRoute Route of the currently displayed destination
 * @param onNavigate Callback invoked with the destination the user tapped
 */
@Composable
fun CoPlanlyBottomBar(
    currentRoute: String?,
    onNavigate: (BottomNavDestination) -> Unit
) {
    NavigationBar {
        BottomNavDestination.entries.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = null
                    )
                },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}
