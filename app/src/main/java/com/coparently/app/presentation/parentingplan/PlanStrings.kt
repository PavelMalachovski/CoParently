package com.coparently.app.presentation.parentingplan

import androidx.annotation.StringRes
import com.coparently.app.R
import com.coparently.app.domain.parentingplan.ParentingPlanCatalogue

/**
 * The wording for each parenting-plan section and question (MON-5).
 *
 * Kept out of `ParentingPlanCatalogue` on purpose. The catalogue is stored vocabulary — ids that
 * key answers on two phones — and it stays free of Android so the comparison logic can be tested
 * at all; the wording is five translations of a question and belongs where every other string in
 * this app lives. The join between them is one map per level, and `PlanStringsTest` fails the
 * build if an id here has no entry, because the alternative is a blank row on a screen a parent
 * may print.
 */
object PlanStrings {

    private val sectionTitles: Map<String, Int> = mapOf(
        "residence" to R.string.parenting_plan_section_residence,
        "care" to R.string.parenting_plan_section_care,
        "holidays" to R.string.parenting_plan_section_holidays,
        "education" to R.string.parenting_plan_section_education,
        "health" to R.string.parenting_plan_section_health,
        "money" to R.string.parenting_plan_section_money,
        "communication" to R.string.parenting_plan_section_communication
    )

    private val questionPrompts: Map<String, Int> = mapOf(
        "residence_home" to R.string.parenting_plan_q_residence_home,
        "residence_change" to R.string.parenting_plan_q_residence_change,
        "care_weekday" to R.string.parenting_plan_q_care_weekday,
        "care_handover" to R.string.parenting_plan_q_care_handover,
        "holidays_school" to R.string.parenting_plan_q_holidays_school,
        "holidays_special" to R.string.parenting_plan_q_holidays_special,
        "education_school" to R.string.parenting_plan_q_education_school,
        "education_activities" to R.string.parenting_plan_q_education_activities,
        "health_doctor" to R.string.parenting_plan_q_health_doctor,
        "health_decisions" to R.string.parenting_plan_q_health_decisions,
        "money_maintenance" to R.string.parenting_plan_q_money_maintenance,
        "money_extraordinary" to R.string.parenting_plan_q_money_extraordinary,
        "communication_between_parents" to R.string.parenting_plan_q_communication_between_parents,
        "communication_disagreement" to R.string.parenting_plan_q_communication_disagreement
    )

    /** The heading for [sectionId], or null when the catalogue has outrun this file. */
    @StringRes
    fun sectionTitle(sectionId: String): Int? = sectionTitles[sectionId]

    /** The question text for [questionId], or null when the catalogue has outrun this file. */
    @StringRes
    fun questionPrompt(questionId: String): Int? = questionPrompts[questionId]

    /** Every id this file has wording for — what the test compares against the catalogue. */
    val knownSectionIds: Set<String> get() = sectionTitles.keys

    /** Every question id this file has wording for. */
    val knownQuestionIds: Set<String> get() = questionPrompts.keys

    /** True when every id [ParentingPlanCatalogue] asks about has wording here. */
    fun isComplete(): Boolean =
        knownSectionIds.containsAll(ParentingPlanCatalogue.sections.map { it.id }) &&
            knownQuestionIds.containsAll(ParentingPlanCatalogue.questionIds)
}
