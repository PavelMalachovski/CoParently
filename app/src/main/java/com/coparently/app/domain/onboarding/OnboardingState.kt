package com.coparently.app.domain.onboarding

import com.coparently.app.domain.model.User

/**
 * Decides whether a parent should be walked through the first-run questionnaire.
 *
 * Kept in the domain layer and free of Android because it gates the app's start destination:
 * getting it wrong shows a questionnaire to a long-standing user, or hides it from a new one,
 * and neither should depend on a device to test.
 */
object OnboardingState {

    /**
     * Whether the wizard should run for this account.
     *
     * @param user The signed-in user, or null before the profile has loaded
     * @param hasChildInfo Whether this account has at least one child record
     * @return true when the wizard should run
     */
    fun isNeeded(user: User?, hasChildInfo: Boolean): Boolean {
        if (user == null) return false
        if (!user.onboardingCompletedAt.isNullOrBlank()) return false

        // Complete by evidence. Every installation that predates this column upgrades with a
        // null marker; an account that already carries a name and a child has plainly been
        // through this once, whatever the marker says.
        val named = user.name.isNotBlank()
        return !(named && hasChildInfo)
    }
}
