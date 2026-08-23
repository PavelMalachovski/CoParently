package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import java.time.LocalDate

/**
 * The single answer to "whose day is this?".
 *
 * CLAUDE.md already requires every view to go through one custody lookup — reading the legacy
 * `CustodyScheduleEntity` rows directly is what once made model-based custody invisible. This
 * takes over that role and joins one-off swaps into it, **inside** the lookup rather than beside
 * it, so a caller cannot accidentally consult the pattern without the overrides.
 *
 * The ordering is the whole correctness of the feature:
 *
 * 1. an **accepted** [DayOverride] for that date;
 * 2. otherwise the active [CustodyModel];
 * 3. otherwise the legacy per-parent schedule;
 * 4. otherwise null — no arrangement is recorded, and a guess would be worse than a blank cell.
 *
 * A `PENDING` or `DECLINED` override changes nothing. Letting a pending swap move a day would
 * show both parents a day neither of them agreed to, on a screen they plan around; letting the
 * pattern win over an accepted swap would do the same in the other direction.
 */
object CustodyResolver {

    /**
     * Whose day [date] is.
     *
     * @param model The agreed pattern, or null when none is active
     * @param overrides One-off swaps keyed by ISO date; absent from the document reads as empty
     * @param legacy The legacy per-parent schedule lookup, consulted only when [model] is null
     * @param date The day being asked about
     * @return `"mom"`, `"dad"`, or null when nothing answers
     */
    fun custodyFor(
        model: CustodyModel?,
        overrides: Map<String, DayOverride>,
        legacy: (LocalDate) -> String?,
        date: LocalDate
    ): String? {
        overrides[date.toString()]
            ?.takeIf { it.isAccepted }
            ?.let { return it.toParent }

        // `getCustodyFor` never returns null, so the legacy schedule is reached only when there
        // is no active model at all — the same precedence the calendar's own lookup has always
        // had, with swaps layered on top rather than folded in.
        return model?.getCustodyFor(date) ?: legacy(date)
    }

    /**
     * [custodyFor] bound to one set of inputs, for callers that ask about many dates.
     *
     * The month grid asks 42 times per frame and the handover walk asks up to two pattern cycles,
     * so binding once and passing the function down is both cheaper and harder to get wrong than
     * handing four arguments to every call site.
     */
    fun resolver(
        model: CustodyModel?,
        overrides: Map<String, DayOverride>,
        legacy: (LocalDate) -> String?
    ): (LocalDate) -> String? = { date -> custodyFor(model, overrides, legacy, date) }

    /**
     * Whether the child changes hands on the morning of [date].
     *
     * A handover day is one whose custody differs from the day before it. Both days must resolve
     * to something: an unanswered day is not a handover, it is an unknown, and painting the split
     * cell for it would invent an arrangement.
     */
    fun isHandoverDay(custodyFor: (LocalDate) -> String?, date: LocalDate): Boolean {
        val today = custodyFor(date) ?: return false
        val yesterday = custodyFor(date.minusDays(1)) ?: return false
        return today != yesterday
    }
}
