package com.coparently.app.domain.custody

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * When the pair's custody document was last written, on the wire and in memory.
 *
 * The value decides which phone's schedule survives: `CustodyModelRepository.isNewer` compares
 * it, and the side it judges newer is not merely kept but **re-pushed over the other**. It used
 * to be `LocalDateTime.now()` formatted ISO — no zone, no offset — so two parents two or three
 * zones apart did not order their writes by real time, and the wrong side could win *and
 * overwrite*. That is SEC-4.
 *
 * **The field's name and its type on the wire are unchanged.** `custody_models.lastModifiedAt`
 * is still an ISO date-time string with no offset; what changed is that the string now expresses
 * **UTC** rather than the writer's local wall clock. Two things follow, and both are why this
 * shape was chosen over adding a numeric field beside it:
 *
 * * A co-parent on an older build keeps working exactly as before. It still parses the string,
 *   still compares it, and — the part that matters — still keys its "the schedule changed under
 *   you" banner on it. Changing the field's *type* would have left that build reading a blank,
 *   and a blank compares equal to the last dismissal, so every future change would have been
 *   silently un-announced. In a product whose premise is an adversarial counterparty, that is
 *   the worst possible failure.
 * * No document gains a key. `firestore.rules` gates a proposal write and a swap write with
 *   `affectedKeys().hasOnly([...])`, and a newly-introduced field appears in that diff — so the
 *   first proposal or swap made from an upgraded build would have been denied outright. Widening
 *   those lists is not an option: `lastModifiedAt` is deliberately absent from them precisely so
 *   a swap cannot re-date the document and win every later comparison.
 *
 * What this does **not** fix: a value written by an older build carries no offset to recover, so
 * reading it as UTC is wrong by that device's offset. That is irreducible — the information was
 * never stored. Between two upgraded builds the ordering is exact; against a legacy value it is
 * no worse than it already was.
 */
object CustodyTimestamp {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** What a write of [millis] puts in the document. */
    fun toWire(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDateTime().format(formatter)

    /**
     * The instant a document's `lastModifiedAt` names.
     *
     * Read as UTC, which is what an upgraded build wrote. Unreadable or absent answers `0` —
     * the epoch, the oldest value there is — so the row loses every comparison rather than
     * winning one it should not: a document that cannot be dated must never be re-pushed over
     * one that can. That is the same direction the previous string comparison degraded in, where
     * an unparseable value answered "not newer".
     */
    fun fromWire(iso: String?): Long {
        val text = iso?.takeIf { it.isNotBlank() } ?: return UNDATED
        return runCatching {
            LocalDateTime.parse(text, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(UNDATED)
    }

    /** A document or row whose write time is unknown. Loses every comparison. */
    const val UNDATED: Long = 0L
}
