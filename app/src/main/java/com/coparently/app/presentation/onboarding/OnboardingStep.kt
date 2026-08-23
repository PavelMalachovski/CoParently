package com.coparently.app.presentation.onboarding

/**
 * The wizard's steps, in the order the product brief lists them.
 *
 * [Custody] and [CoParent] do not render a form inside the wizard: they hand off to
 * `CustodySetupScreen` and `PairingScreen`, which already do those jobs and are reachable from
 * Settings anyway. They are steps here so the progress indicator tells the truth about how much
 * is left.
 */
enum class OnboardingStep {
    /** Explains what is about to be asked, and why. */
    Intro,

    /** The parent's own details. The only step with a required field. */
    Profile,

    /** The child's name, date of birth, allergies and medical profile. */
    Child,

    /** Emergency contacts, saved onto the child's record so both parents may edit them. */
    Relatives,

    /** Hands off to `CustodySetupScreen`. */
    Custody,

    /** Hands off to `PairingScreen`. Finishing here finishes onboarding. */
    CoParent;

    /**
     * True when this step may be left without answering it.
     *
     * [Intro] is excluded because it asks for nothing — there is nothing to skip past, only a
     * Next. [Profile] is excluded because the parent's name is the one field the app genuinely
     * cannot work without: every event, expense and custody day is labelled with it and
     * `ParentLabels` has no honest fallback. Everything else the wizard asks for, medical
     * details included, is collected for the parent's own benefit and must never become a gate
     * on their calendar.
     */
    val isSkippable: Boolean get() = this != Intro && this != Profile

    /** This step's 1-based position among the steps that carry a progress count. */
    val displayIndex: Int get() = ordinal + 1

    /** True for the step that ends the wizard; leaving it, by any button, finishes onboarding. */
    val isLast: Boolean get() = ordinal == OnboardingStep.entries.lastIndex

    companion object {
        /** How many steps the progress indicator counts. */
        val count: Int = OnboardingStep.entries.size
    }
}
