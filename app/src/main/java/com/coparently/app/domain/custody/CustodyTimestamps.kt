package com.coparently.app.domain.custody

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When the pair's shared schedule was last written, as one instant both phones agree on.
 *
 * The field this replaces was a naive `LocalDateTime` formatted ISO — no zone, no offset — and
 * it does not merely get displayed: `CustodyModelRepository.isNewer` compares it and
 * `mirrorIntoRoom` acts on the answer, **re-pushing the side it judges newer over the other**.
 * So for two parents two or three zones apart the wrong side could win *and overwrite*, by
 * exactly the offset between them, with the loser's phone told only that "the schedule changed".
 * Silent-wrong-answer, not silent-loss, but wrong all the same.
 *
 * Epoch millis names one instant and has no zone to get wrong. The same move
 * `Message.sentAtMillis` made for chat, for the same reason and with the same shape: the wire
 * keeps the old field so a co-parent on an older build stays readable, and the read path accepts
 * either.
 *
 * Pure and free of Android because the conversion is the only part that can be wrong, and its
 * callers are a Room DAO and a Firestore listener that no test in this project can reach.
 */
object CustodyTimestamps {

    /**
     * The instant a document was last written, from the two fields it may carry.
     *
     * Prefers [millis] — a number written by any build that has this change. Falls back to
     * parsing [legacyIso], which is what a co-parent on an older build writes and what every
     * document written before this change holds.
     *
     * **The fallback is a guess, and knowingly so.** A naive local date-time means "that
     * device's wall clock", and nothing on the wire says whose. Reading it in [zone] — the
     * reader's own — is exact only when the two phones share a zone. It is still strictly better
     * than the string comparison it replaces, which made the same guess implicitly and then hid
     * it: this one is confined to documents an older build wrote, and disappears the first time
     * a current build writes the document.
     *
     * Anything unusable is 0, which [isNewer] treats as "cannot tell" rather than as "ancient" —
     * see there for why that direction is the safe one.
     */
    fun fromWire(millis: Any?, legacyIso: Any?, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (millis is Number) {
            val value = millis.toLong()
            if (value > 0L) return value
        }
        val iso = (legacyIso as? String)?.takeIf { it.isNotBlank() } ?: return 0L
        return runCatching {
            LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    /**
     * The legacy ISO string to keep writing alongside the millis.
     *
     * A co-parent on an older build reads only this field, and reads it with `as? String`; a
     * number there would come back blank and their `isNewer` would then judge their own local
     * copy newer and re-push it over this one. Writing both costs one field and keeps a mixed
     * pair behaving exactly as it does today until both sides upgrade.
     *
     * Naive local, in [zone], because that is the shape an older build knows how to parse.
     */
    fun toLegacyIso(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /**
     * Whether [candidate] was written strictly later than [reference].
     *
     * A non-positive value on either side answers `false` — "cannot tell". That preserves the
     * behaviour the string version had for an unparseable timestamp, and it is the safe
     * direction: the caller uses this to decide whether to **re-push the local copy over the
     * remote one**, so "cannot tell" must mean "leave the document alone", never "mine wins".
     */
    fun isNewer(candidate: Long, reference: Long): Boolean =
        candidate > 0L && reference > 0L && candidate > reference
}
