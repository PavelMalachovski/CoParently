package com.coparently.app.presentation.parentingplan

import com.coparently.app.domain.parentingplan.ParentingPlanCatalogue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The join between the stored catalogue and the wording on screen (MON-5).
 *
 * The two halves are deliberately separate — ids are stored data that must survive a rewording,
 * strings are five translations of a question — and the price of separating them is that they can
 * drift. This is what makes drift a failing build rather than a blank row on a document a parent
 * prints and hands to a mediator.
 *
 * It matters most at the moment MON-5 is finished: replacing this catalogue with the Ministry of
 * Justice's own questions is a data edit, and this is what says whether the edit was complete.
 */
class PlanStringsTest {

    @Test
    fun `every question the catalogue asks has wording`() {
        val missing = ParentingPlanCatalogue.questionIds - PlanStrings.knownQuestionIds
        assertTrue(missing.isEmpty(), "no wording for: $missing")
    }

    @Test
    fun `every section the catalogue has, has a heading`() {
        val missing = ParentingPlanCatalogue.sections.map { it.id }.toSet() - PlanStrings.knownSectionIds
        assertTrue(missing.isEmpty(), "no heading for: $missing")
    }

    @Test
    fun `no wording is left behind by a question the catalogue dropped`() {
        // The other direction, and not merely tidiness: a stale entry here is the trace of a
        // question that was removed without its answers being thought about, and stored answers
        // under a dropped id are kept rather than deleted.
        val orphans = PlanStrings.knownQuestionIds - ParentingPlanCatalogue.questionIds
        assertTrue(orphans.isEmpty(), "wording for questions nobody asks: $orphans")
    }

    @Test
    fun `the completeness check agrees with the two directions above`() {
        assertTrue(PlanStrings.isComplete())
    }

    @Test
    fun `the catalogue asks fourteen questions across seven sections`() {
        // Pinned so that growing the plan is a deliberate act. Every added question is one more
        // thing two separated parents have to agree on before the document reads as finished,
        // and the progress line on the screen counts against this number.
        assertEquals(7, ParentingPlanCatalogue.sections.size)
        assertEquals(14, ParentingPlanCatalogue.questions.size)
    }
}
