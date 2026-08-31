package com.coparently.app.domain.parentingplan

/**
 * One parent's half of a parenting plan, and how the two halves compare (MON-5).
 *
 * The practice the feature copies is the paper one: each parent fills the form in **separately**,
 * and a mediator or an OSPOD worker lays the two side by side to see where they already agree.
 * So there is no shared document either parent edits — there are two, one per parent, and the
 * comparison is derived. That is also what makes the security rule simple: each parent writes
 * only their own key, which `firestore.rules` can check with a `hasOnly`, and no write of mine
 * can ever change what the other parent said.
 */
data class ParentingPlanEntry(
    /**
     * Answers by [ParentingPlanCatalogue.Question.id]. A blank or missing value is unanswered —
     * the two are the same thing to every reader, so nothing distinguishes them.
     */
    val answers: Map<String, String> = emptyMap(),

    /**
     * What this parent has agreed to, as **the co-parent's answer text they ticked**, keyed by
     * question id.
     *
     * Storing the text rather than a bare "agreed" flag is the whole mechanism, and it is worth
     * not undoing. A flag would go stale the moment either answer was edited, and neither phone
     * could clear the other's flag — a parent may only write their own half of the plan — so an
     * agreement would keep claiming two people had settled wording that no longer exists.
     * Comparing the text makes an agreement lapse by itself on the next read, on both devices,
     * with no cross-write and nothing to clean up. It costs a copy of a paragraph the reader
     * could already see.
     */
    val agreedTo: Map<String, String> = emptyMap(),

    /**
     * The catalogue this parent was answering, from [ParentingPlanCatalogue.VERSION].
     *
     * See there for why it is written before anything reads it.
     */
    val catalogueVersion: Int = ParentingPlanCatalogue.VERSION,

    /** When this half was last edited, epoch millis — the two parents may be in two zones. */
    val updatedAtMillis: Long = 0L
) {

    /** The answer to [questionId], or null when it is blank or absent. */
    fun answerTo(questionId: String): String? = answers[questionId]?.takeIf { it.isNotBlank() }

    /**
     * This entry with [answer] recorded for [questionId].
     *
     * **Editing an answer drops this parent's own agreement on that question**, because the thing
     * they agreed to was a pairing of two texts and one of them has just changed. The co-parent's
     * agreement lapses on its own — it names the old text of *this* answer — which is exactly
     * what [ParentingPlanComparison] is derived from rather than stored.
     */
    fun withAnswer(questionId: String, answer: String, nowMillis: Long): ParentingPlanEntry {
        val trimmed = answer.trim()
        val nextAnswers = if (trimmed.isEmpty()) answers - questionId else answers + (questionId to trimmed)
        return copy(
            answers = nextAnswers,
            agreedTo = agreedTo - questionId,
            catalogueVersion = ParentingPlanCatalogue.VERSION,
            updatedAtMillis = nowMillis
        )
    }

    /**
     * This entry with the co-parent's [theirAnswer] to [questionId] marked as agreed, or the mark
     * removed when [theirAnswer] is null.
     *
     * @param theirAnswer The co-parent's answer as it reads now. Passing what is on screen is the
     *   point: an agreement is to a wording, not to a question.
     */
    fun withAgreement(questionId: String, theirAnswer: String?, nowMillis: Long): ParentingPlanEntry {
        val text = theirAnswer?.trim()?.takeIf { it.isNotEmpty() }
        return copy(
            agreedTo = if (text == null) agreedTo - questionId else agreedTo + (questionId to text),
            updatedAtMillis = nowMillis
        )
    }
}

/** Where one question stands between the two parents. */
enum class PlanQuestionStatus {
    /** Neither parent has written anything. */
    UNANSWERED,

    /** Only the signed-in parent has answered. */
    ONLY_YOURS,

    /** Only the co-parent has answered — the one status that asks the reader to do something. */
    ONLY_THEIRS,

    /** Both have answered and at least one has not agreed to what the other wrote. */
    OPEN,

    /** Both have answered and each has ticked the other's answer as it currently reads. */
    AGREED
}

/**
 * How the two halves of a plan compare, question by question.
 *
 * Pure, and separate from any repository, because everything interesting about this feature is
 * here: an agreement that must lapse when either side edits, and a co-parent who may not exist
 * yet. Both are cheap to get wrong and cheap to test.
 */
object ParentingPlanComparison {

    /**
     * The status of [questionId] given the two halves.
     *
     * @param yours The signed-in parent's half.
     * @param theirs The co-parent's half, or null while the account is unpaired or their half has
     *   not arrived. An absent co-parent is not a disagreement — it is [PlanQuestionStatus.ONLY_YOURS]
     *   at most, never [PlanQuestionStatus.OPEN], because there is nobody to be open with.
     */
    fun statusOf(
        questionId: String,
        yours: ParentingPlanEntry,
        theirs: ParentingPlanEntry?
    ): PlanQuestionStatus {
        val mine = yours.answerTo(questionId)
        val other = theirs?.answerTo(questionId)
        return when {
            mine == null && other == null -> PlanQuestionStatus.UNANSWERED
            other == null -> PlanQuestionStatus.ONLY_YOURS
            mine == null -> PlanQuestionStatus.ONLY_THEIRS
            agreed(questionId, yours, theirs, mine, other) -> PlanQuestionStatus.AGREED
            else -> PlanQuestionStatus.OPEN
        }
    }

    /**
     * Whether both parents have ticked the wording the other has **now**.
     *
     * Each side's mark names the text it was given. An edit on either side changes one of those
     * texts, the comparison stops matching, and the agreement lapses on both phones at once
     * without either of them writing anything.
     */
    private fun agreed(
        questionId: String,
        yours: ParentingPlanEntry,
        theirs: ParentingPlanEntry,
        yourAnswer: String,
        theirAnswer: String
    ): Boolean = yours.agreedTo[questionId] == theirAnswer && theirs.agreedTo[questionId] == yourAnswer

    /**
     * How many of [ParentingPlanCatalogue]'s questions are [PlanQuestionStatus.AGREED].
     *
     * The number a mediator asks for first, and the one the screen leads with.
     */
    fun agreedCount(yours: ParentingPlanEntry, theirs: ParentingPlanEntry?): Int =
        ParentingPlanCatalogue.questionIds.count {
            statusOf(it, yours, theirs) == PlanQuestionStatus.AGREED
        }

    /**
     * How many of [ParentingPlanCatalogue]'s questions the signed-in parent has answered.
     *
     * Counted over the **catalogue**, not over the stored answers: an answer left behind by an
     * older catalogue is kept rather than deleted, and counting it here would show a parent as
     * further along than the questions in front of them.
     */
    fun answeredByYou(yours: ParentingPlanEntry): Int =
        ParentingPlanCatalogue.questionIds.count { yours.answerTo(it) != null }
}
