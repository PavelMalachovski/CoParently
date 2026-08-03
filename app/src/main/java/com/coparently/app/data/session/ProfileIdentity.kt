package com.coparently.app.data.session

/**
 * How the signed-in user's display identity is derived from the several places it can
 * come from: Firebase Auth's top-level fields, `FirebaseUser.providerData`, the Firestore
 * profile document, and the local Room row.
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
 * is already stored. `providerData` sits deliberately low in that order: it exists to fill
 * a gap in the top-level fields (a real, non-anonymous Google session whose account record
 * predates linking the provider carries the name/email/photo only under `providerData`,
 * not at the top level), not to compete with a value this app already has stored.
 */
internal object ProfileIdentity {

    /** Firebase's fixed provider constant for the synthetic entry every account carries. */
    private const val SYNTHETIC_PROVIDER_ID = "firebase"

    /**
     * The provider [bestProvider] prefers when more than one real provider is linked.
     *
     * This app's only first-class sign-in method today is Google, and `google.com` is the
     * one entry in `providerData` that is contractually guaranteed to carry a display name,
     * an email and a photo together. An email/password entry (`password`) never carries a
     * name or a photo at all, so if both are ever linked to the same account, preferring
     * Google is not a coin flip — it is the only one of the two that can fill the gap this
     * fallback exists for.
     */
    private const val PREFERRED_PROVIDER_ID = "google.com"

    /**
     * A plain-string projection of one `FirebaseUser.providerData` entry (`UserInfo`).
     *
     * Kept string-only, like the rest of this file, so `resolveName`/`resolvePhotoUrl`/
     * `resolveEmail` stay pure and JVM-testable without touching `android.net.Uri` or any
     * other Firebase/Android type. Callers (`UserRepositoryImpl`, `SignedInAccountSource`)
     * do the projection from the real `UserInfo`.
     *
     * @param providerId Firebase's fixed provider constant (`google.com`, `password`, the
     *   synthetic `firebase`, …)
     * @param displayName This provider's reported display name, if any
     * @param email This provider's reported email address, if any
     * @param photoUrl This provider's reported avatar URL, if any
     */
    data class ProviderIdentity(
        val providerId: String,
        val displayName: String?,
        val email: String?,
        val photoUrl: String?
    )

    /**
     * Picks the best available display name, or null when there is nothing usable.
     *
     * Order, strongest first:
     *
     * 1. the Firebase Auth `displayName` — the authoritative identity, and the only source
     *    that self-heals once a Google account starts providing one;
     * 2. the name already stored remotely, then locally — this is the rung that matters for
     *    email/password accounts, where `displayName` is always null and step 4 must not be
     *    allowed to demote a real name to the email local part;
     * 3. the name reported by the best linked provider in [providers] — this is the rung
     *    that matters when the account record predates linking the provider, so the
     *    top-level field stays empty even though the account is a genuine, non-anonymous
     *    Google session. It sits *after* the stored name so a session that merely lost track
     *    of what a linked provider reports can never demote a name the user already has, but
     *    *before* the email guess so it wins whenever it has something to offer;
     * 4. the local part of the email address, the same last resort the pre-rewrite pairing
     *    screen used.
     *
     * @param displayName Name Firebase Auth reports for this session, if any
     * @param storedRemoteName Name currently in the Firestore profile document
     * @param storedLocalName Name currently in the local Room row
     * @param email Email address to derive a last-resort name from
     * @param providers This session's linked sign-in providers, synthetic `firebase` entry
     *   included — [bestProvider] is the one place that filters it out
     */
    fun resolveName(
        displayName: String?,
        storedRemoteName: String?,
        storedLocalName: String?,
        email: String?,
        providers: List<ProviderIdentity>
    ): String? = displayName?.nonBlank()
        ?: storedRemoteName?.nonBlank()
        ?: storedLocalName?.nonBlank()
        ?: bestProvider(providers)?.displayName?.nonBlank()
        ?: email?.substringBefore("@")?.nonBlank()

    /**
     * Picks the best available email address, or null when there is nothing usable.
     *
     * Same shape as [resolveName] minus the final guess rung (there is nothing softer to
     * fall back to for an email): the top-level session value first, then the stored values,
     * then the best linked provider's — so a provider's email can fill a gap but can never
     * override a real address that is already stored.
     *
     * @param topLevelEmail `FirebaseUser.email` for this session, if any
     * @param storedRemoteEmail Email currently in the Firestore profile document
     * @param storedLocalEmail Email currently in the local Room row
     * @param providers This session's linked sign-in providers, synthetic `firebase` entry
     *   included
     */
    fun resolveEmail(
        topLevelEmail: String?,
        storedRemoteEmail: String?,
        storedLocalEmail: String?,
        providers: List<ProviderIdentity>
    ): String? = topLevelEmail?.nonBlank()
        ?: storedRemoteEmail?.nonBlank()
        ?: storedLocalEmail?.nonBlank()
        ?: bestProvider(providers)?.email?.nonBlank()

