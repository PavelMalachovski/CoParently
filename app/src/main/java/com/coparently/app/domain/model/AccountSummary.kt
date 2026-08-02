package com.coparently.app.domain.model

/**
 * The account the app is currently signed in as, reduced to what the UI shows.
 *
 * Deliberately not [User]: nothing on screen needs the role, the colour code or the
 * calendar-sync settings, and passing the whole profile around invites a view to read a
 * field it has no business rendering. It mirrors [PartnerSummary] — the same three
 * identity fields for the other parent — so the "you" and "your co-parent" blocks can
 * share one avatar treatment.
 *
 * @property id Firebase UID of the signed-in account
 * @property name Resolved display name; falls back to the email local part, never blank
 *   unless [email] is blank too (in which case there is nothing worth showing at all)
 * @property email Email address of the account, possibly blank
 * @property photoUrl Remote avatar (Google sign-in provides one), or null
 */
data class AccountSummary(
    val id: String,
    val name: String,
    val email: String,
    val photoUrl: String? = null
)
