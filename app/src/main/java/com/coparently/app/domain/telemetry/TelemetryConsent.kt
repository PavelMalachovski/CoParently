package com.coparently.app.domain.telemetry

/**
 * Whether this person has agreed to analytics and crash reporting leaving their device.
 *
 * **Three states, because "said no" and "was never asked" are different facts.** Both mean the
 * same thing to the SDKs — collect nothing — but only one of them means the app still owes the
 * user a question. Collapsing them into a boolean is what turns a consent gate into a silent
 * default, which is the thing REL-5 exists to remove.
 *
 * **One decision covering both SDKs**, deliberately. A parent opening this app for the first time
 * is rarely in a state to weigh product analytics against crash diagnostics separately, and two
 * switches invite the reading that a crash report is somehow not personal data — it carries a
 * device model, an OS build, and a stack trace from a process holding a child's medical profile.
 * `docs/legal/DATA-SAFETY.md` declares them together for the same reason. If a lawyer asks for
 * granular consent later, this enum grows a second axis; nothing else in the design resists it.
 */
enum class TelemetryConsent {
    /** Never asked. Collect nothing, and show the consent screen. */
    UNANSWERED,

    /** Asked and agreed. */
    GRANTED,

    /** Asked and declined. Collect nothing, and never ask again unasked. */
    DENIED;

    /** The value written to storage. The name, not the ordinal — an ordinal shifts when the enum does. */
    val stored: String get() = name

    companion object {
        /**
         * Reads a stored value back, treating anything unrecognised as [UNANSWERED].
         *
         * An unreadable answer is not an answer, so the safe reading is the one that collects
         * nothing and asks again — never the one that assumes agreement.
         *
         * @param raw What storage returned, or null when nothing was stored.
         */
        fun fromStored(raw: String?): TelemetryConsent =
            entries.firstOrNull { it.name == raw } ?: UNANSWERED
    }
}

/**
 * Whether telemetry may actually be collected right now.
 *
 * Kept as a pure function, out of both the Android SDKs and Compose, so the rule can be unit
 * tested — it is the one piece of this feature where being wrong is invisible until somebody
 * inspects network traffic.
 *
 * **Consent and the build flag are ANDed, and neither can override the other.**
 * `BuildConfig.ENABLE_ANALYTICS` / `ENABLE_CRASHLYTICS` are false for debug builds so that a
 * developer's install and every instrumented run stay out of the production project; a granted
 * consent must not defeat that. Equally, a release build's `true` is a statement about *which
 * project* may receive data, never a statement that a user agreed.
 *
 * @param consent What the user answered.
 * @param buildAllows The build type's own flag.
 */
fun telemetryCollectionEnabled(consent: TelemetryConsent, buildAllows: Boolean): Boolean =
    buildAllows && consent == TelemetryConsent.GRANTED
