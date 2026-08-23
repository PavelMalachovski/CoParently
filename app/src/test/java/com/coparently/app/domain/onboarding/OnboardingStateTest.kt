package com.coparently.app.domain.onboarding

import com.coparently.app.domain.model.User
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a parent should be shown the first-run questionnaire.
 *
 * The case that matters most is the third one. Every existing installation upgrades into this
 * code with `onboardingCompletedAt` null, and handing a questionnaire to someone who has been
 * using the app for months — asking them for a child they already entered — would be the most
 * visible possible regression. An account that already has a name and a child is complete by
 * evidence, whatever the marker says.
 */
class OnboardingStateTest {

    private fun user(name: String = "Olya", completedAt: String? = null) = User(
        id = "u1",
        email = "olya@example.com",
        name = name,
        role = "mom",
        colorCode = "#FF4081",
        onboardingCompletedAt = completedAt
    )

    @Test
    fun `a brand new account is asked`() {
        assertTrue(OnboardingState.isNeeded(user(name = ""), hasChildInfo = false))
    }

    @Test
    fun `an account that finished is never asked again`() {
        assertFalse(
            OnboardingState.isNeeded(
                user(completedAt = "2026-08-23T09:00:00"),
                hasChildInfo = false
            )
        )
    }

    @Test
    fun `a blank marker does not count as finished`() {
        // The outbound Firestore map writes "" rather than omitting the key, and `toUser`
        // reads it back through `takeIf { it.isNotBlank() }` — but a document written by an
        // older build, or a hand-edited one, can still land here as whitespace.
        assertTrue(OnboardingState.isNeeded(user(completedAt = "   "), hasChildInfo = false))
    }

    @Test
    fun `an existing installation with real data is not ambushed on upgrade`() {
        // The marker is null because this column did not exist when they signed up.
        assertFalse(OnboardingState.isNeeded(user(), hasChildInfo = true))
    }

    @Test
    fun `a named account with no child is still asked`() {
        // Named but childless: they signed in and stopped. The wizard is the point.
        assertTrue(OnboardingState.isNeeded(user(), hasChildInfo = false))
    }

    @Test
    fun `a child but no name is still asked, because the name is the one required field`() {
        assertTrue(OnboardingState.isNeeded(user(name = "  "), hasChildInfo = true))
    }

    @Test
    fun `a null user is not asked, because there is nobody to ask`() {
        // Sign-out and cold-start races both land here. Showing a wizard to nobody would
        // strand the app on a screen with no account behind it.
        assertFalse(OnboardingState.isNeeded(null, hasChildInfo = false))
    }
}
