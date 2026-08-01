package com.coparently.app.domain.pairing

import kotlin.random.Random

/**
 * Generates and validates the short codes a parent reads out or types to pair.
 *
 * The alphabet deliberately omits `O`, `0`, `I`, `1` and `L` so a code stays
 * unambiguous when it is dictated over the phone or copied by hand.
 */
object InviteCodeGenerator {

    /** The 31 characters a code may contain. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Number of characters in every code. */
    const val LENGTH = 6

    /**
     * Returns a new random code.
     *
     * @param random Source of randomness; injectable so tests can seed it.
     */
    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /** Whether [code] could have been produced by [generate]. */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}
