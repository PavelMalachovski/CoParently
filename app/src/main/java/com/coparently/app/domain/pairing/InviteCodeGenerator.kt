package com.coparently.app.domain.pairing

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

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
     * Cryptographically secure default source of randomness.
     *
     * An invite code is a bearer credential: whoever presents it gains lasting read/write
     * access to a family's calendar, chat and finances. `Random.Default` is a linear
     * congruential-style PRNG whose internal state is recoverable from a handful of
     * observed outputs, which would let an attacker who has seen a few codes predict
     * others and undo the entropy the 31^6 alphabet is supposed to provide.
     * [SecureRandom] has no such structure.
     */
    private val secureRandom: Random by lazy { SecureRandom().asKotlinRandom() }

    /**
     * Returns a new random code.
     *
     * @param random Source of randomness; defaults to a [SecureRandom]-backed source and is
     *   injectable so tests can seed it deterministically.
     */
    fun generate(random: Random = secureRandom): String =
        buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /** Whether [code] could have been produced by [generate]. */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}
