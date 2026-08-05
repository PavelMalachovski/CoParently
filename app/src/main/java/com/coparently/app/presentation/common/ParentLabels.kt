package com.coparently.app.presentation.common

import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User

/**
 * A parent reduced to the two facts a label needs: the slot they hold, and what to call them.
 *
 * Deliberately not [User]. The signed-in parent has a real [User] row, but the co-parent does
 * not — this device only ever stores its own — and all it knows about them is the
 * [PartnerSummary] the pairing listener reads out of their Firestore document. Taking [User]
 * here would have forced that summary to be inflated into a `User` with an invented colour,
 * token and sync flags, and a fabricated domain object is the kind of thing that eventually
 * gets persisted by accident. A projection that cannot be mistaken for a stored profile is
 * cheaper and safer than a convincing fake.
 *
 * @property uid Firebase uid of the parent, so a payer can also be attributed by id.
 * @property slot The stored slot identifier, `"mom"` or `"dad"`. Never shown to anyone.
 * @property name Their display name, which may be blank when their profile carries none.
 */
data class NamedParent(
    val uid: String,
    val slot: String,
    val name: String
)

/** This device's own parent, projected for labelling. */
fun User.asNamedParent(): NamedParent = NamedParent(uid = id, slot = role, name = name)

/**
 * The co-parent, projected for labelling — or null when their slot is not known.
 *
 * [PartnerSummary.role] is null until their `users/{uid}` document carries one, which is the
 * case for every pair created before slot assignment shipped. Returning null there is the
 * point: without a slot the person cannot be matched to anything, and [parentLabel] then says
 * so rather than assuming they hold whichever slot is left over.
 */
fun PartnerSummary.asNamedParent(): NamedParent? =
    role?.let { NamedParent(uid = id, slot = it, name = name) }

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
