package com.coparently.app.presentation.common

/**
 * The label for a parent slot: that person's name.
 *
 * `"mom"` and `"dad"` are slot identifiers, not roles — nobody chooses them and no screen
 * shows them. Every surface that names a parent resolves it here, so the app can never say
 * "Mom" in one place and a name in another, and so families the mom/dad model does not
 * describe are not told who they are.
 *
 * When a parent cannot be identified (their record is null, or the slot matches neither
 * parent), the function returns `unknownFallback`. It never guesses a slot: an unloaded
 * profile or an invalid slot identifier is a fact to report, not a coin to flip. This ensures
 * that after a cold start, before profiles load, the calendar shows "Parent" instead of
 * inverting the names by assuming who is who. The same holds for a pair whose two parents
 * still occupy the same slot — their co-parent reads as "Parent" until the backfill separates
 * them, which is the honest answer while the two genuinely cannot be told apart.
 *
 * The fallbacks arrive already resolved because this function is pure and cannot call
 * `stringResource`; composables resolve them in composable scope and pass them down, the same
 * way `CalendarScreen` resolves its snackbar strings before the effect that uses them. In
 * practice a screen holds a [ParentNames] rather than calling this directly.
 *
 * @param slot The stored slot identifier, typically `Event.parentOwner`.
 * @param me The signed-in parent, or null before the profile has loaded.
 * @param coParent The paired co-parent, or null when unpaired or their slot is unknown.
 * @param youFallback Shown for my own slot when no name is stored.
 * @param coParentFallback Shown for the other slot when there is no co-parent or no name.
 * @param unknownFallback Shown when the parent cannot be identified (slot matches neither).
 */
fun parentLabel(
    slot: String,
    me: NamedParent?,
    coParent: NamedParent?,
    youFallback: String,
    coParentFallback: String,
    unknownFallback: String
): String = when (slot) {
    me?.slot -> me.name.trim().ifBlank { youFallback }
    coParent?.slot -> coParent.name.trim().ifBlank { coParentFallback }
    else -> unknownFallback
}