    /**
     * Picks the best available avatar URL, or null when the account has none.
     *
     * Same preference order and the same no-downgrade guarantee as [resolveName]: Firebase
     * Auth populates `photoUrl` for Google sign-in but never for an email/password account,
     * so a session that reports none must fall back to the stored value, then to the best
     * linked provider's, rather than clear it. Callers must therefore treat a null result as
     * "leave whatever is stored alone", not as "the user removed their photo".
     *
     * @param authPhotoUrl `FirebaseUser.photoUrl` for this session, as a string
     * @param storedRemoteUrl `profilePhotoUrl` currently in the Firestore profile document
     * @param storedLocalUrl `profilePhotoUrl` currently in the local Room row
     * @param providers This session's linked sign-in providers, synthetic `firebase` entry
     *   included
     */
    fun resolvePhotoUrl(
        authPhotoUrl: String?,
        storedRemoteUrl: String?,
        storedLocalUrl: String?,
        providers: List<ProviderIdentity>
    ): String? = authPhotoUrl?.nonBlank()
        ?: storedRemoteUrl?.nonBlank()
        ?: storedLocalUrl?.nonBlank()
        ?: bestProvider(providers)?.photoUrl?.nonBlank()

    /**
     * The linked provider whose data [resolveName]/[resolveEmail]/[resolvePhotoUrl] fall
     * back to, or null when [providers] holds no real provider at all.
     *
     * The synthetic `firebase` entry every account carries is filtered out first — it is
     * bookkeeping, not an identity provider, and never carries a name, email or photo.
     * [PREFERRED_PROVIDER_ID] is preferred among what remains; otherwise the first real
     * provider Firebase reported wins, which keeps the choice deterministic without this app
     * needing to know about every provider Firebase might ever add.
     *
     * Not private: [UserRepositoryImpl][com.coparently.app.data.repository.UserRepositoryImpl]
     * reuses it to compute `hasProviderName` for [describeNameSources] without duplicating
     * the same selection logic a second time.
     */
    fun bestProvider(providers: List<ProviderIdentity>): ProviderIdentity? {
        val real = providers.filterNot { it.providerId == SYNTHETIC_PROVIDER_ID }
        return real.find { it.providerId == PREFERRED_PROVIDER_ID } ?: real.firstOrNull()
    }

    /**
     * A one-line, personal-data-free description of why [resolveName] returned null.
     *
     * When every rung of [resolveName] comes back empty the interesting question is *which*
     * of them were empty and what kind of session this is — a Google sign-in is supposed to
     * supply both a display name and an email address, so a session that supplies neither is
     * either not the provider the user believes it is, or an account whose profile scope was
     * never granted. The original log line said only "no name could be derived", which is
     * consistent with all of those and distinguishes none of them.
     *
     * [hasProviderName] is what keeps this line from going stale now that [resolveName]
     * itself falls back to `providerData`: before that fallback existed, a session with a
     * `google.com` entry but no top-level `displayName` was exactly the case this line was
     * written for. Now that same session resolves a name and never reaches this call at all
     * — so on its own, `providers=[google.com]` in a line that *did* get logged would read
     * exactly as it used to, when what it now means is strictly worse: the provider was
     * linked but its own `displayName` was also blank or missing. `hasProviderName` says
     * that in as many words instead of leaving it to be inferred from code the reader of a
     * logcat dump does not have open.
     *
     * Nothing here is personal data: every input is reported as a presence flag, and
     * `providerId` values are Firebase's fixed provider constants (`google.com`,
     * `password`, `firebase`, …), not account identifiers. The email address itself must
     * never appear — logcat is readable by anything with adb access, and a previous fix on
     * this branch removed exactly that leak from the pairing listener.
     *
     * @param hasDisplayName Whether Firebase Auth reported a non-blank top-level
     *   `displayName`
     * @param hasRemoteName Whether the Firestore profile document holds a non-blank name
     * @param hasLocalName Whether the local Room row holds a non-blank name
     * @param hasProviderName Whether the best real entry in `providerData` (see
     *   [bestProvider]) reported a non-blank display name of its own
     * @param hasEmail Whether any non-blank email address was available, including one
     *   sourced from `providerData`
     * @param hasRemoteProfile Whether a `users/{uid}` document could be read at all
     * @param isAnonymous Whether this is an anonymous Firebase session
     * @param providerIds `FirebaseUser.providerData` provider ids, in the order reported,
     *   synthetic `firebase` entry included
     */
    @Suppress("LongParameterList")
    fun describeNameSources(
        hasDisplayName: Boolean,
        hasRemoteName: Boolean,
        hasLocalName: Boolean,
        hasProviderName: Boolean,
        hasEmail: Boolean,
        hasRemoteProfile: Boolean,
        isAnonymous: Boolean,
        providerIds: List<String>
    ): String = "displayName=${presence(hasDisplayName)} " +
        "remoteName=${presence(hasRemoteName)} " +
        "localName=${presence(hasLocalName)} " +
        "providerName=${presence(hasProviderName)} " +
        "email=${presence(hasEmail)} " +
        "remoteProfile=${presence(hasRemoteProfile)} " +
        "anonymous=$isAnonymous " +
        "providers=[${providerIds.joinToString(",").ifEmpty { "none" }}]"

    /** "present" or "absent" — never the value itself. */
    private fun presence(has: Boolean): String = if (has) "present" else "absent"

    /** This string unless it is blank, in which case null. */
    private fun String.nonBlank(): String? = takeIf { it.isNotBlank() }
}
