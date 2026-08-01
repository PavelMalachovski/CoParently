package com.coparently.app.domain.pairing

/**
 * The `coplanly://pair?code=…` link used by the share sheet, the QR image and
 * the deep link. All three redeem the same invitation, so parsing lives here
 * once instead of in the scanner and the navigation graph separately.
 */
object PairingUri {

    /** Custom scheme; the app owns no domain, so App Links are not an option. */
    const val SCHEME = "coplanly"

    /** Host segment of the pairing link. */
    const val HOST = "pair"

    /** Builds the shareable link for [code]. */
    fun build(code: String): String = "$SCHEME://$HOST?code=$code"

    /**
     * Extracts a valid invite code from [input], which may be a bare code, a
     * full pairing URI, or free text containing one (a pasted share message).
     *
     * @return the upper-cased code, or null when [input] holds none.
     */
    fun extractCode(input: String): String? {
        val normalized = input.trim().uppercase()
        val fromUri = CODE_PARAM.find(normalized)?.groupValues?.get(1)
        val candidate = fromUri ?: normalized
        return candidate.takeIf { InviteCodeGenerator.isValid(it) }
    }

    private val CODE_PARAM = Regex("CODE=([A-Z0-9]{${InviteCodeGenerator.LENGTH}})")
}
