package com.coparently.app.domain.guests

/**
 * An outstanding offer of guest access to one child's record.
 *
 * Shares the `invitations` collection with a co-parent invitation and is told apart from one
 * by the document's `kind` field. The two are **not** interchangeable: redeeming this through
 * the pairing callable would run `assignSlots` and write `partnerId`, turning a grandmother
 * into a co-parent — so `acceptPairingInvitation` refuses a document carrying this kind, and
 * `acceptGuestInvitation` refuses one that does not.
 *
 * Two expiries, which are genuinely different things and must not be collapsed:
 *
 * @property id Firestore document id
 * @property code Short code the guest types or scans, the same six characters a co-parent
 *   invitation uses — it is the document behind it that differs, not the code
 * @property childInfoId The one child record this opens. Exactly one, always: a guest is
 *   invited to a child, not to a family
 * @property inviteExpiresAtMillis When the **offer** stops being redeemable. Short, like a
 *   co-parent code's
 * @property grantExpiresAtMillis When the **access** ends, once redeemed. Chosen by the
 *   parent at invite time and stamped on the grant verbatim, so a guest who redeems late
 *   gets less time rather than a fresh window. Never absent and never zero: an invitation
 *   with no end to the access is refused by the create rule and by the callable both
 */
data class GuestInvite(
    val id: String,
    val code: String,
    val childInfoId: String,
    val inviteExpiresAtMillis: Long,
    val grantExpiresAtMillis: Long
)
