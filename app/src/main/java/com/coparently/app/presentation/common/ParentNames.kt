package com.coparently.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.coparently.app.R

/**
 * Everything a composable needs to turn a slot into a name: the two parents, and the three
 * fallbacks for when one of them cannot be named.
 *
 * This exists so a screen passes *one* value down its tree instead of five. Threading `me`,
 * `coParent` and three strings through `MonthView`, `DayWeekView`, `CustodyRibbon`,
 * `DayAgendaCard`, `ExpenseList` and the rest would be noise at every level, and — the reason
 * that matters — it is how one composable quietly ends up resolving labels from a different
 * pair than the sibling rendered next to it.
 *
 * The fallbacks are resolved by [rememberParentNames] in composable scope. A ViewModel must not
 * resolve them: it has no `Context` to do it with and must not acquire one just for this.
 */
@Immutable
data class ParentNames(
    val parents: Parents,
    val youFallback: String,
    val coParentFallback: String,
    val unknownFallback: String
) {
    /**
     * The name to show for [slot], or a fallback when that person cannot be identified.
     *
     * A null slot — an event with no owner, a day no custody model covers — is not a parent at
     * all, so it takes the same "cannot be identified" answer as an unrecognised one.
     */
    fun labelFor(slot: String?): String = if (slot == null) {
        unknownFallback
    } else {
        parentLabel(
            slot = slot,
            me = parents.me,
            coParent = parents.coParent,
            youFallback = youFallback,
            coParentFallback = coParentFallback,
            unknownFallback = unknownFallback
        )
    }
}

/**
 * Binds [parents] to the localized fallbacks, once per screen.
 *
 * @param parents The two parents, collected from the screen's ViewModel.
 */
@Composable
fun rememberParentNames(parents: Parents): ParentNames {
    val you = stringResource(R.string.parent_label_you)
    val coParent = stringResource(R.string.parent_label_coparent)
    val unknown = stringResource(R.string.parent_label_unknown)
    return remember(parents, you, coParent, unknown) {
        ParentNames(parents, you, coParent, unknown)
    }
}
