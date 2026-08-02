package com.coparently.app.data.session

/**
 * How the signed-in user's display identity is derived from the several places it can
 * come from: Firebase Auth, the Firestore profile document, and the local Room row.
 *
 * Shared by the write path (`UserRepositoryImpl.ensureProfile`, driven off the auth-state
 * boundary by [SessionProfileSynchronizer]) and the read path
 * ([SignedInAccountSource], which renders "signed in as"). Keeping one implementation is
 * the point: if the two disagreed, the name shown on this phone and the name the co-parent
 * reads out of Firestore could differ, which is exactly the confusion this screen exists
 * to remove.
 *
 * The shape of both rules is the same — take the strongest source that actually has a
 * value, and never let a session that happens to know less overwrite something better that
 * is already stored.
 */
internal object ProfileIdentity {

    /**
     * Picks the best available display name, or null when there is nothing usable.
     *
     * Order, strongest first:
     *
     * 1. the Firebase Auth `displayName` — the authoritative identity, and the only source
     *    that self-heals once a Google account starts providing one;
     * 2. the name already stored remotely, then locally — this is the rung that matters for
     *    email/password accounts, where `displayName` is always null and step 3 must not be
     *    allowed to demote a real name to the email local part;
     * 3. the local part of the email address, the same last resort the pre-rewrite pairing
     *    screen used.
     *
     * @param displayName Name Firebase Auth reports for this session, if any
     * @param storedRemoteName Name currently in the Firestore profile document
     * @param storedLocalName Name currently in the local Room row
     * @param email Email address to derive a last-resort name from
     */
    fun resolveName(
        displayName: String?,
        storedRemoteName: String?,
        storedLocalName: String?,
        email: String?
    ): String? = displayName?.nonBlank()
        ?: storedRemoteName?.nonBlank()
        ?: storedLocalName?.nonBlank()
        ?: email?.substringBefore("@")?.nonBlank()

    /**
     * Picks the best available avatar URL, or null when the account has none.
     *
     * Same preference order and the same no-downgrade guarantee as [resolveName]: Firebase
     * Auth populates `photoUrl` for Google sign-in but never for an email/password account,
     * so a session that reports none must fall back to the stored value rather than clear
     * it. Callers must therefore treat a null result as "leave whatever is stored alone",
     * not as "the user removed their photo".
     *
     * @param authPhotoUrl `FirebaseUser.photoUrl` for this session, as a string
     * @param storedRemoteUrl `profilePhotoUrl` currently in the Firestore profile document
     * @param storedLocalUrl `profilePhotoUrl` currently in the local Room row
     */
    fun resolvePhotoUrl(
        authPhotoUrl: String?,
        storedRemoteUrl: String?,
        storedLocalUrl: String?
    ): String? = authPhotoUrl?.nonBlank()
        ?: storedRemoteUrl?.nonBlank()
        ?: storedLocalUrl?.nonBlank()

    /** This string unless it is blank, in which case null. */
    private fun String.nonBlank(): String? = takeIf { it.isNotBlank() }
}
