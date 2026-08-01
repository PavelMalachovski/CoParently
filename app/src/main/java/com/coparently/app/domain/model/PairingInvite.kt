package com.coparently.app.domain.model

/**
 * An outstanding invitation to become co-parents.
 *
 * The same document backs the short code, the QR image and the share link —
 * [code] is what all three carry.
 *
 * @property id Firestore document id
 * @property code Short code the other parent types or scans
 * @property fromUserId Firebase UID of the inviter
 * @property fromUserName Display name of the inviter
 * @property fromUserEmail Email of the inviter
 * @property toEmail Addressee for email invites; empty for code/QR/link invites
 * @property expiresAtMillis Epoch millis after which the invite is refused
 */
data class PairingInvite(
    val id: String,
    val code: String,
    val fromUserId: String,
    val fromUserName: String,
    val fromUserEmail: String,
    val toEmail: String = "",
    val expiresAtMillis: Long
)
