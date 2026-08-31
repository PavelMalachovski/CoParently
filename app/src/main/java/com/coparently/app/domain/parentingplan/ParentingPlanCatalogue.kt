package com.coparently.app.domain.parentingplan

/**
 * The questions a parenting plan asks, as stored identifiers (MON-5).
 *
 * **What this is, stated precisely, because the distinction is the whole product point.** The
 * Czech Ministry of Justice publishes an official parenting-plan form at
 * `vyzivne.justice.cz/rodicovsky-plan`, and the reason MON-5 is worth building is that a mediator
 * or an OSPOD worker *recognises that form*. The catalogue below is **not** that form. Its areas
 * are the ones § 858 of the občanský zákoník enumerates as parental responsibility — care for the
 * child's health and development, contact, education, place of residence — plus the practical
 * headings the Ministry's own page names: communication, school, activities, medical care,
 * holidays, money. Its wording is this project's.
 *
 * That is recorded here rather than glossed over, and it is why nothing in the UI calls this the
 * Ministry's form: an affordance that promises a document a court already accepts, and delivers
 * one somebody wrote, is design rule 8's forbidden promise in the one place it would do real
 * damage — a paper a parent hands to a mediator.
 *
 * **Replacing it is a data edit.** Swap the entries below for the official ones, add the wording
 * to the five `parenting_plan_strings.xml` files, bump [VERSION]. Nothing else changes: answers
 * are keyed by [Question.id], and an id that disappears from the catalogue keeps its stored
 * answer rather than losing it — see `ParentingPlanEntry`.
 *
 * **Ids are stored data and are never renamed.** They key every answer in Room and in Firestore,
 * on both parents' phones, and a rename silently orphans everything written under the old one.
 */
object ParentingPlanCatalogue {

    /**
     * The catalogue's revision, stored beside every answer.
     *
     * Not read for anything yet, and deliberately written anyway: when the questions are replaced
     * with the official ones, a stored answer needs to say which set it was answering. Deriving
     * that later from the ids present is guesswork, and the field costs one integer.
     */
    const val VERSION: Int = 1

    /** One question. [id] is stored; the wording lives in the five string files. */
    data class Question(val id: String)

    /** A run of questions under one heading. [id] is stored for the same reason. */
    data class Section(val id: String, val questions: List<Question>)

    /**
     * The sections, in the order they are asked.
     *
     * Order is part of the document a parent reads and prints, so it belongs here rather than in
     * whatever a screen happens to iterate.
     */
    val sections: List<Section> = listOf(
        Section(
            id = "residence",
            questions = listOf(Question("residence_home"), Question("residence_change"))
        ),
        Section(
            id = "care",
            questions = listOf(Question("care_weekday"), Question("care_handover"))
        ),
        Section(
            id = "holidays",
            questions = listOf(Question("holidays_school"), Question("holidays_special"))
        ),
        Section(
            id = "education",
            questions = listOf(Question("education_school"), Question("education_activities"))
        ),
        Section(
            id = "health",
            questions = listOf(Question("health_doctor"), Question("health_decisions"))
        ),
        Section(
            id = "money",
            questions = listOf(Question("money_maintenance"), Question("money_extraordinary"))
        ),
        Section(
            id = "communication",
            questions = listOf(
                Question("communication_between_parents"),
                Question("communication_disagreement")
            )
        )
    )

    /** Every question, flattened, in the order [sections] asks them. */
    val questions: List<Question> = sections.flatMap { it.questions }

    /** Every question id, for the callers that only need membership. */
    val questionIds: Set<String> = questions.map { it.id }.toSet()
}
