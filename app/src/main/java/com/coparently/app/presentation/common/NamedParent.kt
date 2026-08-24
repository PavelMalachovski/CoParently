package com.coparently.app.presentation.common

import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.model.User

/**
 * A parent reduced to the two facts a label needs: the slot they hold, and what to call them.
 *
 * Deliberately not [User]. The signed-in parent has a real [User] row, but the co-parent does
 * not — this device only ever stores its own — and all it knows about them is the
 * [PartnerSummary] the pairing listener reads out of their Firestore document. Taking [User]
 * in [parentLabel] would have forced that summary to be inflated into a `User` with an invented
 * colour, token and sync flags, and a fabricated domain object is the kind of thing that
 * eventually gets persisted by accident. A projection that cannot be mistaken for a stored
 * profile is cheaper and safer than a convincing fake.
 *
 * @property uid Firebase uid of the parent, so a payer can also be attributed by id.
 * @property slot The stored slot identifier, `"mom"` or `"dad"`. Never shown to anyone.
 * @property name Their display name, which may be blank when their profile carries none.
 * @property photoUrl Their avatar, which for a Google sign-in is the account's own picture —
 *   `ProfileIdentity.resolvePhotoUrl` puts it there and `users/{uid}.profilePhotoUrl` carries it
 *   to the co-parent. Null for an email/password account, for a Google account with no picture,
 *   and for a co-parent whose phone has not yet run a build that stores one, so the
 *   initial-letter fallback in `AccountAvatar` stays load-bearing rather than decorative.
 */
data class NamedParent(
    val uid: String,
    val slot: String,
    val name: String,
    val photoUrl: String? = null
)

/** This device's own parent, projected for labelling. */
fun User.asNamedParent(): NamedParent =
    NamedParent(uid = id, slot = role, name = name, photoUrl = profilePhotoUrl)

/**
 * The co-parent, projected for labelling — or null when their slot is not known.
 *
 * [PartnerSummary.role] is null until their `users/{uid}` document carries one, which is the
 * case for every pair created before slot assignment shipped. Returning null there is the
 * point: without a slot the person cannot be matched to anything, and [parentLabel] then says
 * so rather than assuming they hold whichever slot is left over.
 */
fun PartnerSummary.asNamedParent(): NamedParent? =
    role?.let { NamedParent(uid = id, slot = it, name = name, photoUrl = photoUrl) }
