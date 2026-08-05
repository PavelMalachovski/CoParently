package com.coparently.app.presentation.common

import com.coparently.app.domain.model.User

/**
 * The label for a parent slot: that person's name.
 *
 * `"mom"` and `"dad"` are slot identifiers, not roles — nobody chooses them and no screen
 * shows them. Every surface that names a parent resolves it here, so the app can never say
 * "Mom" in one place and a name in another, and so families the mom/dad model does not
 * describe are not told who they are.
 *
 * The fallbacks arrive already resolved because this function is pure and cannot call
 * `stringResource`; composables resolve them in composable scope and pass them down, the same
 * way `CalendarScreen` resolves its snackbar strings before the effect that uses them.
 *
 * @param slot The stored slot identifier, typically `Event.parentOwner`.
 * @param me The signed-in user, or null before the profile has loaded.
 * @param coParent The paired co-parent, or null when unpaired.
 * @param youFallback Shown for my own slot when no name is stored.
 * @param coParentFallback Shown for the other slot when there is no co-parent or no name.
 */
fun parentLabel(
    slot: String,
    me: User?,
    coParent: User?,
    youFallback: String,
    coParentFallback: String
): String {
    val myRole = me?.role ?: "mom"
    val coParentRole = coParent?.role ?: "dad"

    return when (slot) {
        myRole -> me?.name?.trim()?.ifBlank { null } ?: youFallback
        coParentRole -> coParent?.name?.trim()?.ifBlank { null } ?: coParentFallback
        else -> coParentFallback
    }
}
