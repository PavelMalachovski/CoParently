package com.coparently.app.domain.parentingplan

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agreement between two separately written halves of a parenting plan (MON-5).
 *
 * The mechanism these tests exist for: an agreement records **the wording** the other parent had,
 * not a flag on the question. That is what makes it lapse by itself when either side edits — no
 * cross-write, and none is possible, since a parent may only write their own half. Every case
 * below is one where a flag would have gone on claiming two people had settled text that no
 * longer exists.
 */
class ParentingPlanComparisonTest {

    private val question = "care_weekday"
    private val now = 1_700_000_000_000L

    private fun mine(answer: String? = null) = ParentingPlanEntry()
        .let { if (answer == null) it else it.withAnswer(question, answer, now) }

    @Test
    fun `nobody has answered`() {
        assertEquals(
            PlanQuestionStatus.UNANSWERED,
            ParentingPlanComparison.statusOf(question, ParentingPlanEntry(), null)
        )
    }

    @Test
    fun `an unpaired parent is never in disagreement`() {
        // There is nobody to be open with. Rendering OPEN here would put a "you two disagree"
        // marker on a plan one person is filling in alone.
        assertEquals(
            PlanQuestionStatus.ONLY_YOURS,
            ParentingPlanComparison.statusOf(question, mine("Alternating weeks"), null)
        )
    }

    @Test
    fun `only the co-parent has answered`() {
        assertEquals(
            PlanQuestionStatus.ONLY_THEIRS,
            ParentingPlanComparison.statusOf(question, ParentingPlanEntry(), mine("Alternating weeks"))
        )
    }

    @Test
    fun `two answers with no agreement are open`() {
        val yours = mine("Alternating weeks")
        val theirs = ParentingPlanEntry().withAnswer(question, "Two days, then two days", now)

        assertEquals(PlanQuestionStatus.OPEN, ParentingPlanComparison.statusOf(question, yours, theirs))
    }

    @Test
    fun `one side agreeing is not agreement`() {
        val theirs = ParentingPlanEntry().withAnswer(question, "Alternating weeks", now)
        val yours = mine("Alternating weeks").withAgreement(question, "Alternating weeks", now)

        assertEquals(PlanQuestionStatus.OPEN, ParentingPlanComparison.statusOf(question, yours, theirs))
    }

    @Test
    fun `both agreeing to what the other wrote is agreement`() {
        val yours = mine("Alternating weeks").withAgreement(question, "Weeks, swapping Sunday", now)
        val theirs = ParentingPlanEntry()
            .withAnswer(question, "Weeks, swapping Sunday", now)
            .withAgreement(question, "Alternating weeks", now)

        assertEquals(PlanQuestionStatus.AGREED, ParentingPlanComparison.statusOf(question, yours, theirs))
    }

    @Test
    fun `the co-parent editing their answer lapses the agreement`() {
        // The defect a boolean flag would have. Their edit is a write to *their* half; this
        // device cannot clear a mark it stored, and does not have to.
        val yours = mine("Alternating weeks").withAgreement(question, "Weeks, swapping Sunday", now)
        val theirs = ParentingPlanEntry()
            .withAnswer(question, "Weeks, swapping Sunday", now)
            .withAgreement(question, "Alternating weeks", now)
            .withAnswer(question, "Weeks, swapping Friday", now + 1)

        assertEquals(PlanQuestionStatus.OPEN, ParentingPlanComparison.statusOf(question, yours, theirs))
    }

    @Test
    fun `editing your own answer lapses the agreement from both sides`() {
        val theirs = ParentingPlanEntry()
            .withAnswer(question, "Weeks, swapping Sunday", now)
            .withAgreement(question, "Alternating weeks", now)
        val yours = mine("Alternating weeks")
            .withAgreement(question, "Weeks, swapping Sunday", now)
            .withAnswer(question, "Alternating fortnights", now + 1)

        // Their mark still names the old wording, and this device dropped its own on the edit.
        assertEquals(PlanQuestionStatus.OPEN, ParentingPlanComparison.statusOf(question, yours, theirs))
        assertNull(yours.agreedTo[question])
    }

    @Test
    fun `re-agreeing after an edit restores agreement`() {
        val theirs = ParentingPlanEntry()
            .withAnswer(question, "Weeks, swapping Sunday", now)
            .withAgreement(question, "Alternating fortnights", now + 2)
        val yours = mine("Alternating weeks")
            .withAnswer(question, "Alternating fortnights", now + 1)
            .withAgreement(question, "Weeks, swapping Sunday", now + 2)

        assertEquals(PlanQuestionStatus.AGREED, ParentingPlanComparison.statusOf(question, yours, theirs))
    }

    @Test
    fun `a blank answer is the same as no answer`() {
        // The editor writes on every keystroke's save; clearing a field must read as unanswered
        // rather than as an answer that happens to be empty, or the progress count lies.
        val yours = mine("Alternating weeks").withAnswer(question, "   ", now + 1)

        assertNull(yours.answerTo(question))
        assertEquals(PlanQuestionStatus.UNANSWERED, ParentingPlanComparison.statusOf(question, yours, null))
    }

    @Test
    fun `answers are trimmed, and agreement compares the trimmed text`() {
        val theirs = ParentingPlanEntry().withAnswer(question, "  Weeks  ", now)
        val yours = mine("Weeks").withAgreement(question, "Weeks", now)
        val bothAgreed = theirs.withAgreement(question, "Weeks", now)

        assertEquals("Weeks", theirs.answerTo(question))
        assertEquals(
            PlanQuestionStatus.AGREED,
            ParentingPlanComparison.statusOf(question, yours, bothAgreed)
        )
    }

    @Test
    fun `progress counts only the questions the catalogue is asking`() {
        // An answer left behind by an older catalogue is kept rather than deleted — losing what a
        // parent wrote because the wording was revised is not an acceptable upgrade — but it must
        // not count towards progress against questions nobody is being shown.
        val yours = ParentingPlanEntry()
            .withAnswer(question, "Alternating weeks", now)
            .withAnswer("retired_question_from_an_older_catalogue", "Something", now)

        assertEquals(1, ParentingPlanComparison.answeredByYou(yours))
        assertTrue(yours.answers.containsKey("retired_question_from_an_older_catalogue"))
    }

    @Test
    fun `the agreed count runs over the whole catalogue`() {
        val first = ParentingPlanCatalogue.questions[0].id
        val second = ParentingPlanCatalogue.questions[1].id
        val yours = ParentingPlanEntry()
            .withAnswer(first, "A", now)
            .withAnswer(second, "B", now)
            .withAgreement(first, "C", now)
            .withAgreement(second, "D", now)
        val theirs = ParentingPlanEntry()
            .withAnswer(first, "C", now)
            .withAnswer(second, "D", now)
            .withAgreement(first, "A", now)

        assertEquals(1, ParentingPlanComparison.agreedCount(yours, theirs))
    }

    @Test
    fun `every catalogue question id is unique`() {
        // They key stored answers on two phones. A duplicate would silently merge two questions
        // into one answer, and the loss would only show up as a mediator asking why a section is
        // blank.
        assertEquals(
            ParentingPlanCatalogue.questions.size,
            ParentingPlanCatalogue.questionIds.size
        )
        assertEquals(
            ParentingPlanCatalogue.sections.size,
            ParentingPlanCatalogue.sections.map { it.id }.toSet().size
        )
    }
}
