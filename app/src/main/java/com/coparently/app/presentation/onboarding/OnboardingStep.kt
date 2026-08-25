package com.coparently.app.presentation.onboarding

import com.coparently.app.domain.model.FamilyKind

/**
 * The wizard's steps, in the order the product brief lists them.
 *
 * [Custody] and [CoParent] do not render a form inside the wizard: they hand off to
 * `CustodySetupScreen` and `PairingScreen`, which already do those jobs and are reachable from
 * Settings anyway. They are steps here so the progress indicator tells the truth about how much
 * is left.
 *
 * **Not every step runs.** [Family] asks whether the family is co-parenting children, pets or
 * both, and the answer decides which of [Child], [Relatives] and [Pet] follow. The wizard used
 * to walk this enum by `ordinal ± 1` with `entries.size` as the progress denominator, which is
 * exactly what a conditional flow cannot do — see [stepsFor], which is the list to walk instead.
 */
enum class OnboardingStep {
    /** Explains what is about to be asked, and why. */
    Intro,

    /** Children, pets, or both. Decides which of the record steps below are asked. */
    Family,

    /** The parent's own details. The only step with a required field. */
    Profile,

    /**
     * Each child's name, date of birth, allergies and medical profile.
     *
     * A repeatable list, not one child: the wizard used to write exactly one record, so a
     * family with two could not say so here at all.
     */
    Child,

    /**
     * Emergency contacts, saved onto a child's record so both parents may edit them.
     *
     * They belong to **one** child, and with several the step asks which. A single flat list
     * filed every contact against whichever child was written first.
     */
    Relatives,

    /** Each pet's name and species, repeatable on the same terms as [Child]. */
    Pet,

    /**
     * How a shared expense divides between the two parents.
     *
     * Here rather than only in Settings because the reporter asked for it at registration, and
     * because it is genuinely easier to agree before there is a month of expenses to re-argue.
     * Nobody has to confirm it yet: pairing is the last step, so at this point there is no
     * co-parent, and `FamilySettingsRepository.submitRatio` applies it outright.
     */
    Split,

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
     * `ParentLabels` has no honest fallback. [Family] is excluded because skipping it would
     * leave the wizard unable to decide which steps come next — it opens pre-answered with
     * children, so there is always something to move on with.
     *
     * Everything else the wizard asks for, medical details included, is collected for the
     * parent's own benefit and must never become a gate on their calendar.
     */
    val isSkippable: Boolean get() = this != Intro && this != Profile && this != Family

    companion object {
        /**
         * The steps this wizard will actually walk, given what the family co-parents.
         *
         * @param caresFor The answer to [Family]; an empty set is treated as children, which is
         *   what the step opens pre-answered with.
         */
        fun stepsFor(caresFor: Set<FamilyKind>): List<OnboardingStep> {
            val kinds = caresFor.ifEmpty { setOf(FamilyKind.CHILDREN) }
            return buildList {
                add(Intro)
                add(Family)
                add(Profile)
                if (FamilyKind.CHILDREN in kinds) {
                    add(Child)
                    add(Relatives)
                }
                if (FamilyKind.PETS in kinds) add(Pet)
                add(Split)
                add(Custody)
                add(CoParent)
            }
        }
    }
}
