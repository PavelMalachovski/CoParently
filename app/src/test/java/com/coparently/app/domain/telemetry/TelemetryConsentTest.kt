package com.coparently.app.domain.telemetry

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consent rule (REL-5).
 *
 * Worth testing precisely because being wrong here is invisible: nothing on the screen changes,
 * no test fails, and the only symptom is data leaving a device whose owner said no. Before the
 * gate existed the app had exactly that shape — `BuildConfig.ENABLE_CRASHLYTICS` was applied in
 * `FirebaseModule` and then overruled by an unconditional
 * `setCrashlyticsCollectionEnabled(true)` in `CoPlanlyApplication.onCreate`, and no test noticed
 * for the life of the flag.
 */
class TelemetryConsentTest {

    @Test
    fun `nothing is collected without an explicit yes`() {
        assertFalse(telemetryCollectionEnabled(TelemetryConsent.UNANSWERED, buildAllows = true))
        assertFalse(telemetryCollectionEnabled(TelemetryConsent.DENIED, buildAllows = true))
        assertTrue(telemetryCollectionEnabled(TelemetryConsent.GRANTED, buildAllows = true))
    }

    @Test
    fun `a granted consent cannot switch a debug build back on`() {
        // The build flag says which *project* may receive data, and a developer's install must
        // stay out of the one real families report into — whatever that developer tapped on the
        // consent screen while testing it.
        assertFalse(telemetryCollectionEnabled(TelemetryConsent.GRANTED, buildAllows = false))
    }

    @Test
    fun `a release build cannot stand in for an answer`() {
        // The other direction of the same rule: `true` is permission from the build, never from
        // the user, and this is the assertion that fails if somebody later "simplifies" the AND.
        TelemetryConsent.entries
            .filter { it != TelemetryConsent.GRANTED }
            .forEach { assertFalse(telemetryCollectionEnabled(it, buildAllows = true), "$it") }
    }

    @Test
    fun `an unreadable stored value reads as never asked, not as agreed`() {
        assertEquals(TelemetryConsent.UNANSWERED, TelemetryConsent.fromStored(null))
        assertEquals(TelemetryConsent.UNANSWERED, TelemetryConsent.fromStored(""))
        assertEquals(TelemetryConsent.UNANSWERED, TelemetryConsent.fromStored("true"))
        assertEquals(TelemetryConsent.UNANSWERED, TelemetryConsent.fromStored("granted"))
    }

    @Test
    fun `every answer survives a round trip through storage`() {
        // The stored form is the enum name rather than its ordinal, so reordering the enum cannot
        // silently turn one stored answer into another.
        TelemetryConsent.entries.forEach { consent ->
            assertEquals(consent, TelemetryConsent.fromStored(consent.stored))
        }
    }
}
