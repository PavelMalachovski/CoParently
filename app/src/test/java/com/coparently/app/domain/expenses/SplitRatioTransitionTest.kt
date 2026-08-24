package com.coparently.app.domain.expenses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that make a split an agreement rather than an announcement.
 *
 * The two that earn this file are the ones that would otherwise let one parent move the money on
 * their own: deciding your own proposal, and proposing over the co-parent's pending one.
 */
class SplitRatioTransitionTest {

    private val mom = "uid-mom"
    private val dad = "uid-dad"

    private fun settings(
        ratio: SplitRatio = SplitRatio.EVEN,
        proposal: SplitRatioProposal? = null
    ) = FamilySettings(
        ratio = ratio,
        participants = listOf(mom, dad).sorted(),
        lastModifiedBy = mom,
        lastModifiedAtMillis = 1_756_000_000_000L,
        proposal = proposal
    )

    private fun proposal(by: String, momPercent: Int) = SplitRatioProposal(
        ratio = SplitRatio.ofMomPercent(momPercent),
        proposedBy = by,
        proposedAtMillis = 1_756_000_100_000L
    )

    @Test
    fun `proposing leaves the agreed ratio exactly where it was`() {
        val next = SplitRatioTransition
            .propose(settings(), SplitRatio.ofMomPercent(70), mom, 1L)
            .getOrThrow()

        assertEquals(SplitRatio.EVEN, next.ratio)
        assertEquals(70, next.proposal?.ratio?.momPercent)
    }

    @Test
    fun `a parent may not decide their own proposal`() {
        val result = SplitRatioTransition.accept(
            settings(proposal = proposal(mom, 70)),
            byUid = mom,
            atMillis = 2L
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `accepting moves the agreed ratio and clears the proposal`() {
        val next = SplitRatioTransition
            .accept(settings(proposal = proposal(mom, 70)), byUid = dad, atMillis = 2L)
            .getOrThrow()

        assertEquals(70, next.ratio.momPercent)
        assertNull(next.proposal)
        assertEquals(dad, next.lastModifiedBy)
        assertEquals(SplitRatioOutcome.ACCEPTED, next.lastDecision?.outcome)
    }

    @Test
    fun `declining changes nothing but the record of the answer`() {
        val before = settings(proposal = proposal(mom, 70))
        val next = SplitRatioTransition.decline(before, byUid = dad, atMillis = 2L).getOrThrow()

        assertEquals(SplitRatio.EVEN, next.ratio)
        assertEquals(before.lastModifiedBy, next.lastModifiedBy)
        assertNull(next.proposal)
        assertEquals(SplitRatioOutcome.DECLINED, next.lastDecision?.outcome)
    }

    @Test
    fun `a counter-proposal may not bury the co-parent's pending one`() {
        // Answering theirs is the way past it. Silently replacing it would lose an answer the
        // other parent is waiting for.
        val result = SplitRatioTransition.propose(
            settings(proposal = proposal(dad, 70)),
            SplitRatio.ofMomPercent(60),
            byUid = mom,
            atMillis = 3L
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `a parent may correct their own pending proposal`() {
        val next = SplitRatioTransition
            .propose(settings(proposal = proposal(mom, 70)), SplitRatio.ofMomPercent(65), mom, 3L)
            .getOrThrow()

        assertEquals(65, next.proposal?.ratio?.momPercent)
    }

    @Test
    fun `proposing what is already agreed is refused, not sent as a question`() {
        val result = SplitRatioTransition.propose(settings(), SplitRatio.EVEN, mom, 3L)

        assertTrue(result.isFailure)
    }

    @Test
    fun `only the parent who proposed may withdraw`() {
        val before = settings(proposal = proposal(mom, 70))

        assertTrue(SplitRatioTransition.withdraw(before, byUid = dad).isFailure)
        assertNull(SplitRatioTransition.withdraw(before, byUid = mom).getOrThrow().proposal)
    }

    @Test
    fun `a share is stored exactly, so a month of expenses cannot drift`() {
        val ratio = SplitRatio.ofMomPercent(70)

        assertEquals(7000, ratio.momShareBasisPoints)
        assertEquals(3000, ratio.dadShareBasisPoints)
        assertEquals(0.7, ratio.shareFor("mom"), 0.0)
        assertEquals(0.3, ratio.shareFor("dad"), 0.0)
        // A uid that is not a party to the agreement owes nothing; inventing a debt for a
        // stranger is the one arithmetic mistake this must never make.
        assertEquals(0.0, ratio.shareFor("friend"), 0.0)
    }
}
