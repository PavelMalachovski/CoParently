package com.coparently.app.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * A colour a parent may choose for themselves.
 *
 * **The colour stopped being derived from the slot** (owner decision, Aug 2026). It used to be:
 * slot 1 pink, slot 2 blue, with pairing deciding who got which. That was already arbitrary —
 * nobody picks a slot — and it breaks outright once a person co-parents with two others, because
 * the same person can hold slot 1 in one family and slot 2 in the other, so their own events
 * would change colour as they switched families.
 *
 * So the colour is a property of the *person*, stored on their profile as
 * [com.coparently.app.domain.model.User.colorCode] — the field has existed all along and is
 * finally used for what it is named — and each parent picks their own.
 *
 * **Nothing stores a gender, and that is the point.** A man picking blue and a woman picking
 * pink gets the outcome that was asked for; two men simply pick differently. There is no special
 * case in the code, no inference to get wrong, and no new personal data to declare in the privacy
 * policy — for a field whose only use is deciding which dot is whose.
 *
 * The slot is untouched and keeps every job it has: `parentOwner`, custody, `momDayIndices`, the
 * Firestore schema. Only the *colour* stopped reading it.
 *
 * Each entry carries three tones for the reason `ParentColors` documents at length: the full
 * strength hue is **fill-only** — no entry here clears 4.5:1 as a foreground in both themes — so
 * text needs the theme-aware `light`/`dark` partner instead.
 *
 * @property storedCode The hex written to `users/{uid}.colorCode`. Stable: it is a stored value
 *   two devices compare, so an entry is never re-lettered.
 * @property fill The hue at full strength, for dots, bars, borders and custody tints.
 * @property light The text-grade tone on a dark surface.
 * @property dark The text-grade tone on a light surface.
 */
enum class ParentColorChoice(
    val storedCode: String,
    val fill: Color,
    val light: Color,
    val dark: Color
) {
    /** Material Pink. The app's original slot-1 colour, kept so nobody's calendar changes. */
    PINK("#E91E63", CoPlanlyColors.MomPink, CoPlanlyColors.MomPinkLight, CoPlanlyColors.MomPinkDark),

    /** Material Blue. The original slot-2 colour, kept for the same reason. */
    BLUE("#1976D2", CoPlanlyColors.DadBlue, CoPlanlyColors.DadBlueLight, CoPlanlyColors.DadBlueDark),

    /** Material Purple 700 / 200 / 900. */
    PURPLE("#7B1FA2", Color(0xFF7B1FA2), Color(0xFFCE93D8), Color(0xFF4A148C)),

    /** Material Deep Orange 800 / 200 / 900. */
    ORANGE("#D84315", Color(0xFFD84315), Color(0xFFFFAB91), Color(0xFFBF360C));

    companion object {

        /**
         * The choice a stored code names, or null when it names none.
         *
         * Case-insensitive because the value has been written by hand into fixtures and by
         * three different code paths. Null rather than a guess: the caller knows what to fall
         * back to, and silently mapping an unknown colour onto pink would make a co-parent's
         * deliberate choice look like the default.
         */
        fun fromStored(code: String?): ParentColorChoice? {
            val normalized = code?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.storedCode.uppercase() == normalized }
        }

        /**
         * What a parent holding [slot] is shown when they have not chosen.
         *
         * The colours the app has always used, so an account that upgrades sees no change until
         * somebody actually picks something.
         */
        fun defaultFor(slot: String): ParentColorChoice = if (slot == "dad") BLUE else PINK
    }
}

/**
 * The two colours in play for the family currently on screen.
 *
 * Keyed by *slot* rather than by uid because that is what every drawing call site already holds:
 * an event carries `parentOwner`, a custody day carries a slot, an expense is attributed to one.
 * Resolving uid → colour here, once, is what keeps [ParentColors] a lookup instead of nineteen
 * call sites each needing the two parents.
 *
 * @property slot1 The colour of whoever holds `"mom"`.
 * @property slot2 The colour of whoever holds `"dad"`.
 */
data class ParentPalette(
    val slot1: ParentColorChoice,
    val slot2: ParentColorChoice
) {
    /** The colour for a stored slot identifier; anything unrecognised reads as slot 1. */
    fun of(slot: String): ParentColorChoice = if (slot == "dad") slot2 else slot1

    companion object {

        /** Pink and blue — what the app looked like before anyone could choose. */
        val Default = ParentPalette(ParentColorChoice.PINK, ParentColorChoice.BLUE)

        /**
         * Builds the palette from what the two parents actually chose.
         *
         * **Two parents who chose the same colour are not drawn the same**, and resolving that
         * is this function's real job: the whole point of a parent colour is telling the two
         * apart, so a family where both picked blue must not have a calendar in one hue. Slot 1
         * keeps their choice and slot 2 is moved to the first colour neither of them holds —
         * not silently reassigned in storage, only drawn differently, so the parent's own
         * setting still says what they picked and stops being overridden the moment the other
         * one changes theirs.
         *
         * Slot 1 wins rather than "whoever chose first" because there is no reliable record of
         * who chose first: `colorCode` carries no timestamp, and inventing an order from two
         * profile documents that sync independently would pick a different winner on each
         * device.
         *
         * @param slot1Code `colorCode` of the parent in slot 1, or null when unknown.
         * @param slot2Code `colorCode` of the parent in slot 2, or null when unknown.
         */
        fun of(slot1Code: String?, slot2Code: String?): ParentPalette {
            val first = ParentColorChoice.fromStored(slot1Code)
                ?: ParentColorChoice.defaultFor("mom")
            val second = ParentColorChoice.fromStored(slot2Code)
                ?: ParentColorChoice.defaultFor("dad")
            if (first != second) return ParentPalette(first, second)
            val free = ParentColorChoice.entries.firstOrNull { it != first }
                ?: ParentColorChoice.BLUE
            return ParentPalette(first, free)
        }
    }
}
